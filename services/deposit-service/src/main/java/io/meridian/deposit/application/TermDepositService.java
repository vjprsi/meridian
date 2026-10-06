package io.meridian.deposit.application;

import static io.meridian.deposit.infrastructure.Rows.bool;
import static io.meridian.deposit.infrastructure.Rows.date;
import static io.meridian.deposit.infrastructure.Rows.dec;
import static io.meridian.deposit.infrastructure.Rows.decOr0;
import static io.meridian.deposit.infrastructure.Rows.str;
import static io.meridian.deposit.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.deposit.config.DepositProperties;
import io.meridian.deposit.domain.AccountNumbers;
import io.meridian.deposit.domain.Currencies;
import io.meridian.deposit.domain.DayCount;
import io.meridian.deposit.domain.Lifecycle;
import io.meridian.deposit.domain.Schedule;
import io.meridian.deposit.domain.TermDepositCalculator;
import io.meridian.deposit.infrastructure.ProductCatalogClient;
import io.meridian.deposit.infrastructure.Rows;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Term deposits: placement (own ledger account funded from a deposit account), top-up, partial withdrawal,
 * premature break with penalty, liens (ledger holds), rollover instructions and maturity processing.
 *
 * <p>Interest is simple interest at the contracted rate. When the principal changes during the term the interest
 * earned so far is crystallised into {@code accrued_interest} and {@code metadata.principal_since} records the date
 * from which the new principal earns interest.
 */
@Service
public class TermDepositService {
    static final String TABLE = "term_deposit";
    private static final Set<String> PAYS_OUT = Set.of("PAY_OUT_ALL", "ROLL_PRINCIPAL_PAY_INTEREST");

    private final Rows rows;
    private final AccountSupport accounts;
    private final LedgerPort ledger;
    private final Events events;
    private final DepositProperties props;
    private final Schedule.BusinessCalendar calendar;
    private final ProductCatalogClient catalog;
    private final SecureRandom random = new SecureRandom();

    public TermDepositService(Rows rows, AccountSupport accounts, LedgerPort ledger, Events events,
                              DepositProperties props, Schedule.BusinessCalendar calendar,
                              ProductCatalogClient catalog) {
        this.rows = rows;
        this.accounts = accounts;
        this.ledger = ledger;
        this.events = events;
        this.props = props;
        this.calendar = calendar;
        this.catalog = catalog;
    }

    // ------------------------------------------------------------------ placement

    @Transactional
    public ObjectNode create(Object body) {
        ObjectNode v = rows.columns(TABLE, body);
        String ccy = str(v, "currency");
        BigDecimal principal = dec(v, "principal_amount");
        if (principal == null || principal.signum() <= 0 || !Currencies.fitsMinorUnit(principal, ccy)) {
            throw ApiException.unprocessable("invalid_principal", "principal_amount must be positive with at most "
                    + Currencies.digits(ccy) + " decimals");
        }
        BigDecimal rate = dec(v, "interest_rate");
        if (rate == null || rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) >= 0) {
            throw ApiException.unprocessable("invalid_rate", "interest_rate is an annual fraction in [0, 1)");
        }
        BigDecimal penalty = dec(v, "premature_penalty_rate");
        if (penalty != null && penalty.signum() < 0) {
            throw ApiException.unprocessable("invalid_penalty_rate", "premature_penalty_rate must not be negative");
        }
        int term = v.path("term_days").asInt(0);
        if (term < 1 || term > 3660) {
            throw ApiException.unprocessable("invalid_term", "term_days must be between 1 and 3660");
        }
        DayCount dc;
        try {
            dc = DayCount.parse(str(v, "day_count"));
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable("invalid_day_count", e.getMessage());
        }
        v.put("day_count", dc.name());
        LocalDate start = date(v, "start_date");
        if (start.isBefore(accounts.today())) {
            throw ApiException.unprocessable("start_date_in_past", "start_date must be today or later");
        }
        LocalDate maturity = TermDepositCalculator.maturityDate(start, term, calendar);
        if (!maturity.equals(date(v, "maturity_date"))) {
            throw ApiException.unprocessable("invalid_maturity_date", "maturity_date must be " + maturity
                    + " (start_date + term_days, moved to the next business day)");
        }
        String payoutMode = v.path("interest_payout_mode").asString("AT_MATURITY");
        if (!"AT_MATURITY".equals(payoutMode)) {
            throw ApiException.unprocessable("unsupported_payout_mode", "Only AT_MATURITY interest payout is supported");
        }
        UUID customer = uuid(v, "customer_id");
        UUID funding = uuid(v, "funding_account_id");
        if (funding != null) {
            ownedAccount(funding, customer, ccy, "funding_account_id");
        }
        UUID payout = uuid(v, "payout_account_id");
        if (payout != null) {
            ownedAccount(payout, customer, ccy, "payout_account_id");
        }
        String instruction = v.path("rollover_instruction").asString("PAY_OUT_ALL");
        if (PAYS_OUT.contains(instruction) && payout == null && funding == null) {
            throw ApiException.unprocessable("payout_account_required", instruction + " needs a payout_account_id");
        }
        v.set("product_terms", catalog.snapshot(uuid(v, "product_id"), uuid(v, "product_version_id"), ccy));
        v.put("deposit_number", uniqueDepositNumber());
        v.put("expected_interest", TermDepositCalculator.expectedInterest(principal, rate, dc, start, maturity, ccy)
                .toPlainString());
        v.put("status", "PENDING_FUNDING");
        ObjectNode meta = v.path("metadata").isObject() ? (ObjectNode) v.get("metadata") : v.putObject("metadata");
        meta.put("principal_since", start.toString());
        ObjectNode td = rows.insert(TABLE, v);
        event(td, "PLACED", principal, null, null, null, null, "Placement requested");
        return td;
    }

    private ObjectNode ownedAccount(UUID accountId, UUID customer, String ccy, String field) {
        ObjectNode acc = accounts.reference(accountId, field);
        accounts.requireCurrency(acc, ccy);
        if (customer != null && !customer.toString().equals(str(acc, "customer_id"))) {
            boolean holder = rows.scalar("""
                    select count(*) from account_holder where deposit_account_id = ? and customer_id = ? and status = 'ACTIVE'""",
                    Long.class, accountId, customer).orElse(0L) > 0;
            if (!holder) {
                throw ApiException.unprocessable("account_not_owned", field + " must belong to the depositor");
            }
        }
        return acc;
    }

    @Transactional
    public ObjectNode fund(UUID id) {
        ObjectNode td = rows.lock(TABLE, id);
        Lifecycle.TERM_DEPOSIT.require(str(td, "status"), "ACTIVE");
        UUID fundingId = uuid(td, "funding_account_id");
        if (fundingId == null) {
            throw ApiException.unprocessable("funding_account_required", "The deposit has no funding_account_id");
        }
        ObjectNode funding = accounts.lock(fundingId);
        accounts.requireDebitAllowed(funding);
        UUID source = accounts.requireLedger(funding);
        String ccy = str(td, "currency");
        BigDecimal principal = dec(td, "principal_amount");
        LocalDate today = accounts.today();
        ObjectNode v = rows.object();
        LocalDate start = date(td, "start_date");
        if (start.isBefore(today)) { // funded late: the term starts when the money arrives
            start = today;
            LocalDate maturity = TermDepositCalculator.maturityDate(start, td.path("term_days").asInt(), calendar);
            v.put("start_date", start.toString());
            v.put("maturity_date", maturity.toString());
            v.put("expected_interest", TermDepositCalculator.expectedInterest(principal, dec(td, "interest_rate"),
                    dc(td), start, maturity, ccy).toPlainString());
        }
        UUID tdLedger = ensureLedger(td);
        UUID je = ledger.post(new LedgerPort.Posting(LedgerPort.key("td-fund", id, null), "TRANSFER",
                "Term deposit " + str(td, "deposit_number") + " placement", ccy, "TERM_DEPOSIT", id, null, List.of(
                        LedgerPort.Leg.debit(source, principal, "Term deposit placement"),
                        LedgerPort.Leg.credit(tdLedger, principal, "Term deposit placement"))));
        v.put("status", "ACTIVE");
        v.put("ledger_account_id", tdLedger.toString());
        ObjectNode meta = meta(td);
        meta.put("principal_since", start.toString());
        v.set("metadata", meta);
        ObjectNode updated = rows.update(TABLE, id, v);
        touchActivity(fundingId);
        event(updated, "FUNDED", principal, null, null, null, je, null);
        events.publish("accounts.term_deposit.placed", TABLE, updated);
        return updated;
    }

    private UUID ensureLedger(JsonNode td) {
        UUID existing = uuid(td, "ledger_account_id");
        if (existing != null) {
            return existing;
        }
        UUID id = uuid(td, "id");
        return ledger.openAccount(LedgerPort.key("td-ledger-account", id, null), str(td, "deposit_number"),
                "Term deposit " + str(td, "deposit_number"), str(td, "currency"), BigDecimal.ZERO, null, "TERM_DEPOSIT", id);
    }

    // ------------------------------------------------------------------ changes during the term

    @Transactional
    public ObjectNode topUp(UUID id, BigDecimal amount, String clientKey) {
        ObjectNode td = rows.lock(TABLE, id);
        Lifecycle.TERM_DEPOSIT.requireIn(str(td, "status"), "top up", "ACTIVE");
        if (!(meta(td).path("allow_top_up").asBoolean(false) || ProductSnapshot.of(td).flag("allow_top_up"))) {
            throw ApiException.unprocessable("top_up_not_allowed", "This deposit's product does not allow top-ups");
        }
        String ccy = str(td, "currency");
        requireAmount(amount, ccy);
        LocalDate today = accounts.today();
        if (!today.isBefore(date(td, "maturity_date"))) {
            throw ApiException.conflict("maturity_reached", "The deposit has reached maturity");
        }
        UUID fundingId = uuid(td, "funding_account_id");
        if (fundingId == null) {
            throw ApiException.unprocessable("funding_account_required", "The deposit has no funding_account_id");
        }
        ObjectNode funding = accounts.lock(fundingId);
        accounts.requireDebitAllowed(funding);
        UUID je = ledger.post(new LedgerPort.Posting(LedgerPort.key("td-top-up", id, clientKey), "TRANSFER",
                "Term deposit top-up", ccy, "TERM_DEPOSIT", id, null, List.of(
                        LedgerPort.Leg.debit(accounts.requireLedger(funding), amount, "Term deposit top-up"),
                        LedgerPort.Leg.credit(uuid(td, "ledger_account_id"), amount, "Term deposit top-up"))));
        ObjectNode updated = changePrincipal(td, dec(td, "principal_amount").add(amount), BigDecimal.ZERO);
        touchActivity(fundingId);
        event(updated, "TOPPED_UP", amount, null, null, null, je, null);
        return updated;
    }

    @Transactional
    public ObjectNode withdraw(UUID id, BigDecimal amount, String reason, String clientKey) {
        ObjectNode td = rows.lock(TABLE, id);
        Lifecycle.TERM_DEPOSIT.requireIn(str(td, "status"), "withdraw from", "ACTIVE");
        if (!bool(td, "allow_partial_withdrawal")) {
            throw ApiException.unprocessable("partial_withdrawal_not_allowed", "This deposit does not allow partial withdrawals");
        }
        String ccy = str(td, "currency");
        requireAmount(amount, ccy);
        BigDecimal principal = dec(td, "principal_amount");
        if (amount.compareTo(principal) >= 0) {
            throw ApiException.unprocessable("withdrawal_exceeds_principal", "Use break to withdraw the whole deposit");
        }
        BigDecimal remaining = principal.subtract(amount);
        BigDecimal lien = decOr0(meta(td).path("lien"), "amount");
        if (bool(td, "has_lien") && remaining.compareTo(lien) < 0) {
            throw ApiException.unprocessable("deposit_under_lien", "The remaining principal must cover the lien of "
                    + lien.toPlainString());
        }
        BigDecimal minimum = ProductSnapshot.dec(meta(td), "min_principal");
        if (minimum != null && remaining.compareTo(minimum) < 0) {
            throw ApiException.unprocessable("below_minimum_principal", "The remaining principal would fall below "
                    + minimum.toPlainString());
        }
        LocalDate today = accounts.today();
        BigDecimal penaltyRate = dec(td, "premature_penalty_rate");
        BigDecimal penalty = penaltyRate == null ? BigDecimal.ZERO : TermDepositCalculator.interest(amount, penaltyRate,
                dc(td), date(td, "start_date"), today, ccy);
        UUID payoutLedger = accounts.requireLedger(payoutAccount(td));
        UUID je = ledger.post(new LedgerPort.Posting(LedgerPort.key("td-withdraw", id, clientKey), "TRANSFER",
                "Term deposit partial withdrawal" + (reason == null ? "" : ": " + reason), ccy, "TERM_DEPOSIT", id, null,
                List.of(LedgerPort.Leg.debit(uuid(td, "ledger_account_id"), amount, "Partial withdrawal"),
                        LedgerPort.Leg.credit(payoutLedger, amount, "Partial withdrawal"))));
        ObjectNode updated = changePrincipal(td, remaining, penalty);
        event(updated, "PARTIALLY_WITHDRAWN", amount, null, penalty, null, je, reason);
        return updated;
    }

    /** Crystallises interest at the old principal, applies a penalty, and restarts interest on the new principal. */
    private ObjectNode changePrincipal(ObjectNode td, BigDecimal newPrincipal, BigDecimal penalty) {
        LocalDate today = accounts.today();
        String ccy = str(td, "currency");
        BigDecimal rate = dec(td, "interest_rate");
        BigDecimal crystallised = interestUpTo(td, today).subtract(penalty).max(BigDecimal.ZERO);
        ObjectNode meta = meta(td);
        meta.put("principal_since", today.toString());
        ObjectNode v = rows.object();
        v.put("principal_amount", newPrincipal.toPlainString());
        v.put("accrued_interest", crystallised.toPlainString());
        v.put("expected_interest", crystallised.add(TermDepositCalculator.interest(newPrincipal, rate, dc(td), today,
                date(td, "maturity_date"), ccy)).toPlainString());
        v.set("metadata", meta);
        return rows.update(TABLE, uuid(td, "id"), v);
    }

    /** Contract-rate interest earned up to {@code day}: crystallised part plus the current principal since then. */
    BigDecimal interestUpTo(JsonNode td, LocalDate day) {
        LocalDate since = since(td);
        return decOr0(td, "accrued_interest").add(TermDepositCalculator.interest(dec(td, "principal_amount"),
                dec(td, "interest_rate"), dc(td), since, day, str(td, "currency")));
    }

    // ------------------------------------------------------------------ break

    public ObjectNode breakQuote(UUID id) {
        ObjectNode td = rows.get(TABLE, id);
        Lifecycle.TERM_DEPOSIT.requireIn(str(td, "status"), "quote a break for", "ACTIVE");
        TermDepositCalculator.BreakQuote q = quote(td, accounts.today());
        ObjectNode out = rows.object();
        out.put("term_deposit_id", id.toString());
        out.put("currency", str(td, "currency"));
        out.put("as_of", q.asOf().toString());
        out.put("days_held", q.daysHeld());
        out.put("principal", q.principal().toPlainString());
        out.put("interest_at_contract_rate", q.accruedAtContractRate().toPlainString());
        out.put("effective_rate", q.effectiveRate().stripTrailingZeros().toPlainString());
        out.put("interest_payable", q.interestPayable().toPlainString());
        out.put("penalty", q.penalty().toPlainString());
        out.put("tax", q.tax().toPlainString());
        out.put("payout", q.payout().toPlainString());
        out.put("breakable", !bool(td, "has_lien"));
        if (bool(td, "has_lien")) {
            out.put("blocker", "deposit is pledged under lien " + meta(td).path("lien").path("reference").asString(""));
        }
        return out;
    }

    private TermDepositCalculator.BreakQuote quote(JsonNode td, LocalDate asOf) {
        return TermDepositCalculator.breakQuote(dec(td, "principal_amount"), dec(td, "interest_rate"),
                dec(td, "premature_penalty_rate"), dc(td), date(td, "start_date"), since(td), asOf,
                decOr0(td, "accrued_interest"), taxRate(td), str(td, "currency"));
    }

    @Transactional
    public ObjectNode breakDeposit(UUID id, String reason) {
        ObjectNode td = rows.lock(TABLE, id);
        Lifecycle.TERM_DEPOSIT.require(str(td, "status"), "BROKEN");
        if (bool(td, "has_lien")) {
            throw ApiException.unprocessable("deposit_under_lien", "Release the lien before breaking the deposit");
        }
        LocalDate today = accounts.today();
        TermDepositCalculator.BreakQuote q = quote(td, today);
        String ccy = str(td, "currency");
        UUID tdLedger = uuid(td, "ledger_account_id");
        UUID payoutLedger = accounts.requireLedger(payoutAccount(td));
        BigDecimal net = q.interestPayable().subtract(q.tax());
        UUID je = ledger.post(new LedgerPort.Posting(LedgerPort.key("td-break", id, null), "TRANSFER",
                "Premature withdrawal of term deposit " + str(td, "deposit_number") + ": " + reason, ccy, "TERM_DEPOSIT",
                id, null, List.of(
                        LedgerPort.Leg.debit(tdLedger, q.principal(), "Principal"),
                        LedgerPort.Leg.debit(props.gl().interestExpense(), q.interestPayable(), "Term deposit interest"),
                        LedgerPort.Leg.credit(payoutLedger, q.principal().add(net), "Term deposit payout"),
                        LedgerPort.Leg.credit(props.gl().taxPayable(), q.tax(), "Withholding tax"))));
        ledger.restrict(tdLedger, "ACTIVE", "CLOSED", "Term deposit broken", LedgerPort.key("td-close", id, null));
        ObjectNode v = rows.object();
        v.put("status", "BROKEN");
        v.put("accrued_interest", q.interestPayable().toPlainString());
        ObjectNode updated = rows.update(TABLE, id, v);
        event(updated, "BROKEN", q.principal(), q.interestPayable(), q.penalty(), q.tax(), je, reason);
        events.publish("accounts.term_deposit.broken", TABLE, updated);
        return updated;
    }

    // ------------------------------------------------------------------ instructions and liens

    @Transactional
    public ObjectNode changeRollover(UUID id, String instruction, UUID payoutAccount) {
        ObjectNode td = rows.lock(TABLE, id);
        Lifecycle.TERM_DEPOSIT.requireIn(str(td, "status"), "change the maturity instruction of", "PENDING_FUNDING", "ACTIVE");
        if (!accounts.today().isBefore(date(td, "maturity_date"))) {
            throw ApiException.conflict("maturity_reached", "Maturity instructions can only change before the maturity date");
        }
        ObjectNode v = rows.object();
        if (payoutAccount != null) {
            ownedAccount(payoutAccount, uuid(td, "customer_id"), str(td, "currency"), "payout_account_id");
            v.put("payout_account_id", payoutAccount.toString());
        }
        if (PAYS_OUT.contains(instruction) && payoutAccount == null && uuid(td, "payout_account_id") == null
                && uuid(td, "funding_account_id") == null) {
            throw ApiException.unprocessable("payout_account_required", instruction + " needs a payout_account_id");
        }
        v.put("rollover_instruction", instruction);
        return rows.update(TABLE, id, v);
    }

    @Transactional
    public ObjectNode markLien(UUID id, String reference, BigDecimal amount, String clientKey) {
        ObjectNode td = rows.lock(TABLE, id);
        Lifecycle.TERM_DEPOSIT.requireIn(str(td, "status"), "pledge", "ACTIVE");
        if (bool(td, "has_lien")) {
            throw ApiException.conflict("lien_exists", "The deposit is already pledged under "
                    + meta(td).path("lien").path("reference").asString(""));
        }
        requireAmount(amount, str(td, "currency"));
        if (amount.compareTo(dec(td, "principal_amount")) > 0) {
            throw ApiException.unprocessable("lien_exceeds_principal", "A lien cannot exceed the principal");
        }
        UUID hold = ledger.placeHold(LedgerPort.key("td-lien", id, clientKey), uuid(td, "ledger_account_id"), amount,
                str(td, "currency"), "RESERVATION", "Lien " + reference, "TERM_DEPOSIT", id);
        ObjectNode meta = meta(td);
        ObjectNode lien = meta.putObject("lien");
        lien.put("reference", reference);
        lien.put("amount", amount.toPlainString());
        lien.put("hold_id", hold.toString());
        lien.put("marked_on", accounts.today().toString());
        ObjectNode v = rows.object();
        v.put("has_lien", true);
        v.set("metadata", meta);
        ObjectNode updated = rows.update(TABLE, id, v);
        event(updated, "LIEN_MARKED", amount, null, null, null, null, "Lien " + reference);
        return updated;
    }

    @Transactional
    public ObjectNode releaseLien(UUID id, String reference, String clientKey) {
        ObjectNode td = rows.lock(TABLE, id);
        if (!bool(td, "has_lien")) {
            throw ApiException.conflict("no_lien", "The deposit is not under lien");
        }
        JsonNode lien = meta(td).path("lien");
        if (!reference.equals(lien.path("reference").asString(""))) {
            throw ApiException.unprocessable("lien_reference_mismatch", "Lien reference does not match");
        }
        if (lien.hasNonNull("hold_id")) {
            ledger.releaseHold(UUID.fromString(lien.get("hold_id").asString()), "Lien " + reference + " released",
                    LedgerPort.key("td-lien-release", id, clientKey));
        }
        ObjectNode meta = meta(td);
        meta.remove("lien");
        ObjectNode v = rows.object();
        v.put("has_lien", false);
        v.set("metadata", meta);
        ObjectNode updated = rows.update(TABLE, id, v);
        event(updated, "LIEN_RELEASED", decOr0(lien, "amount"), null, null, null, null, "Lien " + reference + " released");
        return updated;
    }

    public String certificate(UUID id) {
        ObjectNode td = rows.get(TABLE, id);
        String status = str(td, "status");
        if ("PENDING_FUNDING".equals(status) || "CANCELLED".equals(status)) {
            throw ApiException.conflict("deposit_not_placed", "A certificate is issued once the deposit is funded");
        }
        String ccy = str(td, "currency");
        return "TERM DEPOSIT CERTIFICATE\n"
                + "Certificate no : TDC-" + str(td, "deposit_number") + "\n"
                + "Customer id    : " + str(td, "customer_id") + "\n"
                + "Principal      : " + Currencies.round(dec(td, "principal_amount"), ccy).toPlainString() + " " + ccy + "\n"
                + "Interest rate  : " + dec(td, "interest_rate").multiply(BigDecimal.valueOf(100)).stripTrailingZeros()
                .toPlainString() + "% p.a. (" + str(td, "day_count") + ")\n"
                + "Value date     : " + str(td, "start_date") + "\n"
                + "Maturity date  : " + str(td, "maturity_date") + " (" + td.path("term_days").asInt() + " days)\n"
                + "Interest at maturity: " + Currencies.round(decOr0(td, "expected_interest"), ccy).toPlainString() + " " + ccy + "\n"
                + "Maturity instruction: " + str(td, "rollover_instruction") + "\n"
                + "Status         : " + status + (bool(td, "has_lien") ? " (under lien)" : "") + "\n"
                + "This certificate is not negotiable.\n";
    }

    // ------------------------------------------------------------------ maturity (end-of-day)

    /** Publishes "maturing" notices for deposits maturing within {@code noticeDays}. */
    @Transactional
    public int notifyMaturing(LocalDate asOf, int noticeDays) {
        int n = 0;
        for (ObjectNode td : rows.query("""
                select row_to_json(t)::text from term_deposit t where status = 'ACTIVE' and maturity_date <= ?
                  and maturity_date > ? and coalesce((metadata->>'maturing_notified')::boolean, false) = false
                order by id for update""", asOf.plusDays(noticeDays), asOf)) {
            ObjectNode meta = meta(td);
            meta.put("maturing_notified", true);
            ObjectNode v = rows.object();
            v.set("metadata", meta);
            events.publish("accounts.term_deposit.maturing", TABLE, rows.update(TABLE, uuid(td, "id"), v));
            n++;
        }
        return n;
    }

    /** Matures every ACTIVE deposit whose maturity date is on or before {@code asOf}. Returns deposits processed. */
    @Transactional
    public List<ObjectNode> processMaturities(LocalDate asOf) {
        List<ObjectNode> out = new ArrayList<>();
        for (ObjectNode td : rows.query("""
                select row_to_json(t)::text from term_deposit t where status = 'ACTIVE' and maturity_date <= ?
                order by maturity_date, id for update""", asOf)) {
            out.add(mature(td));
        }
        return out;
    }

    private ObjectNode mature(ObjectNode td) {
        UUID id = uuid(td, "id");
        String ccy = str(td, "currency");
        LocalDate maturity = date(td, "maturity_date");
        BigDecimal principal = dec(td, "principal_amount");
        BigDecimal gross = Currencies.round(interestUpTo(td, maturity), ccy);
        BigDecimal tax = TermDepositCalculator.tax(gross, taxRate(td), ccy);
        BigDecimal net = gross.subtract(tax);
        UUID tdLedger = uuid(td, "ledger_account_id");
        String instruction = str(td, "rollover_instruction");
        String label = "Term deposit " + str(td, "deposit_number") + " maturity";
        UUID payoutId = uuid(td, "payout_account_id") != null ? uuid(td, "payout_account_id") : uuid(td, "funding_account_id");
        boolean lien = bool(td, "has_lien");
        if (lien || (PAYS_OUT.contains(instruction) && payoutId == null)) {
            instruction = "ROLL_PRINCIPAL_AND_INTEREST".equals(instruction) && !lien ? instruction : "RETAIN";
        }
        UUID je;
        ObjectNode v = rows.object();
        v.put("accrued_interest", gross.toPlainString());
        switch (instruction) {
            case "PAY_OUT_ALL" -> {
                UUID payout = accounts.requireLedger(accounts.get(payoutId));
                je = ledger.post(new LedgerPort.Posting(LedgerPort.key("td-mature", id, null), "INTEREST", label, ccy,
                        "TERM_DEPOSIT", id, maturity, List.of(
                                LedgerPort.Leg.debit(tdLedger, principal, "Principal repayment"),
                                LedgerPort.Leg.debit(props.gl().interestExpense(), gross, "Term deposit interest"),
                                LedgerPort.Leg.credit(payout, principal.add(net), "Term deposit maturity proceeds"),
                                LedgerPort.Leg.credit(props.gl().taxPayable(), tax, "Withholding tax"))));
                ledger.restrict(tdLedger, "ACTIVE", "CLOSED", "Matured and paid out", LedgerPort.key("td-close", id, null));
                v.put("status", "MATURED");
                ObjectNode updated = rows.update(TABLE, id, v);
                event(updated, "MATURED", principal, gross, null, tax, je, null);
                event(updated, "PAID_OUT", principal.add(net), net, null, null, je, null);
                events.publish("accounts.term_deposit.matured", TABLE, updated);
                return updated;
            }
            case "ROLL_PRINCIPAL_PAY_INTEREST", "ROLL_PRINCIPAL_AND_INTEREST" -> {
                boolean payInterest = "ROLL_PRINCIPAL_PAY_INTEREST".equals(instruction);
                List<LedgerPort.Leg> legs = new ArrayList<>();
                legs.add(LedgerPort.Leg.debit(props.gl().interestExpense(), gross, "Term deposit interest"));
                legs.add(LedgerPort.Leg.credit(props.gl().taxPayable(), tax, "Withholding tax"));
                legs.add(LedgerPort.Leg.credit(payInterest ? accounts.requireLedger(accounts.get(payoutId)) : tdLedger, net,
                        "Term deposit interest"));
                je = ledger.post(new LedgerPort.Posting(LedgerPort.key("td-mature", id, null), "INTEREST", label, ccy,
                        "TERM_DEPOSIT", id, maturity, legs));
                BigDecimal rolled = payInterest ? principal : principal.add(net);
                ObjectNode next = rollover(td, rolled, maturity);
                UUID nextLedger = uuid(next, "ledger_account_id");
                UUID moveJe = ledger.post(new LedgerPort.Posting(LedgerPort.key("td-rollover", id, null), "TRANSFER",
                        "Rollover into " + str(next, "deposit_number"), ccy, "TERM_DEPOSIT", id, maturity, List.of(
                                LedgerPort.Leg.debit(tdLedger, rolled, "Rollover"),
                                LedgerPort.Leg.credit(nextLedger, rolled, "Rollover"))));
                ledger.restrict(tdLedger, "ACTIVE", "CLOSED", "Rolled over", LedgerPort.key("td-close", id, null));
                v.put("status", "ROLLED_OVER");
                ObjectNode updated = rows.update(TABLE, id, v);
                event(updated, "MATURED", principal, gross, null, tax, je, null);
                event(updated, "ROLLED_OVER", rolled, payInterest ? null : net, null, null, moveJe,
                        "Rolled into " + str(next, "deposit_number"));
                event(next, "FUNDED", rolled, null, null, null, moveJe, "Rollover of " + str(td, "deposit_number"));
                events.publish("accounts.term_deposit.matured", TABLE, updated);
                events.publish("accounts.term_deposit.placed", TABLE, next);
                return updated;
            }
            default -> { // RETAIN: lien or no payout account — interest credited to the deposit, funds stay put
                je = ledger.post(new LedgerPort.Posting(LedgerPort.key("td-mature", id, null), "INTEREST", label, ccy,
                        "TERM_DEPOSIT", id, maturity, List.of(
                                LedgerPort.Leg.debit(props.gl().interestExpense(), gross, "Term deposit interest"),
                                LedgerPort.Leg.credit(tdLedger, net, "Term deposit interest"),
                                LedgerPort.Leg.credit(props.gl().taxPayable(), tax, "Withholding tax"))));
                v.put("status", "MATURED");
                ObjectNode updated = rows.update(TABLE, id, v);
                event(updated, "MATURED", principal, gross, null, tax, je,
                        lien ? "Proceeds retained: deposit under lien" : "Proceeds retained: no payout account");
                events.publish("accounts.term_deposit.matured", TABLE, updated);
                return updated;
            }
        }
    }

    private ObjectNode rollover(JsonNode old, BigDecimal principal, LocalDate start) {
        int term = old.path("term_days").asInt();
        LocalDate maturity = TermDepositCalculator.maturityDate(start, term, calendar);
        String ccy = str(old, "currency");
        UUID id = Ids.newId();
        ObjectNode v = rows.object();
        v.put("id", id.toString());
        v.put("deposit_number", uniqueDepositNumber());
        for (String f : List.of("customer_id", "product_id", "product_version_id", "funding_account_id",
                "payout_account_id", "currency", "interest_rate", "day_count", "interest_payout_mode",
                "rollover_instruction", "premature_penalty_rate", "allow_partial_withdrawal")) {
            if (old.hasNonNull(f)) {
                v.set(f, old.get(f));
            }
        }
        v.put("principal_amount", principal.toPlainString());
        v.put("term_days", term);
        v.put("start_date", start.toString());
        v.put("maturity_date", maturity.toString());
        v.put("expected_interest", TermDepositCalculator.expectedInterest(principal, dec(old, "interest_rate"), dc(old),
                start, maturity, ccy).toPlainString());
        v.put("auto_rollover_count", old.path("auto_rollover_count").asInt(0) + 1);
        v.put("previous_deposit_id", str(old, "id"));
        v.put("status", "ACTIVE");
        ObjectNode meta = rows.object();
        meta.put("principal_since", start.toString());
        if (old.hasNonNull("product_terms")) {
            v.set("product_terms", old.get("product_terms"));
        }
        v.set("metadata", meta);
        v.put("ledger_account_id", ledger.openAccount(LedgerPort.key("td-ledger-account", id, null),
                str(v, "deposit_number"), "Term deposit " + str(v, "deposit_number"), ccy, BigDecimal.ZERO, null,
                "TERM_DEPOSIT", id).toString());
        return rows.insert(TABLE, v);
    }

    // ------------------------------------------------------------------ helpers

    private String uniqueDepositNumber() {
        for (int attempt = 0; attempt < 20; attempt++) {
            String candidate = "TD" + AccountNumbers.generate(12, random);
            if (rows.scalar("select 1 from term_deposit where deposit_number = ?", Integer.class, candidate).isEmpty()) {
                return candidate;
            }
        }
        throw ApiException.conflict("deposit_number_exhausted", "Could not allocate a unique deposit number");
    }

    private ObjectNode payoutAccount(JsonNode td) {
        UUID payout = uuid(td, "payout_account_id") != null ? uuid(td, "payout_account_id") : uuid(td, "funding_account_id");
        if (payout == null) {
            throw ApiException.unprocessable("payout_account_required", "The deposit has no payout or funding account");
        }
        ObjectNode acc = accounts.get(payout);
        accounts.requireCreditAllowed(acc);
        return acc;
    }

    private void event(JsonNode td, String type, BigDecimal amount, BigDecimal interest, BigDecimal penalty,
                       BigDecimal tax, UUID je, String note) {
        ObjectNode e = rows.object();
        e.put("term_deposit_id", str(td, "id"));
        e.put("event_type", type);
        if (amount != null) {
            e.put("amount", amount.toPlainString());
        }
        if (interest != null) {
            e.put("interest_amount", interest.toPlainString());
        }
        if (penalty != null) {
            e.put("penalty_amount", penalty.toPlainString());
        }
        if (tax != null) {
            e.put("tax_amount", tax.toPlainString());
        }
        if (je != null) {
            e.put("journal_entry_id", je.toString());
        }
        if (note != null) {
            e.put("note", note.length() > 500 ? note.substring(0, 500) : note);
        }
        rows.insert("term_deposit_event", e);
    }

    private void touchActivity(UUID accountId) {
        rows.execute("update deposit_account set last_customer_activity_at = now() where id = ?", accountId);
    }

    private BigDecimal taxRate(JsonNode td) {
        return ProductSnapshot.of(td).withholdingTaxRate().orElse(props.withholdingTaxRate());
    }

    private static void requireAmount(BigDecimal amount, String ccy) {
        if (amount == null || amount.signum() <= 0 || !Currencies.fitsMinorUnit(amount, ccy)) {
            throw ApiException.unprocessable("invalid_amount", "amount must be positive with at most "
                    + Currencies.digits(ccy) + " decimals");
        }
    }

    private static DayCount dc(JsonNode td) {
        return DayCount.parse(str(td, "day_count"));
    }

    private static LocalDate since(JsonNode td) {
        String s = td.path("metadata").path("principal_since").asString(null);
        return s == null ? date(td, "start_date") : LocalDate.parse(s);
    }

    private ObjectNode meta(JsonNode td) {
        JsonNode m = td.get("metadata");
        return m != null && m.isObject() ? ((ObjectNode) m).deepCopy() : rows.object();
    }

    static long days(LocalDate a, LocalDate b) {
        return ChronoUnit.DAYS.between(a, b);
    }
}
