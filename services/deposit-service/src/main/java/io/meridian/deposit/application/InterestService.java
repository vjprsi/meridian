package io.meridian.deposit.application;

import static io.meridian.deposit.infrastructure.Rows.date;
import static io.meridian.deposit.infrastructure.Rows.dec;
import static io.meridian.deposit.infrastructure.Rows.decOr0;
import static io.meridian.deposit.infrastructure.Rows.str;
import static io.meridian.deposit.infrastructure.Rows.ts;
import static io.meridian.deposit.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.deposit.config.DepositProperties;
import io.meridian.deposit.domain.Currencies;
import io.meridian.deposit.domain.DayCount;
import io.meridian.deposit.domain.InterestCalculator;
import io.meridian.deposit.domain.InterestCalculator.RateConfig;
import io.meridian.deposit.domain.Schedule;
import io.meridian.deposit.infrastructure.Rows;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Daily interest accrual, capitalisation (ledger posting), back-valued recalculation and projection.
 *
 * <p>Accrual rows are append-only: a recalculation never edits a row, it appends a correcting row that
 * {@code supersedes} the old one. The "effective" accrual for a day and kind is the row nobody supersedes.
 * Balances come from ledger-service: the booked balance at the start of the window plus every entry booked since,
 * bucketed by its value (effective) date, so back-valued postings move interest from their value date.
 */
@Service
public class InterestService {
    static final Set<String> CREDIT_KINDS = Set.of("CREDIT", "BONUS");
    static final Set<String> DEBIT_KINDS = Set.of("DEBIT", "OVERDRAFT", "UNARRANGED_OVERDRAFT", "PENALTY");
    private static final Set<String> ACCRUING = Set.of("ACTIVE", "BLOCKED_DEBIT", "BLOCKED_CREDIT", "BLOCKED", "FROZEN",
            "INACTIVE", "DORMANT", "CLOSING");
    private static final int MAX_CATCH_UP_DAYS = 400;

    private final Rows rows;
    private final AccountSupport accounts;
    private final LedgerPort ledger;
    private final Events events;
    private final DepositProperties props;

    public InterestService(Rows rows, AccountSupport accounts, LedgerPort ledger, Events events, DepositProperties props) {
        this.rows = rows;
        this.accounts = accounts;
        this.ledger = ledger;
        this.events = events;
        this.props = props;
    }

    /** One computed accrual line before persistence. */
    record Line(String kind, BigDecimal basis, BigDecimal rate, DayCount dayCount, BigDecimal amount) {}

    // ------------------------------------------------------------------ rates

    Optional<RateConfig> rate(JsonNode acc, String rateKind, LocalDate day) {
        Optional<RateConfig> base = ProductSnapshot.of(acc).rate(rateKind);
        if (rateKind.startsWith("OVERDRAFT") || rateKind.startsWith("UNARRANGED")) {
            Optional<ObjectNode> facility = rows.queryOne("""
                    select row_to_json(f)::text from overdraft_facility f
                    where deposit_account_id = ? and status = 'ACTIVE' and interest_rate is not null
                      and starts_on <= ? and (expires_on is null or expires_on >= ?)
                    order by created_at desc limit 1""", uuid(acc, "id"), day, day);
            if (facility.isPresent() && rateKind.equals("OVERDRAFT_INTEREST")) {
                DayCount dc = base.map(RateConfig::dayCount).orElse(DayCount.ACT_365);
                base = Optional.of(RateConfig.fixed(rateKind, dec(facility.get(), "interest_rate"), dc));
            }
        }
        if (base.isEmpty()) {
            return base;
        }
        String shortKind = rateKind.replace("_INTEREST", "");
        Optional<ObjectNode> override = rows.queryOne("""
                select row_to_json(o)::text from account_rate_override o
                where deposit_account_id = ? and rate_kind in (?, ?) and valid_from <= ? and (valid_to is null or valid_to >= ?)
                order by valid_from desc, created_at desc limit 1""", uuid(acc, "id"), rateKind, shortKind, day, day);
        RateConfig cfg = base.get();
        return Optional.of(override.map(o -> cfg.withOverride(dec(o, "override_rate"), dec(o, "rate_adjustment"))).orElse(cfg));
    }

    /** Interest lines for one day's end-of-day balance (credit, arranged and unarranged overdraft). */
    List<Line> compute(JsonNode acc, LocalDate day, BigDecimal balance) {
        List<Line> lines = new ArrayList<>();
        if (balance.signum() >= 0) {
            rate(acc, "CREDIT_INTEREST", day).ifPresent(cfg -> lines.add(line("CREDIT", cfg, balance, day)));
            return lines;
        }
        BigDecimal overdrawn = balance.negate();
        BigDecimal limit = decOr0(acc, "overdraft_limit");
        BigDecimal arranged = overdrawn.min(limit);
        BigDecimal excess = overdrawn.subtract(arranged);
        Optional<RateConfig> od = rate(acc, "OVERDRAFT_INTEREST", day);
        if (arranged.signum() > 0) {
            od.ifPresent(cfg -> lines.add(line("OVERDRAFT", cfg, arranged, day)));
        }
        if (excess.signum() > 0) {
            Optional<RateConfig> un = rate(acc, "UNARRANGED_OVERDRAFT_INTEREST", day).or(() -> od);
            un.ifPresent(cfg -> lines.add(line("UNARRANGED_OVERDRAFT", cfg, excess, day)));
        }
        return lines;
    }

    private static Line line(String kind, RateConfig cfg, BigDecimal basis, LocalDate day) {
        return new Line(kind, basis, InterestCalculator.annualRate(cfg, basis), cfg.dayCount(),
                InterestCalculator.dailyInterest(cfg, basis, day));
    }

    // ------------------------------------------------------------------ balances from the ledger

    /** End-of-day balances for each day in [from, to], value-dated (see class comment). */
    Map<LocalDate, BigDecimal> dailyBalances(UUID ledgerAccount, LocalDate from, LocalDate to) {
        OffsetDateTime start = from.atStartOfDay().atOffset(ZoneOffset.UTC);
        BigDecimal opening = ledger.balanceAt(ledgerAccount, start.minusNanos(1000));
        TreeMap<LocalDate, BigDecimal> deltas = new TreeMap<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode page = ledger.entries(ledgerAccount, start, null, 200, cursor);
            for (JsonNode e : page.path("data")) {
                BigDecimal amt = new BigDecimal(e.path("amount").asString("0"));
                BigDecimal signed = "CREDIT".equals(e.path("direction").asString()) ? amt : amt.negate();
                OffsetDateTime eff = e.hasNonNull("effective_at") ? OffsetDateTime.parse(e.get("effective_at").asString())
                        : OffsetDateTime.parse(e.get("created_at").asString());
                LocalDate bucket = eff.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
                if (bucket.isBefore(from)) {
                    bucket = from;
                }
                deltas.merge(bucket, signed, BigDecimal::add);
            }
            cursor = page.path("has_more").asBoolean(false) ? page.path("next_cursor").asString(null) : null;
        } while (cursor != null && ++pages < 1000);
        Map<LocalDate, BigDecimal> out = new LinkedHashMap<>();
        BigDecimal running = opening;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            BigDecimal delta = deltas.get(d);
            if (delta != null) {
                running = running.add(delta);
            }
            out.put(d, running);
        }
        return out;
    }

    // ------------------------------------------------------------------ accrual

    /** Accrues every not-yet-accrued day of an account up to and including {@code through}. Returns rows added. */
    @Transactional
    public int accrueThrough(UUID accountId, LocalDate through) {
        ObjectNode acc = accounts.lock(accountId);
        return accrueThrough(acc, through);
    }

    int accrueThrough(ObjectNode acc, LocalDate through) {
        if (!ACCRUING.contains(str(acc, "status")) || acc.path("ledger_account_id").isNull()
                || !acc.has("ledger_account_id")) {
            return 0;
        }
        UUID id = uuid(acc, "id");
        LocalDate opened = ts(acc, "opened_at") == null ? null : ts(acc, "opened_at").toLocalDate();
        if (opened == null) {
            return 0;
        }
        LocalDate last = rows.scalar("select max(accrual_date) from interest_accrual where deposit_account_id = ?",
                LocalDate.class, id).orElse(null);
        LocalDate lastCap = lastCapitalisationEnd(id, null);
        LocalDate from = last != null ? last.plusDays(1) : opened;
        if (lastCap != null && !from.isAfter(lastCap)) {
            from = lastCap.plusDays(1);
        }
        if (from.isAfter(through)) {
            return 0;
        }
        if (ChronoUnit.DAYS.between(from, through) > MAX_CATCH_UP_DAYS) {
            from = through.minusDays(MAX_CATCH_UP_DAYS);
        }
        Map<LocalDate, BigDecimal> balances = dailyBalances(uuid(acc, "ledger_account_id"), from, through);
        int added = 0;
        Map<String, BigDecimal> cumulative = cumulativeSince(id, lastCap);
        for (Map.Entry<LocalDate, BigDecimal> day : balances.entrySet()) {
            for (Line l : compute(acc, day.getKey(), day.getValue())) {
                BigDecimal cum = cumulative.merge(l.kind(), l.amount(), BigDecimal::add);
                insertAccrual(acc, day.getKey(), l, cum, false, null);
                added++;
            }
        }
        refreshAccruedTotals(id, lastCapitalisationEnd(id, CREDIT_KINDS), lastCapitalisationEnd(id, DEBIT_KINDS));
        return added;
    }

    private ObjectNode insertAccrual(JsonNode acc, LocalDate day, Line l, BigDecimal cumulative, boolean recalc,
                                     UUID supersedes) {
        ObjectNode r = rows.object();
        r.put("deposit_account_id", str(acc, "id"));
        r.put("accrual_date", day.toString());
        r.put("kind", l.kind());
        r.put("balance_basis", l.basis().toPlainString());
        r.put("annual_rate", l.rate().setScale(10, java.math.RoundingMode.HALF_EVEN).toPlainString());
        r.put("day_count", l.dayCount().name());
        r.put("days", 1);
        r.put("amount", l.amount().toPlainString());
        r.put("cumulative_amount", cumulative.toPlainString());
        r.put("currency", str(acc, "currency"));
        r.put("is_backdated_recalc", recalc);
        if (supersedes != null) {
            r.put("supersedes_id", supersedes.toString());
        }
        return rows.insert("interest_accrual", r);
    }

    /** Effective (non-superseded) accrual rows of an account after a date. */
    List<ObjectNode> effectiveAccruals(UUID accountId, LocalDate after) {
        return rows.query("""
                select row_to_json(a)::text from interest_accrual a
                where a.deposit_account_id = ? and a.accrual_date > coalesce(?::date, '-infinity'::date)
                  and not exists (select 1 from interest_accrual s where s.supersedes_id = a.id)
                order by a.accrual_date, a.kind""", accountId, after);
    }

    private Map<String, BigDecimal> cumulativeSince(UUID accountId, LocalDate lastCap) {
        Map<String, BigDecimal> m = new HashMap<>();
        for (ObjectNode a : effectiveAccruals(accountId, lastCap)) {
            m.merge(str(a, "kind"), decOr0(a, "amount"), BigDecimal::add);
        }
        return m;
    }

    LocalDate lastCapitalisationEnd(UUID accountId, Set<String> kinds) {
        String kindFilter = kinds == null ? "" : kinds == CREDIT_KINDS ? " and kind in ('CREDIT','BONUS')"
                : " and kind in ('DEBIT','OVERDRAFT','UNARRANGED_OVERDRAFT','PENALTY')";
        return rows.scalar("select max(period_end) from interest_capitalisation where deposit_account_id = ?" + kindFilter,
                LocalDate.class, accountId).orElse(null);
    }

    private void refreshAccruedTotals(UUID accountId, LocalDate lastCreditCap, LocalDate lastDebitCap) {
        BigDecimal credit = BigDecimal.ZERO;
        for (ObjectNode a : effectiveAccruals(accountId, lastCreditCap)) {
            if (CREDIT_KINDS.contains(str(a, "kind"))) {
                credit = credit.add(decOr0(a, "amount"));
            }
        }
        BigDecimal debit = BigDecimal.ZERO;
        for (ObjectNode a : effectiveAccruals(accountId, lastDebitCap)) {
            if (DEBIT_KINDS.contains(str(a, "kind"))) {
                debit = debit.add(decOr0(a, "amount"));
            }
        }
        ObjectNode v = rows.object();
        v.put("accrued_credit_interest", credit.toPlainString());
        v.put("accrued_debit_interest", debit.toPlainString());
        rows.update(AccountSupport.TABLE, accountId, v);
    }

    // ------------------------------------------------------------------ recalculation

    @Transactional
    public ObjectNode recalculate(UUID accountId, LocalDate fromDate, String reason) {
        ObjectNode acc = accounts.lock(accountId);
        UUID ledgerAccount = accounts.requireLedger(acc);
        accounts.requireNotClosed(acc);
        LocalDate yesterday = accounts.today().minusDays(1);
        if (fromDate.isAfter(yesterday)) {
            throw ApiException.unprocessable("invalid_from_date", "from_date must be before today");
        }
        LocalDate opened = ts(acc, "opened_at") == null ? null : ts(acc, "opened_at").toLocalDate();
        if (opened == null) {
            throw ApiException.conflict("account_not_open", "Account " + accountId + " was never activated");
        }
        LocalDate from = fromDate.isBefore(opened) ? opened : fromDate;
        LocalDate lastCap = lastCapitalisationEnd(accountId, null);
        if (lastCap != null && !from.isAfter(lastCap)) {
            throw ApiException.unprocessable("period_already_capitalised",
                    "Interest up to " + lastCap + " was already capitalised; recalculate from " + lastCap.plusDays(1)
                            + " or later and post an adjustment for earlier periods");
        }
        Map<LocalDate, BigDecimal> balances = dailyBalances(ledgerAccount, from, yesterday);
        Map<String, ObjectNode> existing = new HashMap<>();
        for (ObjectNode a : effectiveAccruals(accountId, from.minusDays(1))) {
            existing.put(date(a, "accrual_date") + "|" + str(a, "kind"), a);
        }
        Map<String, BigDecimal> cumulative = new HashMap<>();
        for (ObjectNode a : effectiveAccruals(accountId, lastCap)) {
            if (date(a, "accrual_date").isBefore(from)) {
                cumulative.merge(str(a, "kind"), decOr0(a, "amount"), BigDecimal::add);
            }
        }
        for (Map.Entry<LocalDate, BigDecimal> day : balances.entrySet()) {
            LocalDate d = day.getKey();
            List<Line> lines = compute(acc, d, day.getValue());
            Set<String> produced = new java.util.HashSet<>();
            for (Line l : lines) {
                produced.add(l.kind());
                ObjectNode old = existing.get(d + "|" + l.kind());
                BigDecimal cum = cumulative.merge(l.kind(), l.amount(), BigDecimal::add);
                boolean same = old != null && decOr0(old, "amount").compareTo(l.amount()) == 0
                        && decOr0(old, "balance_basis").compareTo(l.basis()) == 0;
                if (!same) {
                    insertAccrual(acc, d, l, cum, true, old == null ? null : uuid(old, "id"));
                }
            }
            for (String kind : List.of("CREDIT", "OVERDRAFT", "UNARRANGED_OVERDRAFT")) {
                ObjectNode old = existing.get(d + "|" + kind);
                if (!produced.contains(kind) && old != null && decOr0(old, "amount").signum() != 0) {
                    BigDecimal cum = cumulative.getOrDefault(kind, BigDecimal.ZERO);
                    insertAccrual(acc, d, new Line(kind, BigDecimal.ZERO, decOr0(old, "annual_rate"),
                            DayCount.parse(str(old, "day_count")), BigDecimal.ZERO.setScale(8)), cum, true, uuid(old, "id"));
                }
            }
        }
        refreshAccruedTotals(accountId, lastCapitalisationEnd(accountId, CREDIT_KINDS),
                lastCapitalisationEnd(accountId, DEBIT_KINDS));
        accounts.history(accountId, str(acc, "status"), str(acc, "status"), "INTEREST_RECALCULATED",
                "Interest recalculated from " + from + ": " + reason);
        return rows.get(AccountSupport.TABLE, accountId);
    }

    // ------------------------------------------------------------------ capitalisation

    /** Result of a capitalisation run. */
    public record Capitalised(ObjectNode account, List<ObjectNode> capitalisations) {}

    /**
     * Posts accrued interest to the account. Credit interest: debit interest expense GL (gross), credit customer
     * (net), credit tax payable (withholding). Debit/overdraft interest: debit customer, credit interest income GL.
     * Each period posts with a deterministic ledger key per account and period end, so it can never post twice.
     */
    @Transactional
    public Capitalised capitalise(UUID accountId, boolean requireSomething) {
        return capitalise(accountId, requireSomething, accounts.today());
    }

    /**
     * Capitalisation as of business date {@code today}: interest accrued through {@code today - 1} is posted and the
     * next capitalisation date is scheduled from {@code today} (the end-of-day job passes business date + 1).
     */
    @Transactional
    public Capitalised capitalise(UUID accountId, boolean requireSomething, LocalDate today) {
        ObjectNode acc = accounts.lock(accountId);
        UUID ledgerAccount = accounts.requireLedger(acc);
        String status = str(acc, "status");
        if (!ACCRUING.contains(status)) {
            throw ApiException.conflict("invalid_transition", "Cannot capitalise interest on an account in status " + status);
        }
        accrueThrough(acc, today.minusDays(1));
        boolean newYear = rows.scalar("""
                select max(created_at)::date from interest_capitalisation where deposit_account_id = ? and kind = 'CREDIT'""",
                LocalDate.class, accountId).map(d -> d.getYear() < today.getYear()).orElse(true);
        String ccy = str(acc, "currency");
        List<ObjectNode> out = new ArrayList<>();
        BigDecimal netCredit = BigDecimal.ZERO;
        BigDecimal taxTotal = BigDecimal.ZERO;

        LocalDate creditCap = lastCapitalisationEnd(accountId, CREDIT_KINDS);
        List<ObjectNode> credit = effectiveAccruals(accountId, creditCap).stream()
                .filter(a -> CREDIT_KINDS.contains(str(a, "kind"))).toList();
        if (!credit.isEmpty()) {
            BigDecimal gross = Currencies.round(sum(credit), ccy);
            if (gross.signum() > 0) {
                BigDecimal rate = ProductSnapshot.of(acc).withholdingTaxRate().orElse(props.withholdingTaxRate());
                BigDecimal tax = Currencies.round(gross.multiply(rate, MathContext.DECIMAL128), ccy);
                BigDecimal net = gross.subtract(tax);
                LocalDate start = date(credit.getFirst(), "accrual_date");
                LocalDate end = date(credit.getLast(), "accrual_date");
                UUID je = ledger.post(new LedgerPort.Posting(
                        LedgerPort.key("capitalise-credit", accountId, end.toString()), "INTEREST",
                        "Credit interest " + start + " to " + end, ccy, "INTEREST_CAPITALISATION", accountId, null,
                        List.of(LedgerPort.Leg.debit(props.gl().interestExpense(), gross, "Interest expense"),
                                LedgerPort.Leg.credit(ledgerAccount, net, "Credit interest"),
                                LedgerPort.Leg.credit(props.gl().taxPayable(), tax, "Withholding tax"))));
                ObjectNode cap = capitalisation(accountId, "CREDIT", start, end, gross, tax, net, ccy,
                        tax.signum() > 0 ? "WITHHOLDING_TAX" : null, je);
                events.publish("accounts.interest.capitalised", "interest_capitalisation", cap);
                out.add(cap);
                netCredit = net;
                taxTotal = tax;
            }
        }
        LocalDate debitCap = lastCapitalisationEnd(accountId, DEBIT_KINDS);
        List<ObjectNode> debit = effectiveAccruals(accountId, debitCap).stream()
                .filter(a -> DEBIT_KINDS.contains(str(a, "kind"))).toList();
        if (!debit.isEmpty()) {
            BigDecimal gross = Currencies.round(sum(debit), ccy);
            if (gross.signum() > 0) {
                LocalDate start = date(debit.getFirst(), "accrual_date");
                LocalDate end = date(debit.getLast(), "accrual_date");
                UUID je = ledger.post(new LedgerPort.Posting(
                        LedgerPort.key("capitalise-debit", accountId, end.toString()), "INTEREST",
                        "Debit interest " + start + " to " + end, ccy, "INTEREST_CAPITALISATION", accountId, null,
                        List.of(LedgerPort.Leg.debit(ledgerAccount, gross, "Overdraft interest"),
                                LedgerPort.Leg.credit(props.gl().interestIncome(), gross, "Interest income"))));
                ObjectNode cap = capitalisation(accountId, "DEBIT", start, end, gross, BigDecimal.ZERO, gross, ccy, null, je);
                events.publish("accounts.interest.capitalised", "interest_capitalisation", cap);
                out.add(cap);
            }
        }
        if (out.isEmpty() && requireSomething) {
            throw ApiException.unprocessable("nothing_to_capitalise", "Account " + accountId
                    + " has no accrued interest of at least one minor currency unit to capitalise");
        }
        ObjectNode v = rows.object();
        if (!out.isEmpty()) {
            BigDecimal paid = newYear ? BigDecimal.ZERO : decOr0(acc, "interest_paid_ytd");
            BigDecimal withheld = newYear ? BigDecimal.ZERO : decOr0(acc, "tax_withheld_ytd");
            if (netCredit.signum() > 0) {
                v.put("interest_paid_ytd", paid.add(netCredit).add(taxTotal).toPlainString());
                v.put("tax_withheld_ytd", withheld.add(taxTotal).toPlainString());
            }
        }
        v.put("next_capitalisation_date", Schedule.advance(today, ProductSnapshot.of(acc).capitalisationFrequency()).toString());
        rows.update(AccountSupport.TABLE, accountId, v);
        refreshAccruedTotals(accountId, lastCapitalisationEnd(accountId, CREDIT_KINDS),
                lastCapitalisationEnd(accountId, DEBIT_KINDS));
        return new Capitalised(rows.get(AccountSupport.TABLE, accountId), out);
    }

    private ObjectNode capitalisation(UUID accountId, String kind, LocalDate start, LocalDate end, BigDecimal gross,
                                      BigDecimal tax, BigDecimal net, String ccy, String taxRule, UUID je) {
        ObjectNode c = rows.object();
        c.put("deposit_account_id", accountId.toString());
        c.put("kind", kind);
        c.put("period_start", start.toString());
        c.put("period_end", end.toString());
        c.put("gross_amount", gross.toPlainString());
        c.put("tax_amount", tax.toPlainString());
        c.put("net_amount", net.toPlainString());
        c.put("currency", ccy);
        if (taxRule != null) {
            c.put("tax_rule_code", taxRule);
        }
        c.put("journal_entry_id", je.toString());
        return rows.insert("interest_capitalisation", c);
    }

    private static BigDecimal sum(List<ObjectNode> accruals) {
        BigDecimal s = BigDecimal.ZERO;
        for (ObjectNode a : accruals) {
            s = s.add(decOr0(a, "amount"));
        }
        return s;
    }

    // ------------------------------------------------------------------ accrual history

    private static final Set<String> ACCRUAL_KINDS = Set.of("CREDIT", "DEBIT", "OVERDRAFT", "UNARRANGED_OVERDRAFT",
            "PENALTY", "BONUS");

    /** Accruals of an account with accrual_date in [from, to] (both optional), newest first, keyset paged. */
    public ObjectNode accruals(UUID accountId, LocalDate from, LocalDate to, String kind, Integer limit, String cursor) {
        accounts.get(accountId);
        if (from != null && to != null && to.isBefore(from)) {
            throw ApiException.badRequest("invalid_range", "to must not be before from");
        }
        if (kind != null && !ACCRUAL_KINDS.contains(kind)) {
            throw ApiException.badRequest("invalid_kind", "kind must be an InterestAccrualKind");
        }
        int n = io.meridian.common.page.Cursor.limit(limit);
        UUID after = cursor == null || cursor.isBlank() ? null : io.meridian.common.page.Cursor.decode(cursor);
        List<ObjectNode> data = rows.query("""
                select row_to_json(a)::text from interest_accrual a where deposit_account_id = ?
                  and (?::date is null or accrual_date >= ?::date) and (?::date is null or accrual_date <= ?::date)
                  and (?::varchar is null or kind = ?::varchar) and (?::uuid is null or id < ?::uuid)
                order by id desc limit ?""", accountId, from, from, to, to, kind, kind, after, after, n + 1);
        boolean more = data.size() > n;
        List<ObjectNode> page = more ? data.subList(0, n) : data;
        ObjectNode out = rows.object();
        ArrayNode arr = out.putArray("data");
        page.forEach(arr::add);
        out.put("has_more", more);
        if (more) {
            out.put("next_cursor", io.meridian.common.page.Cursor.encode(uuid(page.getLast(), "id")));
        } else {
            out.putNull("next_cursor");
        }
        return out;
    }

    // ------------------------------------------------------------------ projection

    public ObjectNode projection(UUID accountId, int days) {
        if (days < 1 || days > 1830) {
            throw ApiException.badRequest("invalid_days", "days must be between 1 and 1830");
        }
        ObjectNode acc = accounts.get(accountId);
        UUID ledgerAccount = accounts.requireLedger(acc);
        BigDecimal balance = LedgerPort.posted(ledger.balance(ledgerAccount));
        String ccy = str(acc, "currency");
        LocalDate today = accounts.today();
        BigDecimal credit = BigDecimal.ZERO;
        BigDecimal debit = BigDecimal.ZERO;
        Map<String, BigDecimal> byMonth = new TreeMap<>();
        BigDecimal annualRate = null;
        String dayCount = null;
        for (int i = 0; i < days; i++) {
            LocalDate d = today.plusDays(i);
            for (Line l : compute(acc, d, balance)) {
                if (annualRate == null) {
                    annualRate = l.rate();
                    dayCount = l.dayCount().name();
                }
                if (CREDIT_KINDS.contains(l.kind())) {
                    credit = credit.add(l.amount());
                } else {
                    debit = debit.add(l.amount());
                }
                byMonth.merge(d.toString().substring(0, 7), CREDIT_KINDS.contains(l.kind()) ? l.amount() : l.amount().negate(),
                        BigDecimal::add);
            }
        }
        BigDecimal taxRate = ProductSnapshot.of(acc).withholdingTaxRate().orElse(props.withholdingTaxRate());
        BigDecimal creditRounded = Currencies.round(credit, ccy);
        BigDecimal tax = Currencies.round(creditRounded.multiply(taxRate, MathContext.DECIMAL128), ccy);
        ObjectNode r = rows.object();
        r.put("deposit_account_id", accountId.toString());
        r.put("currency", ccy);
        r.put("as_of", today.toString());
        r.put("days", days);
        r.put("balance", balance.toPlainString());
        r.put("annual_rate", annualRate == null ? "0" : annualRate.stripTrailingZeros().toPlainString());
        r.put("day_count", dayCount == null ? DayCount.ACT_365.name() : dayCount);
        r.put("accrued_to_date", decOr0(acc, "accrued_credit_interest").subtract(decOr0(acc, "accrued_debit_interest"))
                .toPlainString());
        r.put("projected_credit_interest", creditRounded.toPlainString());
        r.put("projected_debit_interest", Currencies.round(debit, ccy).toPlainString());
        r.put("projected_tax", tax.toPlainString());
        r.put("projected_net_interest", creditRounded.subtract(tax).subtract(Currencies.round(debit, ccy)).toPlainString());
        ArrayNode months = r.putArray("by_month");
        byMonth.forEach((m, amt) -> months.addObject().put("month", m).put("amount", Currencies.round(amt, ccy).toPlainString()));
        return r;
    }
}
