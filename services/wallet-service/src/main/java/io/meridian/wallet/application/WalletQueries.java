package io.meridian.wallet.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.page.Cursor;
import io.meridian.wallet.domain.LimitEvaluator;
import io.meridian.wallet.infrastructure.Downstream;
import io.meridian.wallet.infrastructure.LedgerClient;
import io.meridian.wallet.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Read models: lookup, balances and statements from the ledger, wallet transaction history, limits, summaries. */
@Service
public class WalletQueries {
    private final Rows rows;
    private final LedgerClient ledger;
    private final Tariffs tariffs;
    private final Downstream downstream;

    public WalletQueries(Rows rows, LedgerClient ledger, Tariffs tariffs, Downstream downstream) {
        this.rows = rows;
        this.ledger = ledger;
        this.tariffs = tariffs;
        this.downstream = downstream;
    }

    public ObjectNode lookup(String msisdn, String alias, String walletNumber, String currency) {
        if (msisdn == null && alias == null && walletNumber == null) {
            throw ApiException.badRequest("missing_parameter", "Give msisdn, alias or wallet_number");
        }
        return rows.queryOne("""
                select row_to_json(w)::text from wallet w
                where (?::text is null or w.msisdn = ?) and (?::text is null or lower(w.alias) = lower(?))
                  and (?::text is null or w.wallet_number = ?) and (?::text is null or w.currency = ?)
                  and w.status in ('ACTIVE', 'DORMANT')
                order by (w.wallet_type = 'CUSTOMER') desc, w.is_default desc, w.created_at limit 1""",
                msisdn, msisdn, alias, alias, walletNumber, walletNumber, currency, currency)
                .orElseThrow(() -> ApiException.notFound("wallet", msisdn != null ? msisdn : alias != null ? alias : walletNumber));
    }

    public JsonNode balance(UUID walletId) {
        ObjectNode w = rows.get("wallet", walletId);
        UUID account = Rows.uuid(w, "ledger_account_id");
        if (account == null) {
            throw ApiException.unprocessable("wallet_not_activated", "The wallet has never been activated");
        }
        return ledger.balance(account);
    }

    /** Transactions where the wallet is payer or payee, newest first, keyset-paged by id. */
    public String transactionsPage(UUID walletId, OffsetDateTime from, OffsetDateTime to, String txnType, String status,
                                   Integer limit, String cursor) {
        rows.get("wallet", walletId);
        int n = Cursor.limit(limit);
        List<ObjectNode> list = rows.query("""
                select row_to_json(t)::text from wallet_transaction t
                where (t.payer_wallet_id = ? or t.payee_wallet_id = ?)
                  and (?::timestamptz is null or t.created_at >= ?::timestamptz)
                  and (?::timestamptz is null or t.created_at < ?::timestamptz)
                  and (?::text is null or t.txn_type = ?) and (?::text is null or t.status = ?)
                  and (?::uuid is null or t.id < ?::uuid)
                order by t.id desc limit ?""",
                walletId, walletId, ts(from), ts(from), ts(to), ts(to), txnType, txnType, status, status,
                cursor == null ? null : Cursor.decode(cursor), cursor == null ? null : Cursor.decode(cursor), n + 1);
        return page(list, n);
    }

    String page(List<ObjectNode> list, int n) {
        boolean more = list.size() > n;
        List<ObjectNode> data = more ? list.subList(0, n) : list;
        ObjectNode page = rows.object();
        ArrayNode arr = page.putArray("data");
        data.forEach(arr::add);
        page.put("has_more", more);
        if (more) {
            page.put("next_cursor", Cursor.encode(Rows.uuid(data.getLast(), "id")));
        } else {
            page.putNull("next_cursor");
        }
        return page.toString();
    }

    private static String ts(OffsetDateTime t) {
        return t == null ? null : t.toString();
    }

    /** Effective tier limits with today's and this month's usage and the remaining headroom. */
    public ObjectNode limits(UUID walletId, String txnType) {
        ObjectNode w = rows.get("wallet", walletId);
        ObjectNode out = rows.object();
        out.put("wallet_id", walletId.toString());
        out.put("kyc_tier", Rows.integer(w, "kyc_tier"));
        out.put("wallet_type", Rows.str(w, "wallet_type"));
        out.put("currency", Rows.str(w, "currency"));
        ArrayNode arr = out.putArray("limits");
        for (LimitEvaluator.Limit l : tariffs.limits(w, txnType)) {
            LimitEvaluator.Usage u = tariffs.usage(walletId, l.txnType());
            ObjectNode o = arr.addObject();
            o.put("wallet_tier_limit_id", l.id().toString());
            o.put("txn_type", l.txnType());
            put(o, "per_txn_min", l.perTxnMin());
            put(o, "per_txn_max", l.perTxnMax());
            put(o, "max_balance", l.maxBalance());
            put(o, "daily_amount", l.dailyAmount());
            o.put("daily_used", u.dailyAmount().toPlainString());
            put(o, "daily_remaining", LimitEvaluator.remaining(l.dailyAmount(), u.dailyAmount()));
            if (l.dailyCount() != null) {
                o.put("daily_count", l.dailyCount());
            }
            o.put("daily_count_used", u.dailyCount());
            put(o, "monthly_amount", l.monthlyAmount());
            o.put("monthly_used", u.monthlyAmount().toPlainString());
            put(o, "monthly_remaining", LimitEvaluator.remaining(l.monthlyAmount(), u.monthlyAmount()));
            if (l.monthlyCount() != null) {
                o.put("monthly_count", l.monthlyCount());
            }
            o.put("monthly_count_used", u.monthlyCount());
        }
        return out;
    }

    private static void put(ObjectNode o, String f, BigDecimal v) {
        if (v == null) {
            o.putNull(f);
        } else {
            o.put(f, v.toPlainString());
        }
    }

    /** Statement from the ledger: opening and closing balance and the entries of [from, to] (UTC dates). */
    public ObjectNode statement(UUID walletId, LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw ApiException.unprocessable("invalid_period", "from and to are required and to must not be before from");
        }
        if (from.plusDays(366).isBefore(to)) {
            throw ApiException.unprocessable("invalid_period", "A statement covers at most one year");
        }
        ObjectNode w = rows.get("wallet", walletId);
        UUID account = Rows.uuid(w, "ledger_account_id");
        if (account == null) {
            throw ApiException.unprocessable("wallet_not_activated", "The wallet has never been activated");
        }
        OffsetDateTime start = from.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime end = to.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        BigDecimal opening = ledger.balanceAt(account, start.minusNanos(1000));
        List<JsonNode> entries = new ArrayList<>(ledger.entries(account, start, end, 10_000));
        java.util.Collections.reverse(entries); // oldest first
        BigDecimal credits = BigDecimal.ZERO;
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal running = opening;
        ObjectNode out = rows.object();
        out.put("wallet_id", walletId.toString());
        out.put("wallet_number", Rows.str(w, "wallet_number"));
        out.put("currency", Rows.str(w, "currency"));
        out.put("from", from.toString());
        out.put("to", to.toString());
        out.put("opening_balance", opening.toPlainString());
        ArrayNode arr = out.putArray("entries");
        for (JsonNode e : entries) {
            BigDecimal amt = LedgerClient.dec(e, "amount");
            boolean credit = "CREDIT".equals(e.path("direction").asString());
            if (credit) {
                credits = credits.add(amt);
                running = running.add(amt);
            } else {
                debits = debits.add(amt);
                running = running.subtract(amt);
            }
            ObjectNode o = arr.addObject();
            o.put("ledger_entry_id", e.path("id").asString(null));
            o.put("journal_entry_id", e.path("journal_entry_id").asString(null));
            o.put("booked_at", e.path("effective_at").asString(e.path("created_at").asString(null)));
            o.put("direction", credit ? "CREDIT" : "DEBIT");
            o.put("amount", amt.toPlainString());
            o.put("description", e.path("description").asString(null));
            o.put("reference", e.path("reference").asString(null));
            o.put("balance_after", e.hasNonNull("balance_after") ? e.get("balance_after").asString() : running.toPlainString());
        }
        out.put("total_credits", credits.toPlainString());
        out.put("total_debits", debits.toPlainString());
        out.put("closing_balance", running.toPlainString());
        out.put("entry_count", entries.size());
        return out;
    }

    /** Full statement request: summary delivered to the wallet's mobile number through notification-service. */
    public ObjectNode requestStatement(UUID walletId, LocalDate from, LocalDate to, String format, String key) {
        ObjectNode st = statement(walletId, from, to);
        ObjectNode w = rows.get("wallet", walletId);
        if (Rows.str(w, "msisdn") == null) {
            throw ApiException.unprocessable("no_delivery_address", "The wallet has no mobile number to deliver the statement to");
        }
        String fmt = format == null ? "PDF" : format.toUpperCase();
        if (!java.util.Set.of("PDF", "CSV", "TEXT").contains(fmt)) {
            throw ApiException.unprocessable("invalid_format", "format must be PDF, CSV or TEXT");
        }
        String body = "Statement " + Rows.str(w, "wallet_number") + " " + from + " to " + to + ": opening "
                + st.get("opening_balance").asString() + ", in " + st.get("total_credits").asString() + ", out "
                + st.get("total_debits").asString() + ", closing " + st.get("closing_balance").asString() + " "
                + Rows.str(w, "currency") + " (" + st.get("entry_count").asInt() + " entries).";
        UUID notification = downstream.sendSms("wst-" + key, "CUSTOMER", Rows.uuid(w, "customer_id"), Rows.str(w, "msisdn"),
                "SERVICE", body);
        ObjectNode out = rows.object();
        out.put("wallet_id", walletId.toString());
        out.put("notification_id", notification == null ? null : notification.toString());
        out.put("entry_count", st.get("entry_count").asInt());
        out.put("from_date", from.toString());
        out.put("to_date", to.toString());
        out.put("format", fmt);
        out.put("closing_balance", st.get("closing_balance").asString());
        return out;
    }

    /** Counts and sums grouped by type, status and currency (from/to apply to completion time, else creation time). */
    public ObjectNode summary(String txnType, String status, UUID merchantId, UUID agentId, UUID outletId, UUID billerId,
                              UUID walletId, OffsetDateTime from, OffsetDateTime to) {
        ObjectNode out = rows.queryOne("""
                select json_build_object('groups', coalesce(json_agg(g order by g.txn_type, g.status, g.currency), '[]'::json))::text
                from (
                  select txn_type, status, currency, count(*) as count, sum(amount)::text as amount,
                         sum(fee_amount)::text as fee_amount, sum(tax_amount)::text as tax_amount,
                         count(distinct payer_wallet_id) as distinct_payers
                  from wallet_transaction t
                  where (?::text is null or t.txn_type = ?) and (?::text is null or t.status = ?)
                    and (?::uuid is null or t.merchant_id = ?) and (?::uuid is null or t.agent_id = ?)
                    and (?::uuid is null or t.agent_outlet_id = ?) and (?::uuid is null or t.biller_id = ?)
                    and (?::uuid is null or t.payer_wallet_id = ? or t.payee_wallet_id = ?)
                    and (?::timestamptz is null or coalesce(t.completed_at, t.created_at) >= ?::timestamptz)
                    and (?::timestamptz is null or coalesce(t.completed_at, t.created_at) < ?::timestamptz)
                  group by txn_type, status, currency) g""",
                txnType, txnType, status, status, merchantId, merchantId, agentId, agentId, outletId, outletId,
                billerId, billerId, walletId, walletId, walletId, ts(from), ts(from), ts(to), ts(to)).orElseThrow();
        if (from != null) {
            out.put("from", from.toString());
        }
        if (to != null) {
            out.put("to", to.toString());
        }
        return out;
    }

    /** Printable receipt. */
    public String receipt(UUID id) {
        ObjectNode t = rows.get("wallet_transaction", id);
        StringBuilder sb = new StringBuilder();
        sb.append("MERIDIAN WALLET RECEIPT\n");
        sb.append("Reference:   ").append(Rows.str(t, "reference")).append('\n');
        sb.append("Type:        ").append(Rows.str(t, "txn_type")).append('\n');
        sb.append("Status:      ").append(Rows.str(t, "status")).append('\n');
        sb.append("Date:        ").append(t.hasNonNull("completed_at") ? Rows.str(t, "completed_at") : Rows.str(t, "created_at")).append('\n');
        if (t.hasNonNull("payer_wallet_id")) {
            rows.find("wallet", Rows.uuid(t, "payer_wallet_id")).ifPresent(w ->
                    sb.append("From:        ").append(Rows.str(w, "display_name")).append(" (").append(Rows.str(w, "wallet_number")).append(")\n"));
        }
        if (t.hasNonNull("payee_wallet_id")) {
            rows.find("wallet", Rows.uuid(t, "payee_wallet_id")).ifPresent(w ->
                    sb.append("To:          ").append(Rows.str(w, "display_name")).append(" (").append(Rows.str(w, "wallet_number")).append(")\n"));
        } else if (t.hasNonNull("payee_external_ref")) {
            sb.append("To:          ").append(Rows.str(t, "payee_external_ref")).append('\n');
        }
        String ccy = Rows.str(t, "currency");
        sb.append("Amount:      ").append(Rows.str(t, "amount")).append(' ').append(ccy).append('\n');
        sb.append("Fee:         ").append(Rows.str(t, "fee_amount")).append(' ').append(ccy).append('\n');
        sb.append("Tax:         ").append(Rows.str(t, "tax_amount")).append(' ').append(ccy).append('\n');
        sb.append("Total:       ").append(Rows.str(t, "total_debit_amount")).append(' ').append(ccy).append('\n');
        if (t.hasNonNull("description")) {
            sb.append("Description: ").append(Rows.str(t, "description")).append('\n');
        }
        return sb.toString();
    }
}
