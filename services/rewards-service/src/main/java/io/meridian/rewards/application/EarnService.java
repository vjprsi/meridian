package io.meridian.rewards.application;

import static io.meridian.rewards.application.Json.text;

import io.meridian.common.error.ApiException;
import io.meridian.rewards.api.generated.model.CashbackTransaction;
import io.meridian.rewards.api.generated.model.RewardTransaction;
import io.meridian.rewards.domain.EarnEngine;
import io.meridian.rewards.infrastructure.Rows;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Earning: evaluates qualifying transactions against a program's active earn rules (exactly once per program and
 * source transaction), writes the points (pending during the rule's dispute window) and cashback awards, and
 * reverses them on refunds and chargebacks.
 */
@Service
public class EarnService {
    static final String T = "reward_transaction";

    private final Rows rows;
    private final PointsLedger ledger;
    private final AccountService accounts;
    private final CashbackService cashback;
    private final Events events;

    public EarnService(Rows rows, PointsLedger ledger, AccountService accounts, CashbackService cashback, Events events) {
        this.rows = rows;
        this.ledger = ledger;
        this.accounts = accounts;
        this.cashback = cashback;
        this.events = events;
    }

    /** A qualifying transaction as received (API body or card event). */
    public record Input(UUID customerId, String trigger, String sourceType, UUID sourceId, BigDecimal amount,
                        String currency, String mcc, String merchantId, String merchantCountry, String channel,
                        UUID productId, OffsetDateTime transactionAt, UUID creditAccountId) {

        static Input of(JsonNode b) {
            return new Input(Json.uuid(b, "customer_id"), Json.required(b, "trigger"), Json.required(b, "source_type"),
                    Json.uuid(b, "source_id"), Json.decimal(b, "amount"), Json.required(b, "currency"), text(b, "mcc"),
                    text(b, "merchant_id"), text(b, "merchant_country"), text(b, "channel"), Json.uuid(b, "product_id"),
                    Json.ts(b, "transaction_at"), Json.uuid(b, "credit_account_id"));
        }
    }

    // ------------------------------------------------------------------ evaluate one program

    @Transactional
    public ObjectNode create(JsonNode body) {
        UUID programId = Json.uuid(body, "rewards_program_id");
        Input in = Input.of(body);
        validate(in);
        Optional<ObjectNode> existing = existing(programId, in);
        if (existing.isPresent()) {
            return existing.get();
        }
        ObjectNode program = rows.queryOne("select row_to_json(t)::text from rewards_program t where t.id = ?", programId)
                .orElseThrow(() -> ApiException.unprocessable("program_not_found", "Rewards program " + programId + " does not exist"));
        String why = programUnavailable(program, in);
        if (why != null) {
            throw ApiException.unprocessable("program_not_active", why);
        }
        ObjectNode account = accountFor(program, in);
        if (account == null) {
            throw ApiException.unprocessable("not_enrolled", "The customer is not enrolled and cannot be auto-enrolled");
        }
        AccountService.requireActive(account);
        return evaluate(program, account, in);
    }

    /** Evaluates the transaction against every live program the customer is (or can be auto-) enrolled in. */
    @Transactional
    public ObjectNode ingest(JsonNode body) {
        Input in = Input.of(body);
        validate(in);
        ObjectNode out = rows.object();
        ArrayNode results = out.putArray("results");
        ArrayNode skipped = out.putArray("skipped");
        long points = 0;
        BigDecimal cash = BigDecimal.ZERO;
        for (ObjectNode program : rows.query("select row_to_json(t)::text from rewards_program t where t.status = 'ACTIVE' order by t.id")) {
            UUID programId = Json.uuid(program, "id");
            ObjectNode row = existing(programId, in).orElse(null);
            if (row == null) {
                String why = programUnavailable(program, in);
                ObjectNode account = why == null ? accountFor(program, in) : null;
                if (why == null && account == null) {
                    why = "not_enrolled";
                } else if (why == null && !"ACTIVE".equals(text(account, "status"))) {
                    why = "account_" + text(account, "status").toLowerCase();
                }
                if (why != null) {
                    if (!"not_enrolled".equals(why)) {
                        skipped.addObject().put("rewards_program_id", programId.toString()).put("reason", why);
                    }
                    continue;
                }
                row = evaluate(program, account, in);
            }
            ObjectNode r = results.addObject();
            r.put("rewards_program_id", programId.toString());
            r.put("reward_transaction_id", text(row, "id"));
            r.put("points_account_id", text(row, "points_account_id"));
            r.put("status", text(row, "status"));
            r.put("points_awarded", row.path("points_awarded").asLong());
            r.put("cashback_awarded", text(row, "cashback_awarded"));
            points += row.path("points_awarded").asLong();
            cash = cash.add(new BigDecimal(text(row, "cashback_awarded")));
        }
        out.put("points_awarded", points);
        out.put("cashback_awarded", cash.stripTrailingZeros().toPlainString());
        return out;
    }

    private static void validate(Input in) {
        Problems p = new Problems("invalid_transaction");
        p.check(in.customerId() != null, "customer_id", "required", "customer_id is required");
        p.check(in.sourceId() != null, "source_id", "required", "source_id is required");
        p.check(in.amount() != null && in.amount().signum() > 0, "amount", "must_be_positive", "amount must be > 0");
        p.check(in.mcc() == null || Problems.MCC.matcher(in.mcc()).matches(), "mcc", "invalid_mcc", "MCC codes have 4 digits");
        p.throwIfAny();
    }

    private Optional<ObjectNode> existing(UUID programId, Input in) {
        return rows.queryOne("""
                select row_to_json(t)::text from reward_transaction t
                where t.rewards_program_id = ? and t.source_type = ? and t.source_id = ?""", programId, in.sourceType(), in.sourceId());
    }

    /** Null when the program can award now, else why not. */
    private String programUnavailable(ObjectNode program, Input in) {
        if (!"ACTIVE".equals(text(program, "status"))) {
            return "The program is " + text(program, "status");
        }
        LocalDate today = rows.now().toLocalDate();
        LocalDate start = Json.date(program, "starts_on");
        LocalDate end = Json.date(program, "ends_on");
        if (start != null && today.isBefore(start) || end != null && today.isAfter(end)) {
            return "The program is outside its validity dates";
        }
        String vc = text(program, "value_currency");
        if (("CASHBACK".equals(text(program, "program_type")) || "HYBRID".equals(text(program, "program_type")))
                && vc != null && !vc.equals(in.currency())) {
            return "currency_mismatch";
        }
        return null;
    }

    /** The customer's account in the program, auto-enrolling when the program allows it; null when not enrolled. */
    private ObjectNode accountFor(ObjectNode program, Input in) {
        UUID programId = Json.uuid(program, "id");
        Optional<ObjectNode> a = rows.queryOne("""
                select row_to_json(t)::text from points_account t where t.rewards_program_id = ? and t.customer_id = ?""",
                programId, in.customerId());
        if (a.isPresent()) {
            return a.get();
        }
        if (!program.path("auto_enrol").asBoolean(false) || !AccountService.eligible(program, in.productId())) {
            return null;
        }
        return accounts.enrol(programId, in.customerId(), in.productId(), in.creditAccountId());
    }

    private ObjectNode evaluate(ObjectNode program, ObjectNode accountRow, Input in) {
        UUID programId = Json.uuid(program, "id");
        UUID accountId = Json.uuid(accountRow, "id");
        ObjectNode account = ledger.lockAccount(accountId);
        OffsetDateTime at = in.transactionAt() == null ? rows.now() : in.transactionAt();
        List<ObjectNode> ruleRows = activeRules(programId);
        List<EarnEngine.Rule> rules = ruleRows.stream().map(EarnService::rule).toList();
        EarnEngine.Txn txn = txn(in, at);
        BigDecimal tierMult = tierMultiplier(account);
        EarnEngine.Result result = EarnEngine.evaluate(text(program, "program_type"), rules, txn, tierMult,
                (rule, from, to) -> usage(in.customerId(), programId, rule.id(), from, to));

        ObjectNode t = rows.object();
        t.put("rewards_program_id", programId.toString());
        t.put("points_account_id", accountId.toString());
        t.put("customer_id", in.customerId().toString());
        t.put("trigger", in.trigger());
        t.put("source_type", in.sourceType());
        t.put("source_id", in.sourceId().toString());
        t.put("amount", in.amount().toPlainString());
        t.put("currency", in.currency());
        putIf(t, "mcc", in.mcc());
        putIf(t, "merchant_id", in.merchantId());
        putIf(t, "merchant_country", in.merchantCountry());
        putIf(t, "channel", in.channel());
        putIf(t, "product_id", in.productId());
        t.put("transaction_at", at.toString());
        UUID credit = in.creditAccountId() != null ? in.creditAccountId() : Json.uuid(account, "default_credit_account_id");
        putIf(t, "credit_account_id", credit);
        t.put("status", result.awards().isEmpty() ? "NO_AWARD" : "AWARDED");
        t.put("points_awarded", result.points());
        t.put("cashback_awarded", result.cashback().toPlainString());
        ArrayNode awards = t.putArray("awards");
        for (EarnEngine.Award a : result.awards()) {
            awards.addObject().put("reward_rule_id", a.ruleId().toString()).put("rule_name", a.ruleName())
                    .put("points", a.points()).put("cashback", a.cashback().toPlainString()).put("capped", a.capped());
        }
        ObjectNode txRow = rows.insert(T, t);
        UUID txId = Json.uuid(txRow, "id");
        OffsetDateTime now = rows.now();
        for (EarnEngine.Award a : result.awards()) {
            ObjectNode rule = ruleRows.stream().filter(r -> a.ruleId().toString().equals(text(r, "id"))).findFirst().orElseThrow();
            int settle = Json.integer(rule, "settle_after_days", 0);
            OffsetDateTime confirmAfter = settle > 0 ? at.plusDays(settle) : null;
            boolean pending = confirmAfter != null && confirmAfter.isAfter(now);
            if (a.points() > 0) {
                PointsLedger.Move m = new PointsLedger.Move("EARN", a.points(), "earn:" + txId + ":" + a.ruleId())
                        .rule(a.ruleId()).transaction(txId, in.amount(), in.currency()).source(in.sourceType(), in.sourceId())
                        .expires(PointsLedger.expiry(program, now.toLocalDate())).event("engagement.points.earned");
                if (pending) {
                    m.pending(confirmAfter);
                }
                ledger.append(account, m);
            }
            if (a.cashback().signum() > 0) {
                ObjectNode c = rows.object();
                c.put("rewards_program_id", programId.toString());
                c.put("reward_rule_id", a.ruleId().toString());
                c.put("reward_transaction_id", txId.toString());
                c.put("customer_id", in.customerId().toString());
                c.put("source_type", in.sourceType());
                c.put("source_id", in.sourceId().toString());
                c.put("transaction_amount", in.amount().toPlainString());
                c.put("cashback_amount", a.cashback().toPlainString());
                c.put("currency", in.currency());
                c.put("status", pending ? "PENDING" : "CONFIRMED");
                c.put("confirm_after", (confirmAfter == null ? now : confirmAfter).toString());
                putIf(c, "credit_account_id", credit);
                ObjectNode cb = rows.insert(CashbackService.T, c);
                events.publish("engagement.cashback.awarded", CashbackService.T, cb, CashbackTransaction.class);
                if (!pending) {
                    events.publish("engagement.cashback.confirmed", CashbackService.T, cb, CashbackTransaction.class);
                }
            }
        }
        events.publish("engagement.reward_transaction.evaluated", T, txRow, RewardTransaction.class);
        return txRow;
    }

    private EarnEngine.Used usage(UUID customerId, UUID programId, UUID ruleId, java.time.Instant from, java.time.Instant to) {
        return rows.jdbc().sql("""
                select coalesce(sum((a->>'points')::bigint), 0), coalesce(sum((a->>'cashback')::numeric), 0)
                from reward_transaction t cross join lateral jsonb_array_elements(coalesce(t.awards, '[]'::jsonb)) a
                where t.customer_id = ? and t.rewards_program_id = ? and t.status <> 'REVERSED'
                  and a->>'reward_rule_id' = ? and t.transaction_at >= ? and t.transaction_at < ?""")
                .params(customerId, programId, ruleId.toString(), Timestamp.from(from), Timestamp.from(to))
                .query((rs, i) -> new EarnEngine.Used(rs.getLong(1), rs.getBigDecimal(2))).single();
    }

    List<ObjectNode> activeRules(UUID programId) {
        return rows.query("select row_to_json(t)::text from reward_rule t where t.rewards_program_id = ? and t.status = 'ACTIVE'", programId);
    }

    BigDecimal tierMultiplier(ObjectNode account) {
        UUID tier = account == null ? null : Json.uuid(account, "reward_tier_id");
        if (tier == null) {
            return BigDecimal.ONE;
        }
        return rows.find("reward_tier", tier).map(t -> Json.decimal(t, "earn_multiplier")).orElse(BigDecimal.ONE);
    }

    static EarnEngine.Txn txn(Input in, OffsetDateTime at) {
        return new EarnEngine.Txn(in.trigger(), in.amount(), in.mcc(), in.merchantId(), in.merchantCountry(), in.channel(),
                in.productId() == null ? null : in.productId().toString(), at);
    }

    static EarnEngine.Rule rule(ObjectNode r) {
        List<Integer> days = new ArrayList<>();
        JsonNode d = r.get("valid_days_of_week");
        if (d != null && d.isArray()) {
            d.forEach(x -> days.add(x.asInt()));
        }
        String zone = text(r, "time_zone");
        return new EarnEngine.Rule(Json.uuid(r, "id"), text(r, "name"), text(r, "trigger"), r.path("priority").asInt(100),
                r.path("stackable").asBoolean(true), Json.strings(r.get("mcc_include")), Json.strings(r.get("mcc_exclude")),
                Json.strings(r.get("merchant_ids")), Json.strings(r.get("product_ids")), Json.strings(r.get("channels")),
                Json.strings(r.get("countries")), Json.decimal(r, "min_amount"), Json.decimal(r, "points_per_unit"),
                Json.decimal(r, "cashback_rate"), Json.decimal(r, "fixed_award"), Json.decimal(r, "multiplier"),
                Json.decimal(r, "cap_amount"), text(r, "cap_period"), days, text(r, "valid_time_from"),
                text(r, "valid_time_to"), ZoneId.of(zone == null ? "UTC" : zone), Json.ts(r, "valid_from"), Json.ts(r, "valid_to"));
    }

    // ------------------------------------------------------------------ simulate

    @Transactional(readOnly = true)
    public JsonNode simulate(JsonNode body) {
        UUID programId = Json.uuid(body, "rewards_program_id");
        ObjectNode program = rows.queryOne("select row_to_json(t)::text from rewards_program t where t.id = ?", programId)
                .orElseThrow(() -> ApiException.unprocessable("program_not_found", "Rewards program " + programId + " does not exist"));
        BigDecimal amount = Json.decimal(body, "amount");
        if (amount == null || amount.signum() <= 0) {
            throw ApiException.unprocessable("invalid_transaction", "amount must be > 0");
        }
        UUID customer = Json.uuid(body, "customer_id");
        ObjectNode account = customer == null ? null : rows.queryOne(
                "select row_to_json(t)::text from points_account t where t.rewards_program_id = ? and t.customer_id = ?",
                programId, customer).orElse(null);
        OffsetDateTime at = Json.ts(body, "transaction_at") == null ? rows.now() : Json.ts(body, "transaction_at");
        Input in = new Input(customer, Json.required(body, "trigger"), "SIMULATION", null, amount, Json.required(body, "currency"),
                text(body, "mcc"), text(body, "merchant_id"), text(body, "merchant_country"), text(body, "channel"),
                Json.uuid(body, "product_id"), at, null);
        BigDecimal tierMult = tierMultiplier(account);
        EarnEngine.Result result = EarnEngine.evaluate(text(program, "program_type"),
                activeRules(programId).stream().map(EarnService::rule).toList(), txn(in, at), tierMult,
                (rule, from, to) -> customer == null ? EarnEngine.Used.NONE : usage(customer, programId, rule.id(), from, to));
        ObjectNode out = rows.object();
        out.put("rewards_program_id", programId.toString());
        out.put("program_type", text(program, "program_type"));
        out.put("tier_multiplier", tierMult.toPlainString());
        out.put("points", result.points());
        out.put("cashback", result.cashback().toPlainString());
        out.put("currency", in.currency());
        ArrayNode awards = out.putArray("awards");
        result.awards().forEach(a -> awards.addObject().put("reward_rule_id", a.ruleId().toString()).put("rule_name", a.ruleName())
                .put("points", a.points()).put("cashback", a.cashback().toPlainString()).put("capped", a.capped()));
        ArrayNode evals = out.putArray("evaluations");
        result.evaluations().forEach(e -> evals.addObject().put("reward_rule_id", e.ruleId()).put("rule_name", e.ruleName())
                .put("matched", e.matched()).put("reason", e.reason()));
        return out;
    }

    // ------------------------------------------------------------------ reverse

    /** Reverses the awards of a transaction: pending points cancelled, confirmed points clawed back up to the
     *  available balance (the shortfall is recorded in the reason), cashback reversed (paid cashback in the ledger). */
    @Transactional
    public ObjectNode reverse(UUID id, String reason) {
        ObjectNode tx = rows.lock(T, id);
        if ("REVERSED".equals(text(tx, "status"))) {
            throw ApiException.conflict("invalid_transition", "The transaction is already reversed");
        }
        UUID accountId = Json.uuid(tx, "points_account_id");
        if (accountId != null) {
            ObjectNode account = ledger.lockAccount(accountId);
            for (ObjectNode e : rows.query("""
                    select row_to_json(t)::text from points_ledger_entry t
                    where t.reward_transaction_id = ? and t.entry_type = 'EARN' and t.confirms_id is null order by t.id""", id)) {
                UUID entryId = Json.uuid(e, "id");
                long points = e.path("points").asLong();
                Optional<ObjectNode> confirmation = rows.queryOne(
                        "select row_to_json(t)::text from points_ledger_entry t where t.confirms_id = ?", entryId);
                if (e.path("is_pending").asBoolean() && confirmation.isEmpty()) {
                    ledger.append(account, new PointsLedger.Move("REVERSE", -points, "reverse:" + entryId).pending(null)
                            .reverses(entryId).source(text(tx, "source_type"), Json.uuid(tx, "source_id")).reason(reason)
                            .event("engagement.points.reversed"));
                } else {
                    UUID target = confirmation.map(c -> Json.uuid(c, "id")).orElse(entryId);
                    long available = account.path("available_points").asLong();
                    long claw = Math.min(points, available);
                    String why = claw < points ? reason + " (shortfall " + (points - claw) + " points already used)" : reason;
                    if (claw > 0) {
                        ledger.append(account, new PointsLedger.Move("REVERSE", -claw, "reverse:" + target)
                                .reverses(target).source(text(tx, "source_type"), Json.uuid(tx, "source_id")).reason(why)
                                .event("engagement.points.reversed"));
                    }
                }
            }
        }
        for (ObjectNode c : rows.query("""
                select row_to_json(t)::text from cashback_transaction t where t.reward_transaction_id = ?
                  and t.status in ('PENDING', 'CONFIRMED', 'PAID') for update""", id)) {
            cashback.reverseLocked(c, reason);
        }
        ObjectNode v = rows.object();
        v.put("status", "REVERSED");
        v.put("reversed_at", rows.now().toString());
        v.put("reversal_reason", Json.truncate(reason, 255));
        ObjectNode row = rows.update(T, id, v);
        events.publish("engagement.reward_transaction.reversed", T, row, RewardTransaction.class);
        return row;
    }

    /** Reverses the transaction of a source, in every program (card reversals); returns the number reversed. */
    @Transactional
    public int reverseSource(String sourceType, UUID sourceId, String reason) {
        int n = 0;
        for (ObjectNode t : rows.query("""
                select row_to_json(t)::text from reward_transaction t where t.source_type = ? and t.source_id = ?
                  and t.status <> 'REVERSED'""", sourceType, sourceId)) {
            reverse(Json.uuid(t, "id"), reason);
            n++;
        }
        return n;
    }

    private static void putIf(ObjectNode n, String f, Object v) {
        if (v != null) {
            n.put(f, v.toString());
        }
    }

    static OffsetDateTime utc(OffsetDateTime t) {
        return t.withOffsetSameInstant(ZoneOffset.UTC);
    }
}
