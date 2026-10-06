package io.meridian.wallet.application;

import io.meridian.wallet.domain.FeeCalculator;
import io.meridian.wallet.domain.LimitEvaluator;
import io.meridian.wallet.infrastructure.Rows;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Loads fee rules and tier limits effective today (database clock) and measures limit usage. */
@Component
public class Tariffs {
    /** Debits that count towards limits: completed or still reserved. */
    static final String COUNTED = "('COMPLETED','PENDING_CONFIRMATION','PROCESSING','INITIATED')";

    private final Rows rows;

    public Tariffs(Rows rows) {
        this.rows = rows;
    }

    public FeeCalculator.Fee fee(String txnType, String channel, JsonNode payer, BigDecimal amount, String currency) {
        if (payer == null) {
            return FeeCalculator.Fee.NONE;
        }
        List<ObjectNode> found = rows.query("""
                select row_to_json(r)::text from wallet_fee_rule r
                where r.is_active and r.txn_type = ? and r.currency = ?
                  and r.valid_from <= current_date and (r.valid_to is null or r.valid_to >= current_date)""",
                txnType, currency);
        List<FeeCalculator.Rule> rules = found.stream().map(Tariffs::rule).toList();
        return FeeCalculator.quote(rules, new FeeCalculator.Context(txnType, channel, Rows.str(payer, "wallet_type"),
                Rows.integer(payer, "kyc_tier"), amount));
    }

    public static FeeCalculator.Rule rule(JsonNode r) {
        List<FeeCalculator.Tier> tiers = new ArrayList<>();
        JsonNode t = r.get("tiers");
        if (t != null && t.isArray()) {
            for (JsonNode x : t) {
                tiers.add(new FeeCalculator.Tier(Rows.dec(x, "from"), Rows.dec(x, "to"), Rows.dec(x, "flat"),
                        Rows.dec(x, "percentage")));
            }
        }
        return new FeeCalculator.Rule(Rows.uuid(r, "id"), Rows.str(r, "code") == null ? "" : Rows.str(r, "code"),
                Rows.str(r, "txn_type"), Rows.str(r, "channel"), Rows.str(r, "wallet_type"), Rows.integer(r, "kyc_tier"),
                Rows.dec(r, "min_amount"), Rows.dec(r, "max_amount"),
                Rows.str(r, "basis") == null ? "FLAT" : Rows.str(r, "basis"), Rows.dec(r, "flat_fee"),
                Rows.dec(r, "percentage"), tiers, Rows.dec(r, "min_fee"), Rows.dec(r, "max_fee"), Rows.dec(r, "tax_rate"),
                Rows.integer(r, "priority") == null ? 100 : Rows.integer(r, "priority"));
    }

    /** Tier limit rows for the wallet (its tier, type and currency) effective today, for a txn type or all (null). */
    public List<LimitEvaluator.Limit> limits(JsonNode wallet, String txnType) {
        List<ObjectNode> found = rows.query("""
                select row_to_json(l)::text from wallet_tier_limit l
                where l.kyc_tier = ? and l.wallet_type = ? and l.currency = ?
                  and l.valid_from <= current_date and (l.valid_to is null or l.valid_to >= current_date)
                  and (l.txn_type is null or ?::text is null or l.txn_type = ?)
                order by l.txn_type nulls first, l.valid_from desc""",
                Rows.integer(wallet, "kyc_tier"), Rows.str(wallet, "wallet_type"), Rows.str(wallet, "currency"),
                txnType, txnType);
        // keep the newest version per txn_type
        List<LimitEvaluator.Limit> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (ObjectNode l : found) {
            String type = Rows.str(l, "txn_type");
            if (seen.add(type == null ? "*" : type)) {
                out.add(new LimitEvaluator.Limit(Rows.uuid(l, "id"), type, Rows.dec(l, "per_txn_min"),
                        Rows.dec(l, "per_txn_max"), Rows.dec(l, "max_balance"), Rows.dec(l, "daily_amount"),
                        Rows.integer(l, "daily_count"), Rows.dec(l, "monthly_amount"), Rows.integer(l, "monthly_count")));
            }
        }
        return out;
    }

    /** Debits of the wallet so far today and this month (UTC, database clock), for one txn type or all (null). */
    public LimitEvaluator.Usage usage(UUID walletId, String txnType) {
        ObjectNode u = rows.queryOne("""
                select json_build_object(
                  'd_amt', coalesce(sum(amount) filter (where created_at >= date_trunc('day', now() at time zone 'UTC') at time zone 'UTC'), 0),
                  'd_cnt', count(*) filter (where created_at >= date_trunc('day', now() at time zone 'UTC') at time zone 'UTC'),
                  'm_amt', coalesce(sum(amount), 0),
                  'm_cnt', count(*))::text
                from wallet_transaction
                where payer_wallet_id = ? and status in""" + " " + COUNTED + """
                  and txn_type not in ('REVERSAL', 'REFUND')
                  and (?::text is null or txn_type = ?)
                  and created_at >= date_trunc('month', now() at time zone 'UTC') at time zone 'UTC'""",
                walletId, txnType, txnType).orElseThrow();
        return new LimitEvaluator.Usage(Rows.dec(u, "d_amt"), Rows.integer(u, "d_cnt"), Rows.dec(u, "m_amt"),
                Rows.integer(u, "m_cnt"));
    }
}
