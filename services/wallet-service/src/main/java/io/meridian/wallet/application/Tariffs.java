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

/** Loads fee rules and tier limits effective on the business date (database date as fallback) and measures limit usage. */
@Component
public class Tariffs {
    /** Debits that count towards limits: completed or still reserved. */
    static final String COUNTED = "('COMPLETED','PENDING_CONFIRMATION','PROCESSING','INITIATED')";

    private final Rows rows;

    public Tariffs(Rows rows) {
        this.rows = rows;
    }

    public FeeCalculator.Fee fee(String txnType, String channel, JsonNode payer, BigDecimal amount, String currency) {
        return fee(txnType, channel, payer, amount, currency, null);
    }

    /**
     * The fee for a transaction. WE-PRICE-1: {@code remoteTxnType} (onsite/offsite, PE-16) narrows the matching rules;
     * when a candidate rule is telescopic-cumulative (PE-7) or milestone-gated (PE-18) the payer's windowed counts and
     * cumulative volume are loaded and threaded into the engine. With no such rule (the seeded SP-* rules) the extra
     * queries are skipped and the result is identical to the pre-WE-PRICE-1 band charge.
     */
    public FeeCalculator.Fee fee(String txnType, String channel, JsonNode payer, BigDecimal amount, String currency,
                                 String remoteTxnType) {
        if (payer == null) {
            return FeeCalculator.Fee.NONE;
        }
        java.time.LocalDate today = rows.today();
        List<ObjectNode> found = rows.query("""
                select row_to_json(r)::text from wallet_fee_rule r
                where r.is_active and r.txn_type = ? and r.currency = ?
                  and r.valid_from <= ? and (r.valid_to is null or r.valid_to >= ?)""",
                txnType, currency, today, today);
        List<FeeCalculator.Rule> rules = found.stream().map(Tariffs::rule).toList();
        String wt = Rows.str(payer, "wallet_type");
        Integer tier = Rows.integer(payer, "kyc_tier");
        boolean needWindows = rules.stream().anyMatch(r -> r.hasMilestone() || "CUMULATIVE".equals(r.scope()));
        if (!needWindows) {
            FeeCalculator.Context ctx = new FeeCalculator.Context(txnType, channel, wt, tier, amount, remoteTxnType,
                    BigDecimal.ZERO, null, null, null, null);
            return FeeCalculator.quote(rules, ctx);
        }
        UUID walletId = Rows.uuid(payer, "id");
        Windows w = windows(walletId, txnType);
        FeeCalculator.Context ctx = new FeeCalculator.Context(txnType, channel, wt, tier, amount, remoteTxnType,
                BigDecimal.ZERO, w.daily, w.weekly, w.monthly, w.lifetime);
        return FeeCalculator.select(rules, ctx).map(r -> {
            BigDecimal before = "CUMULATIVE".equals(r.scope()) ? cumulative(walletId, txnType, r.accumulationPeriod())
                    : BigDecimal.ZERO;
            BigDecimal fee = FeeCalculator.compute(r, amount, before);
            BigDecimal tax = r.taxRate() == null ? BigDecimal.ZERO
                    : fee.multiply(r.taxRate()).setScale(2, java.math.RoundingMode.HALF_EVEN);
            return new FeeCalculator.Fee(r.id(), fee, tax.setScale(2, java.math.RoundingMode.HALF_EVEN));
        }).orElse(FeeCalculator.Fee.NONE);
    }

    /** Prior matching-transaction counts per window for the payer (this txn is the next one). */
    private record Windows(int daily, int weekly, int monthly, int lifetime) {}

    private Windows windows(UUID walletId, String txnType) {
        ObjectNode c = rows.queryOne("""
                select json_build_object(
                  'd', count(*) filter (where created_at >= date_trunc('day', now() at time zone 'UTC') at time zone 'UTC'),
                  'w', count(*) filter (where created_at >= date_trunc('week', now() at time zone 'UTC') at time zone 'UTC'),
                  'm', count(*) filter (where created_at >= date_trunc('month', now() at time zone 'UTC') at time zone 'UTC'),
                  'l', count(*))::text
                from wallet_transaction
                where payer_wallet_id = ? and txn_type = ? and status in""" + " " + COUNTED + """
                  and txn_type not in ('REVERSAL', 'REFUND')""", walletId, txnType).orElseThrow();
        return new Windows(Rows.integer(c, "d"), Rows.integer(c, "w"), Rows.integer(c, "m"), Rows.integer(c, "l"));
    }

    /** Cumulative amount already transacted by the payer for this txn type in the rule's accumulation window. */
    private BigDecimal cumulative(UUID walletId, String txnType, String period) {
        String window = switch (period == null ? "LIFETIME" : period) {
            case "DAILY" -> "and created_at >= date_trunc('day', now() at time zone 'UTC') at time zone 'UTC'";
            case "WEEKLY" -> "and created_at >= date_trunc('week', now() at time zone 'UTC') at time zone 'UTC'";
            case "MONTHLY" -> "and created_at >= date_trunc('month', now() at time zone 'UTC') at time zone 'UTC'";
            case "YEARLY" -> "and created_at >= date_trunc('year', now() at time zone 'UTC') at time zone 'UTC'";
            default -> "";
        };
        return rows.scalar("select coalesce(sum(amount), 0) from wallet_transaction where payer_wallet_id = ? "
                + "and txn_type = ? and status in " + COUNTED + " and txn_type not in ('REVERSAL', 'REFUND') " + window,
                BigDecimal.class, walletId, txnType).orElse(BigDecimal.ZERO);
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
                Rows.integer(r, "priority") == null ? 100 : Rows.integer(r, "priority"),
                Rows.str(r, "tier_mode"), Rows.str(r, "charge_scope"), Rows.str(r, "accumulation_period"),
                Rows.str(r, "charge_direction"), Rows.integer(r, "milestone_from_nth"), Rows.integer(r, "milestone_to_nth"),
                Rows.str(r, "milestone_period"), Rows.str(r, "milestone_count_scope"), Rows.str(r, "remote_txn_type"));
    }

    /** Tier limit rows for the wallet (its tier, type and currency) effective today, for a txn type or all (null). */
    public List<LimitEvaluator.Limit> limits(JsonNode wallet, String txnType) {
        java.time.LocalDate today = rows.today();
        List<ObjectNode> found = rows.query("""
                select row_to_json(l)::text from wallet_tier_limit l
                where l.kyc_tier = ? and l.wallet_type = ? and l.currency = ?
                  and l.valid_from <= ? and (l.valid_to is null or l.valid_to >= ?)
                  and (l.txn_type is null or ?::text is null or l.txn_type = ?)
                order by l.txn_type nulls first, l.valid_from desc""",
                Rows.integer(wallet, "kyc_tier"), Rows.str(wallet, "wallet_type"), Rows.str(wallet, "currency"),
                today, today, txnType, txnType);
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
