package io.meridian.loan.application;

import static io.meridian.loan.infrastructure.Rows.dec;
import static io.meridian.loan.infrastructure.Rows.decOr0;
import static io.meridian.loan.infrastructure.Rows.str;
import static io.meridian.loan.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.loan.domain.CreditLineMath;
import io.meridian.loan.domain.Currencies;
import io.meridian.loan.domain.DayCount;
import io.meridian.loan.domain.Delinquency;
import io.meridian.loan.domain.Frequency;
import io.meridian.loan.domain.Lifecycles;
import io.meridian.loan.domain.ScheduleGenerator;
import io.meridian.loan.infrastructure.ExternalClients;
import io.meridian.loan.infrastructure.Rows;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Revolving credit lines: activation (receivable account), draws within the available limit, payments (interest, fees,
 * then principal and the oldest statement), limit changes (temporary limits, approval above a threshold), freeze /
 * unfreeze / close, conversion of balances into instalment loans, draw reversals, monthly statements with average
 * daily balance interest and minimum payment, missed-minimum delinquency and autopay.
 */
@Service
public class CreditLineService {
    static final String TABLE = "credit_line";
    static final String DRAWS = "credit_line_drawdown";
    static final String STATEMENTS = "credit_line_statement";
    static final MathContext MC = MathContext.DECIMAL128;

    private final LoanSupport loans;
    private final Rows rows;
    private final ExternalClients external;
    private final LoanService loanService;

    public CreditLineService(LoanSupport loans, ExternalClients external, LoanService loanService) {
        this.loans = loans;
        this.rows = loans.rows();
        this.external = external;
        this.loanService = loanService;
    }

    // ------------------------------------------------------------------ create / lifecycle

    @Transactional
    public ObjectNode create(JsonNode req) {
        ObjectNode values = rows.columns(TABLE, req);
        String currency = str(values, "currency");
        BigDecimal limit = dec(values, "credit_limit");
        if (limit == null || limit.signum() <= 0 || !Currencies.fitsMinorUnit(limit, currency)) {
            throw ApiException.unprocessable("invalid_limit", "credit_limit must be positive");
        }
        int day = values.path("statement_day").asInt(0);
        if (day < 1 || day > 28) {
            throw ApiException.unprocessable("invalid_statement_day", "statement_day must be 1-28");
        }
        BigDecimal pct = dec(values, "minimum_payment_pct");
        if (pct == null || pct.signum() <= 0 || pct.compareTo(BigDecimal.ONE) > 0) {
            throw ApiException.unprocessable("invalid_minimum_payment", "minimum_payment_pct must be in (0, 1]");
        }
        for (String r : List.of("purchase_rate", "cash_advance_rate", "penalty_rate")) {
            BigDecimal v = dec(values, r);
            if (v != null && (v.signum() < 0 || v.compareTo(BigDecimal.ONE) > 0)) {
                throw ApiException.unprocessable("invalid_rate", r + " must be between 0 and 1");
            }
        }
        if (values.path("payment_due_days").asInt(21) < 1) {
            throw ApiException.unprocessable("invalid_payment_due_days", "payment_due_days must be positive");
        }
        external.productTerms(uuid(values, "product_id"), uuid(values, "product_version_id"), currency);
        if (values.hasNonNull("repayment_account_id")) {
            external.depositLedgerAccount(uuid(values, "repayment_account_id"), currency, "repayment_account_id");
        }
        String autopay = str(values, "autopay_mode");
        if (autopay != null && !Set.of("NONE", "MINIMUM", "STATEMENT", "FULL").contains(autopay)) {
            throw ApiException.unprocessable("invalid_autopay_mode", "autopay_mode must be NONE, MINIMUM, STATEMENT or FULL");
        }
        values.put("credit_line_number", number());
        values.put("status", "PENDING_ACTIVATION");
        values.put("available_amount", "0");
        ObjectNode line = rows.insert(TABLE, values);
        loans.events().publish("lending.credit_line.created", TABLE, line);
        return line;
    }

    private String number() {
        for (int i = 0; i < 10; i++) {
            String n = "CL" + String.format("%010d", Math.floorMod(new java.security.SecureRandom().nextLong(), 10_000_000_000L));
            if (!rows.scalar("select exists(select 1 from credit_line where credit_line_number = ?)", Boolean.class, n).orElse(false)) {
                return n;
            }
        }
        throw new IllegalStateException("could not allocate a credit line number");
    }

    @Transactional
    public ObjectNode activate(UUID id) {
        ObjectNode line = rows.lock(TABLE, id);
        move(line, "ACTIVE");
        if (!"PENDING_ACTIVATION".equals(str(line, "status"))) {
            throw ApiException.conflict("invalid_transition", "Only pending credit lines can be activated");
        }
        UUID acc = loans.ledger().openReceivable(LedgerPort.key("credit-line-receivable", id, null),
                "CL-" + str(line, "credit_line_number"), "Credit line " + str(line, "credit_line_number"), str(line, "currency"),
                "CREDIT_LINE", id);
        ObjectNode upd = rows.object();
        upd.put("status", "ACTIVE");
        upd.put("ledger_account_id", acc.toString());
        upd.put("opened_at", loans.now().toString());
        upd.put("last_statement_date", loans.today().minusDays(1).toString());
        rows.update(TABLE, id, upd);
        line = refreshAvailable(id);
        loans.events().publish("lending.credit_line.activated", TABLE, line);
        return line;
    }

    @Transactional
    public ObjectNode freeze(UUID id, String reason) {
        return status(id, "FROZEN", reason, Set.of("ACTIVE", "IN_ARREARS"));
    }

    @Transactional
    public ObjectNode unfreeze(UUID id, String reason) {
        ObjectNode line = rows.get(TABLE, id);
        String to = line.path("days_past_due").asInt(0) > 0 ? "IN_ARREARS" : "ACTIVE";
        if ("IN_ARREARS".equals(to)) {
            return status(id, "IN_ARREARS", reason, Set.of("FROZEN"));
        }
        return status(id, "ACTIVE", reason, Set.of("FROZEN", "SUSPENDED"));
    }

    @Transactional
    public ObjectNode close(UUID id, String reason) {
        ObjectNode line = rows.lock(TABLE, id);
        BigDecimal owed = decOr0(line, "utilised_amount").add(decOr0(line, "instalment_balance"))
                .add(decOr0(line, "outstanding_interest")).add(decOr0(line, "outstanding_fees"));
        if (owed.signum() > 0) {
            throw ApiException.conflict("balance_outstanding", "The credit line still owes " + owed.toPlainString());
        }
        ObjectNode closed = status(id, "CLOSED", reason == null ? "closed" : reason,
                Set.of("PENDING_ACTIVATION", "ACTIVE", "FROZEN", "SUSPENDED"));
        UUID acc = uuid(closed, "ledger_account_id");
        if (acc != null) {
            loans.ledger().close(acc, "Credit line closed", LedgerPort.key("credit-line-close", id, null));
        }
        ObjectNode upd = rows.object();
        upd.put("closed_at", loans.now().toString());
        upd.put("available_amount", "0");
        return rows.update(TABLE, id, upd);
    }

    private ObjectNode status(UUID id, String to, String reason, Set<String> from) {
        ObjectNode line = rows.lock(TABLE, id);
        if (!from.contains(str(line, "status"))) {
            throw ApiException.conflict("invalid_transition", "Credit line in status " + str(line, "status") + " cannot become " + to);
        }
        move(line, to);
        ObjectNode upd = rows.object();
        upd.put("status", to);
        upd.put("status_reason", reason);
        ObjectNode updated = rows.update(TABLE, id, upd);
        loans.events().publish("lending.credit_line.status_changed", TABLE, updated);
        return updated;
    }

    private static void move(JsonNode line, String to) {
        try {
            if (!str(line, "status").equals(to)) {
                Lifecycles.require(Lifecycles.CREDIT_LINE, "Credit line", str(line, "status"), to);
            }
        } catch (Lifecycles.InvalidTransition e) {
            throw ApiException.conflict(e.code(), e.getMessage());
        }
    }

    /** Recomputes the available amount with the database clock deciding whether the temporary limit still applies. */
    public ObjectNode refreshAvailable(UUID id) {
        return rows.queryOne("""
                update credit_line set available_amount = case when status in ('ACTIVE') then greatest(
                    coalesce(case when temporary_limit is not null and temporary_limit_expires_at > now() then temporary_limit end, credit_limit)
                    - utilised_amount - instalment_balance - outstanding_interest - outstanding_fees, 0) else 0 end,
                  temporary_limit = case when temporary_limit_expires_at <= now() then null else temporary_limit end,
                  temporary_limit_expires_at = case when temporary_limit_expires_at <= now() then null else temporary_limit_expires_at end
                where id = ? returning row_to_json(credit_line)::text""", id).orElseThrow(() -> ApiException.notFound("credit line", id));
    }

    public ObjectNode available(UUID id) {
        ObjectNode line = rows.get(TABLE, id);
        boolean tempActive = rows.scalar("select temporary_limit is not null and temporary_limit_expires_at > now() from credit_line where id = ?",
                Boolean.class, id).orElse(false);
        BigDecimal limit = tempActive ? decOr0(line, "temporary_limit") : decOr0(line, "credit_limit");
        BigDecimal used = decOr0(line, "utilised_amount");
        BigDecimal inst = decOr0(line, "instalment_balance");
        BigDecimal interest = decOr0(line, "outstanding_interest");
        BigDecimal fees = decOr0(line, "outstanding_fees");
        BigDecimal available = "ACTIVE".equals(str(line, "status"))
                ? limit.subtract(used).subtract(inst).subtract(interest).subtract(fees).max(BigDecimal.ZERO) : BigDecimal.ZERO;
        ObjectNode out = rows.object();
        out.put("credit_line_id", id.toString());
        out.put("status", str(line, "status"));
        out.put("currency", str(line, "currency"));
        out.put("credit_limit", str(line, "credit_limit"));
        out.put("temporary_limit_active", tempActive);
        if (tempActive) {
            out.put("temporary_limit", str(line, "temporary_limit"));
            out.put("temporary_limit_expires_at", str(line, "temporary_limit_expires_at"));
        }
        out.put("effective_limit", limit.toPlainString());
        out.put("utilised_amount", used.toPlainString());
        out.put("instalment_balance", inst.toPlainString());
        out.put("outstanding_interest", interest.toPlainString());
        out.put("outstanding_fees", fees.toPlainString());
        out.put("available_amount", available.toPlainString());
        out.put("total_owed", used.add(inst).add(interest).add(fees).toPlainString());
        return out;
    }

    // ------------------------------------------------------------------ draws

    @Transactional
    public ObjectNode draw(UUID id, JsonNode req, String idempotencyKey) {
        ObjectNode line = rows.lock(TABLE, id);
        String status = str(line, "status");
        if ("FROZEN".equals(status)) {
            throw ApiException.conflict("credit_line_frozen", "The credit line is frozen: " + str(line, "status_reason"));
        }
        if (!"ACTIVE".equals(status)) {
            throw ApiException.conflict("invalid_transition", "Draws are not allowed on a " + status + " credit line");
        }
        Optional<ObjectNode> replay = rows.queryOne("select row_to_json(d)::text from credit_line_drawdown d where idempotency_key = ?", idempotencyKey);
        if (replay.isPresent()) {
            return replay.get();
        }
        String type = str(req, "draw_type");
        if ("INSTALMENT_CONVERSION".equals(type)) {
            throw ApiException.unprocessable("invalid_draw_type", "Use convert-to-instalments");
        }
        BigDecimal amount = dec(req, "amount");
        String currency = str(line, "currency");
        if (amount == null || amount.signum() <= 0 || !Currencies.fitsMinorUnit(amount, currency)) {
            throw ApiException.unprocessable("invalid_amount", "amount must be positive");
        }
        UUID dest = uuid(req, "destination_account_id");
        if ("TRANSFER_TO_ACCOUNT".equals(type) && dest == null) {
            throw ApiException.unprocessable("destination_required", "destination_account_id is required for TRANSFER_TO_ACCOUNT");
        }
        BigDecimal rate = "CASH_ADVANCE".equals(type) && line.hasNonNull("cash_advance_rate")
                ? decOr0(line, "cash_advance_rate") : decOr0(line, "purchase_rate");
        line = refreshAvailable(id);
        ObjectNode d = rows.object();
        d.put("credit_line_id", id.toString());
        d.put("draw_type", type);
        d.put("amount", amount.toPlainString());
        d.put("currency", currency);
        d.put("rate", rate.toPlainString());
        if (dest != null) {
            d.put("destination_account_id", dest.toString());
        }
        if (req.hasNonNull("card_authorization_id")) {
            d.put("card_authorization_id", str(req, "card_authorization_id"));
        }
        d.put("idempotency_key", idempotencyKey);
        if (amount.compareTo(decOr0(line, "available_amount")) > 0) {
            d.put("status", "DECLINED");
            d.put("decline_reason", "insufficient available credit (" + str(line, "available_amount") + ")");
            return rows.insert(DRAWS, d);
        }
        UUID drawId = Ids.newId();
        UUID target = switch (type) {
            case "FEE" -> loans.gl().feeIncome();
            case "TRANSFER_TO_ACCOUNT", "CASH_ADVANCE" -> dest != null ? external.depositLedgerAccount(dest, currency, "destination_account_id")
                    : loans.gl().clearing();
            default -> loans.gl().clearing();
        };
        UUID je = loans.ledger().post(new LedgerPort.Posting(LedgerPort.key("credit-line-draw", drawId, null), "LOAN",
                type + " on credit line " + str(line, "credit_line_number"), currency, "CREDIT_LINE_DRAW", drawId, loans.today(),
                new LedgerPort.Legs().debit(uuid(line, "ledger_account_id"), amount, "Credit line receivable")
                        .credit(target, amount, type).list()));
        d.put("id", drawId.toString());
        d.put("status", "POSTED");
        d.put("journal_entry_id", je.toString());
        d.put("posted_at", loans.now().toString());
        ObjectNode draw = rows.insert(DRAWS, d);
        ObjectNode upd = rows.object();
        upd.put("utilised_amount", decOr0(line, "utilised_amount").add(amount).toPlainString());
        rows.update(TABLE, id, upd);
        refreshAvailable(id);
        loans.events().publish("lending.credit_line.drawn", DRAWS, draw);
        return draw;
    }

    @Transactional
    public ObjectNode reverseDraw(UUID drawId, String reason) {
        ObjectNode draw = rows.get(DRAWS, drawId);
        ObjectNode line = rows.lock(TABLE, uuid(draw, "credit_line_id"));
        draw = rows.lock(DRAWS, drawId);
        if (!"POSTED".equals(str(draw, "status"))) {
            throw ApiException.conflict("invalid_transition", "Only posted draws can be reversed (status " + str(draw, "status") + ")");
        }
        BigDecimal amount = decOr0(draw, "amount");
        if (decOr0(line, "utilised_amount").compareTo(amount) < 0) {
            throw ApiException.conflict("draw_already_repaid", "The drawn amount was already repaid; refund the customer instead");
        }
        loans.ledger().reverse(uuid(draw, "journal_entry_id"), reason, LedgerPort.key("credit-line-draw-reversal", drawId, null));
        ObjectNode du = rows.object();
        du.put("status", "REVERSED");
        du.put("reversal_reason", reason);
        du.put("reversed_at", loans.now().toString());
        ObjectNode reversed = rows.update(DRAWS, drawId, du);
        ObjectNode upd = rows.object();
        upd.put("utilised_amount", decOr0(line, "utilised_amount").subtract(amount).toPlainString());
        rows.update(TABLE, uuid(line, "id"), upd);
        refreshAvailable(uuid(line, "id"));
        loans.events().publish("lending.credit_line.draw_reversed", DRAWS, reversed);
        return reversed;
    }

    // ------------------------------------------------------------------ payments

    @Transactional
    public ObjectNode repay(UUID id, JsonNode req, String idempotencyKey) {
        ObjectNode line = rows.lock(TABLE, id);
        return pay(line, dec(req, "amount"), str(req, "source"), uuid(req, "source_account_id"), str(req, "external_reference"),
                idempotencyKey);
    }

    ObjectNode pay(ObjectNode line, BigDecimal amount, String source, UUID sourceAccount, String reference, String key) {
        UUID id = uuid(line, "id");
        Optional<ObjectNode> replay = rows.queryOne("select row_to_json(r)::text from loan_repayment r where idempotency_key = ?", key);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (Set.of("PENDING_ACTIVATION", "CLOSED").contains(str(line, "status"))) {
            throw ApiException.conflict("invalid_transition", "Payments are not accepted on a " + str(line, "status") + " credit line");
        }
        String currency = str(line, "currency");
        if (amount == null || amount.signum() <= 0 || !Currencies.fitsMinorUnit(amount, currency)) {
            throw ApiException.unprocessable("invalid_amount", "amount must be positive");
        }
        BigDecimal interest = decOr0(line, "outstanding_interest").max(BigDecimal.ZERO);
        BigDecimal fees = decOr0(line, "outstanding_fees");
        BigDecimal principal = decOr0(line, "utilised_amount");
        BigDecimal owed = interest.add(fees).add(principal);
        if (amount.compareTo(owed) > 0) {
            throw ApiException.unprocessable("overpayment", "The payment exceeds the revolving balance " + owed.toPlainString());
        }
        BigDecimal toInterest = amount.min(interest);
        BigDecimal toFees = amount.subtract(toInterest).min(fees);
        BigDecimal toPrincipal = amount.subtract(toInterest).subtract(toFees);
        UUID account = sourceAccount;
        if (account == null && "AUTO_DEBIT".equals(source)) {
            account = uuid(line, "repayment_account_id");
        }
        UUID funding = account != null ? external.depositLedgerAccount(account, currency, "source_account_id") : loans.gl().clearing();
        UUID repId = Ids.newId();
        UUID je = loans.ledger().post(new LedgerPort.Posting(LedgerPort.key("credit-line-payment", repId, null), "PAYMENT",
                "Payment credit line " + str(line, "credit_line_number"), currency, "CREDIT_LINE_PAYMENT", repId, loans.today(),
                new LedgerPort.Legs().debit(funding, amount, "Payment " + source)
                        .credit(loans.gl().interestReceivable(), toInterest, "Interest")
                        .credit(loans.gl().feeReceivable(), toFees, "Fees")
                        .credit(uuid(line, "ledger_account_id"), toPrincipal, "Principal").list()));
        ArrayNode detail = rows.json().createArrayNode();
        BigDecimal left = amount;
        for (ObjectNode s : rows.query("""
                select row_to_json(s)::text from credit_line_statement s where credit_line_id = ?
                  and status in ('ISSUED','PARTIALLY_PAID','OVERDUE') order by payment_due_date, cycle_end""", id)) {
            if (left.signum() <= 0) {
                break;
            }
            BigDecimal open = decOr0(s, "closing_balance").subtract(decOr0(s, "amount_paid"));
            BigDecimal x = open.min(left);
            if (x.signum() <= 0) {
                continue;
            }
            BigDecimal paid = decOr0(s, "amount_paid").add(x);
            ObjectNode su = rows.object();
            su.put("amount_paid", paid.toPlainString());
            boolean minimumMet = paid.compareTo(decOr0(s, "minimum_payment_due")) >= 0;
            su.put("status", paid.compareTo(decOr0(s, "closing_balance")) >= 0 ? "PAID"
                    : "OVERDUE".equals(str(s, "status")) && !minimumMet ? "OVERDUE" : "PARTIALLY_PAID");
            rows.update(STATEMENTS, uuid(s, "id"), su);
            detail.addObject().put("type", "STATEMENT").put("id", str(s, "id")).put("amount", x.toPlainString());
            left = left.subtract(x);
        }
        detail.addObject().put("type", "CREDIT_LINE").put("component", "INTEREST").put("amount", toInterest.toPlainString());
        detail.addObject().put("type", "CREDIT_LINE").put("component", "FEE").put("amount", toFees.toPlainString());
        detail.addObject().put("type", "CREDIT_LINE").put("component", "PRINCIPAL").put("amount", toPrincipal.toPlainString());
        ObjectNode r = rows.object();
        r.put("id", repId.toString());
        r.put("credit_line_id", id.toString());
        r.put("amount", amount.toPlainString());
        r.put("currency", currency);
        r.put("source", source);
        r.put("value_date", loans.today().toString());
        r.put("allocated_principal", toPrincipal.toPlainString());
        r.put("allocated_interest", toInterest.toPlainString());
        r.put("allocated_fees", toFees.toPlainString());
        r.put("allocated_penalties", "0");
        r.put("excess_amount", "0");
        r.set("allocation_detail", detail);
        r.put("status", "POSTED");
        if (account != null) {
            r.put("source_account_id", account.toString());
        }
        if (reference != null) {
            r.put("external_reference", reference);
        }
        r.put("journal_entry_id", je.toString());
        r.put("idempotency_key", key);
        ObjectNode payment = rows.insert(RepaymentService.TABLE, r);
        ObjectNode upd = rows.object();
        upd.put("outstanding_interest", decOr0(line, "outstanding_interest").subtract(toInterest).toPlainString());
        upd.put("outstanding_fees", fees.subtract(toFees).toPlainString());
        upd.put("utilised_amount", principal.subtract(toPrincipal).toPlainString());
        rows.update(TABLE, id, upd);
        delinquency(id, loans.today());
        refreshAvailable(id);
        loans.events().publish("lending.credit_line.payment_received", RepaymentService.TABLE, payment);
        return payment;
    }

    // ------------------------------------------------------------------ limits

    @Transactional
    public ObjectNode changeLimit(UUID id, JsonNode req) {
        ObjectNode line = rows.lock(TABLE, id);
        if (!Set.of("ACTIVE", "FROZEN", "PENDING_ACTIVATION").contains(str(line, "status"))) {
            throw ApiException.conflict("invalid_transition", "The limit of a " + str(line, "status") + " credit line cannot change");
        }
        BigDecimal newLimit = dec(req, "new_limit");
        if (newLimit == null || newLimit.signum() <= 0 || !Currencies.fitsMinorUnit(newLimit, str(line, "currency"))) {
            throw ApiException.unprocessable("invalid_limit", "new_limit must be positive");
        }
        BigDecimal old = decOr0(line, "credit_limit");
        boolean temporary = Rows.bool(req, "is_temporary");
        BigDecimal used = decOr0(line, "utilised_amount").add(decOr0(line, "instalment_balance"))
                .add(decOr0(line, "outstanding_interest")).add(decOr0(line, "outstanding_fees"));
        if (newLimit.compareTo(used) < 0) {
            throw ApiException.unprocessable("limit_below_utilisation", "The new limit is below the amount owed " + used.toPlainString());
        }
        BigDecimal threshold = old.multiply(BigDecimal.ONE.add(loans.props().limitIncreaseApprovalPct()), MC);
        if (newLimit.compareTo(threshold) > 0 && !req.hasNonNull("approval_request_id")) {
            throw ApiException.unprocessable("approval_required", "Increases above "
                    + loans.props().limitIncreaseApprovalPct().movePointRight(2).stripTrailingZeros().toPlainString()
                    + "% of the current limit need an approval_request_id");
        }
        ObjectNode upd = rows.object();
        OffsetDateTime expires = null;
        if (temporary) {
            expires = Rows.ts(req, "expires_at");
            if (expires == null) {
                throw ApiException.unprocessable("expires_at_required", "Temporary limits need expires_at");
            }
            boolean future = rows.scalar("select ?::timestamptz > now()", Boolean.class, expires).orElse(false);
            if (!future) {
                throw ApiException.unprocessable("invalid_expiry", "expires_at must be in the future");
            }
            if (newLimit.compareTo(old) <= 0) {
                throw ApiException.unprocessable("invalid_limit", "A temporary limit must be above the permanent limit");
            }
            upd.put("temporary_limit", newLimit.toPlainString());
            upd.put("temporary_limit_expires_at", expires.toString());
        } else {
            upd.put("credit_limit", newLimit.toPlainString());
        }
        rows.update(TABLE, id, upd);
        refreshAvailable(id);
        ObjectNode c = rows.object();
        c.put("credit_line_id", id.toString());
        c.put("old_limit", old.toPlainString());
        c.put("new_limit", newLimit.toPlainString());
        c.put("is_temporary", temporary);
        if (expires != null) {
            c.put("expires_at", expires.toString());
        }
        c.put("reason", str(req, "reason"));
        if (req.hasNonNull("approval_request_id")) {
            c.put("approval_request_id", str(req, "approval_request_id"));
        }
        ObjectNode change = rows.insert("credit_line_limit_change", c);
        loans.events().publish("lending.credit_line.limit_changed", "credit_line_limit_change", change);
        return change;
    }

    // ------------------------------------------------------------------ conversion

    /** Moves part of the revolving balance into an instalment loan drawn from the line (it still counts against the limit). */
    @Transactional
    public ObjectNode convert(UUID id, JsonNode req) {
        ObjectNode line = rows.lock(TABLE, id);
        if (!"ACTIVE".equals(str(line, "status"))) {
            throw ApiException.conflict("invalid_transition", "Only active credit lines can convert balances");
        }
        BigDecimal amount = dec(req, "amount");
        int term = req.path("term_months").asInt(0);
        if (amount == null || amount.signum() <= 0 || amount.compareTo(decOr0(line, "utilised_amount")) > 0
                || !Currencies.fitsMinorUnit(amount, str(line, "currency"))) {
            throw ApiException.unprocessable("invalid_amount", "amount must be positive and at most the drawn balance "
                    + str(line, "utilised_amount"));
        }
        if (term < 1 || term > 60) {
            throw ApiException.unprocessable("invalid_term", "term_months must be 1-60");
        }
        BigDecimal rate = dec(req, "interest_rate") != null ? dec(req, "interest_rate") : decOr0(line, "purchase_rate");
        LocalDate today = loans.today();
        ObjectNode v = rows.object();
        v.put("loan_number", loanService.number("LN"));
        v.put("customer_id", str(line, "customer_id"));
        v.put("product_id", str(line, "product_id"));
        v.put("product_version_id", str(line, "product_version_id"));
        v.put("legal_entity_id", str(line, "legal_entity_id"));
        v.put("loan_type", "INSTALMENT");
        v.put("currency", str(line, "currency"));
        v.put("principal_amount", amount.toPlainString());
        v.put("disbursed_amount", amount.toPlainString());
        v.put("outstanding_principal", amount.toPlainString());
        v.put("interest_rate_type", rate.signum() == 0 ? "ZERO" : "FIXED");
        v.put("interest_rate", rate.toPlainString());
        v.put("day_count", str(line, "day_count"));
        v.put("schedule_type", "ANNUITY");
        v.put("repayment_frequency", "MONTHLY");
        v.put("business_day_convention", "MODIFIED_FOLLOWING");
        v.put("term_months", term);
        v.put("start_date", today.toString());
        v.put("first_repayment_date", Frequency.MONTHLY.next(today, term).toString());
        v.put("last_accrual_date", today.minusDays(1).toString());
        v.put("credit_line_id", id.toString());
        if (line.hasNonNull("repayment_account_id")) {
            v.put("repayment_account_id", str(line, "repayment_account_id"));
            v.put("auto_debit_enabled", true);
        } else {
            v.put("auto_debit_enabled", false);
        }
        v.put("status", "ACTIVE");
        v.putObject("product_terms").put("origination_fee", "0").put("converted_from_credit_line", id.toString());
        ObjectNode loan = rows.insert(LoanSupport.LOAN, v);
        UUID receivable = loans.receivable(loan);
        loans.ledger().post(new LedgerPort.Posting(LedgerPort.key("credit-line-conversion", uuid(loan, "id"), null), "LOAN",
                "Conversion of credit line " + str(line, "credit_line_number") + " to instalments", str(line, "currency"),
                "CREDIT_LINE_CONVERSION", uuid(loan, "id"), today, new LedgerPort.Legs()
                .debit(receivable, amount, "Instalment loan").credit(uuid(line, "ledger_account_id"), amount, "Revolving balance").list()));
        ScheduleGenerator.Schedule s = loans.generate(loans.terms(loan, amount, rate, term, today,
                Rows.date(loan, "first_repayment_date"), "ANNUITY"));
        loans.persistSchedule(loan, s, "ORIGINATION", today, rate, true);
        ObjectNode upd = rows.object();
        upd.put("utilised_amount", decOr0(line, "utilised_amount").subtract(amount).toPlainString());
        upd.put("instalment_balance", decOr0(line, "instalment_balance").add(amount).toPlainString());
        rows.update(TABLE, id, upd);
        refreshAvailable(id);
        loan = loans.get(uuid(loan, "id"));
        loans.events().publish("lending.loan.created", LoanSupport.LOAN, loan);
        return loan;
    }

    // ------------------------------------------------------------------ statements and delinquency

    public List<UUID> statementCandidates() {
        return rows.query("select row_to_json(c)::text from credit_line c where status in ('ACTIVE','FROZEN','IN_ARREARS','SUSPENDED') order by id")
                .stream().map(c -> uuid(c, "id")).toList();
    }

    /** Closes the statement cycle of one line when {@code date} is its statement day; returns the statement if issued. */
    @Transactional
    public Optional<ObjectNode> statement(UUID id, LocalDate date) {
        ObjectNode line = rows.lock(TABLE, id);
        int day = line.path("statement_day").asInt(1);
        boolean cycleDay = date.getDayOfMonth() == Math.min(day, date.lengthOfMonth());
        if (!cycleDay || rows.scalar("select exists(select 1 from credit_line_statement where credit_line_id = ? and cycle_end = ?)",
                Boolean.class, id, date).orElse(false)) {
            return Optional.empty();
        }
        LocalDate last = Rows.date(line, "last_statement_date");
        LocalDate start = last != null ? last.plusDays(1) : Rows.ts(line, "opened_at").toLocalDate();
        if (start.isAfter(date)) {
            return Optional.empty();
        }
        int scale = Currencies.digits(str(line, "currency"));
        Optional<ObjectNode> previous = rows.queryOne("""
                select row_to_json(s)::text from credit_line_statement s where credit_line_id = ? order by cycle_end desc limit 1""", id);
        BigDecimal purchases = BigDecimal.ZERO;
        BigDecimal cash = BigDecimal.ZERO;
        BigDecimal feeDraws = BigDecimal.ZERO;
        List<CreditLineMath.Movement> moves = new ArrayList<>();
        BigDecimal cashInterestPremium = BigDecimal.ZERO;
        DayCount dc = DayCount.parse(str(line, "day_count"));
        BigDecimal purchaseRate = decOr0(line, "purchase_rate");
        BigDecimal cashRate = line.hasNonNull("cash_advance_rate") ? decOr0(line, "cash_advance_rate") : purchaseRate;
        BigDecimal cashInterest = BigDecimal.ZERO;
        for (ObjectNode d : rows.query("""
                select row_to_json(d)::text from credit_line_drawdown d where credit_line_id = ? and status = 'POSTED'
                  and posted_at::date between ? and ?""", id, start, date)) {
            BigDecimal amt = decOr0(d, "amount");
            LocalDate on = Rows.ts(d, "posted_at").toLocalDate();
            moves.add(new CreditLineMath.Movement(on, amt));
            switch (str(d, "draw_type")) {
                case "CASH_ADVANCE", "TRANSFER_TO_ACCOUNT" -> {
                    cash = cash.add(amt);
                    long days = ChronoUnit.DAYS.between(on, date) + 1;
                    cashInterest = cashInterest.add(CreditLineMath.interest(amt, cashRate, days, dc, date, 8));
                    if (cashRate.compareTo(purchaseRate) > 0) {
                        cashInterestPremium = cashInterestPremium.add(CreditLineMath.interest(amt, cashRate.subtract(purchaseRate), days, dc, date, 8));
                    }
                }
                case "FEE" -> feeDraws = feeDraws.add(amt);
                default -> purchases = purchases.add(amt);
            }
        }
        BigDecimal payments = BigDecimal.ZERO;
        for (ObjectNode p : rows.query("""
                select row_to_json(r)::text from loan_repayment r where credit_line_id = ? and status = 'POSTED'
                  and received_at::date between ? and ?
                  and not exists (select 1 from loan_repayment x where x.reversal_of_id = r.id)""", id, start, date)) {
            payments = payments.add(decOr0(p, "amount"));
            BigDecimal principalPaid = decOr0(p, "allocated_principal");
            if (principalPaid.signum() > 0) {
                moves.add(new CreditLineMath.Movement(Rows.ts(p, "received_at").toLocalDate(), principalPaid.negate()));
            }
        }
        BigDecimal netMoves = moves.stream().map(CreditLineMath.Movement::delta).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal openingPrincipal = decOr0(line, "utilised_amount").subtract(netMoves);
        BigDecimal adb = CreditLineMath.averageDailyBalance(openingPrincipal, moves, start, date);
        long days = ChronoUnit.DAYS.between(start, date) + 1;
        boolean interestFree = Rows.bool(line, "grace_period_interest_free")
                && (previous.isEmpty() || "PAID".equals(str(previous.get(), "status")));
        BigDecimal interest = interestFree ? cashInterest
                : CreditLineMath.interest(adb, purchaseRate, days, dc, date, 8).add(cashInterestPremium);
        interest = interest.setScale(scale, RoundingMode.HALF_EVEN);
        UUID stmtId = Ids.newId();
        if (interest.signum() > 0) {
            loans.ledger().post(new LedgerPort.Posting(LedgerPort.key("credit-line-interest", id, date.toString()), "INTEREST",
                    "Interest credit line " + str(line, "credit_line_number") + " " + start + ".." + date, str(line, "currency"),
                    "CREDIT_LINE_STATEMENT", stmtId, date, new LedgerPort.Legs()
                    .debit(loans.gl().interestReceivable(), interest, "Interest receivable")
                    .credit(loans.gl().interestIncome(), interest, "Interest income").list()));
        }
        BigDecimal outInterest = decOr0(line, "outstanding_interest").add(interest);
        BigDecimal closing = decOr0(line, "utilised_amount").add(outInterest).add(decOr0(line, "outstanding_fees"));
        BigDecimal opening = previous.map(p -> decOr0(p, "closing_balance")).orElse(BigDecimal.ZERO);
        BigDecimal minimum = CreditLineMath.minimumPayment(closing, decOr0(line, "minimum_payment_pct"),
                decOr0(line, "minimum_payment_floor"), scale);
        ObjectNode s = rows.object();
        s.put("id", stmtId.toString());
        s.put("credit_line_id", id.toString());
        s.put("cycle_start", start.toString());
        s.put("cycle_end", date.toString());
        s.put("opening_balance", opening.toPlainString());
        s.put("purchases", purchases.toPlainString());
        s.put("cash_advances", cash.toPlainString());
        s.put("interest_charged", interest.toPlainString());
        s.put("fees_charged", feeDraws.toPlainString());
        s.put("payments_received", payments.toPlainString());
        s.put("closing_balance", closing.toPlainString());
        s.put("minimum_payment_due", minimum.toPlainString());
        s.put("payment_due_date", date.plusDays(line.path("payment_due_days").asInt(21)).toString());
        s.put("status", closing.signum() <= 0 ? "PAID" : "ISSUED");
        s.put("currency", str(line, "currency"));
        ObjectNode statement = rows.insert(STATEMENTS, s);
        ObjectNode upd = rows.object();
        upd.put("outstanding_interest", outInterest.toPlainString());
        upd.put("last_statement_date", date.toString());
        rows.update(TABLE, id, upd);
        refreshAvailable(id);
        loans.events().publish("lending.credit_line.statement_issued", STATEMENTS, statement);
        return Optional.of(statement);
    }

    /**
     * Missed minimum payments: statements past their due date with less than the minimum paid become OVERDUE; DPD is
     * counted from the oldest such due date; the line moves to IN_ARREARS and back to ACTIVE when cured.
     */
    @Transactional
    public boolean delinquency(UUID id, LocalDate date) {
        ObjectNode line = rows.lock(TABLE, id);
        rows.execute("""
                update credit_line_statement set status = 'OVERDUE' where credit_line_id = ? and status in ('ISSUED','PARTIALLY_PAID')
                  and payment_due_date < ? and amount_paid < minimum_payment_due""", id, date);
        LocalDate oldest = rows.scalar("""
                select min(payment_due_date) from credit_line_statement where credit_line_id = ? and status = 'OVERDUE'
                  and amount_paid < minimum_payment_due""", LocalDate.class, id).orElse(null);
        int dpd = oldest == null ? 0 : (int) Math.max(0, ChronoUnit.DAYS.between(oldest, date));
        String bucket = Delinquency.bucket(dpd);
        String status = str(line, "status");
        String to = status;
        if (dpd > 0 && Set.of("ACTIVE", "FROZEN").contains(status)) {
            to = "IN_ARREARS";
        } else if (dpd == 0 && "IN_ARREARS".equals(status)) {
            to = "ACTIVE";
        }
        ObjectNode upd = rows.object();
        upd.put("days_past_due", dpd);
        upd.put("delinquency_bucket", bucket);
        upd.put("ecl_stage", Delinquency.eclStage(dpd, false, false));
        upd.put("status", to);
        ObjectNode updated = rows.update(TABLE, id, upd);
        if (!to.equals(status)) {
            updated = rows.update(TABLE, id, rows.object().put("status_reason", dpd > 0 ? "minimum payment missed" : "arrears cleared"));
            loans.events().publish("lending.credit_line.status_changed", TABLE, updated);
            return true;
        }
        return !bucket.equals(str(line, "delinquency_bucket"));
    }

    /** Autopay on the payment due date of the latest statement (MINIMUM, STATEMENT or FULL). Returns the amount collected. */
    @Transactional
    public BigDecimal autopay(UUID id, LocalDate date) {
        ObjectNode line = rows.lock(TABLE, id);
        String mode = str(line, "autopay_mode");
        if (mode == null || "NONE".equals(mode) || uuid(line, "repayment_account_id") == null
                || Set.of("CLOSED", "PENDING_ACTIVATION").contains(str(line, "status"))) {
            return BigDecimal.ZERO;
        }
        Optional<ObjectNode> st = rows.queryOne("""
                select row_to_json(s)::text from credit_line_statement s where credit_line_id = ? and payment_due_date = ?
                  and status in ('ISSUED','PARTIALLY_PAID','OVERDUE')""", id, date);
        if (st.isEmpty()) {
            return BigDecimal.ZERO;
        }
        ObjectNode s = st.get();
        BigDecimal owed = decOr0(line, "utilised_amount").add(decOr0(line, "outstanding_interest")).add(decOr0(line, "outstanding_fees"));
        BigDecimal want = switch (mode) {
            case "MINIMUM" -> decOr0(s, "minimum_payment_due").subtract(decOr0(s, "amount_paid"));
            case "STATEMENT" -> decOr0(s, "closing_balance").subtract(decOr0(s, "amount_paid"));
            default -> owed;
        };
        want = want.min(owed);
        if (want.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        UUID ledgerAccount = external.depositLedgerAccount(uuid(line, "repayment_account_id"), str(line, "currency"), "repayment_account_id");
        BigDecimal available = LedgerPort.available(loans.ledger().balance(ledgerAccount));
        BigDecimal collect = want.min(available).setScale(Currencies.digits(str(line, "currency")), RoundingMode.DOWN);
        if (collect.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        pay(line, collect, "AUTO_DEBIT", uuid(line, "repayment_account_id"), "AUTOPAY " + date, "cl-autopay:" + id + ":" + date);
        return collect;
    }

    public List<UUID> autopayCandidates() {
        return rows.query("select row_to_json(c)::text from credit_line c where autopay_mode in ('MINIMUM','STATEMENT','FULL') and status not in ('CLOSED','PENDING_ACTIVATION') order by id")
                .stream().map(c -> uuid(c, "id")).toList();
    }
}
