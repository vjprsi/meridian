package io.meridian.merchant.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.merchant.config.MerchantProperties;
import io.meridian.merchant.domain.MdrCalculator;
import io.meridian.merchant.infrastructure.Rows;
import io.meridian.merchant.infrastructure.WalletClient;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Merchant settlement: CALCULATED from the merchant's completed payments and refunds of a period, APPROVED by a
 * checker, then PAID: the MDR fee moves from the collection wallet to fee income and the net goes to the settlement
 * bank account (on-us deposit account) or stays in the wallet when no bank account is set.
 */
@Service
public class SettlementService {
    static final String TABLE = "merchant_settlement";

    private final Rows rows;
    private final WalletClient wallets;
    private final Events events;
    private final TransactionTemplate tx;
    private final MerchantProperties props;

    public SettlementService(Rows rows, WalletClient wallets, Events events, TransactionTemplate tx,
                             MerchantProperties props) {
        this.rows = rows;
        this.wallets = wallets;
        this.events = events;
        this.tx = tx;
        this.props = props;
    }

    /** Completed MERCHANT_PAYMENT transactions of the merchant completed within [start, end] (UTC dates). */
    List<JsonNode> payments(UUID merchantId, String currency, LocalDate start, LocalDate end) {
        OffsetDateTime from = start.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime to = end.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode t : wallets.all(merchantId, "MERCHANT_PAYMENT", "COMPLETED", from.toString(), to.toString())) {
            String completed = t.hasNonNull("completed_at") ? t.get("completed_at").asString() : t.path("created_at").asString();
            OffsetDateTime c = OffsetDateTime.parse(completed);
            if (currency.equals(t.path("currency").asString()) && !c.isBefore(from) && c.isBefore(to)) {
                out.add(t);
            }
        }
        return out;
    }

    public ObjectNode create(JsonNode req) {
        ObjectNode v = rows.columns(TABLE, req);
        UUID merchantId = Rows.uuid(v, "merchant_id");
        ObjectNode m = rows.find("merchant", merchantId).orElseThrow(() ->
                ApiException.unprocessable("merchant_not_found", "The merchant does not exist"));
        String currency = Rows.str(v, "currency");
        LocalDate start = Rows.date(v, "period_start");
        LocalDate end = Rows.date(v, "period_end");
        if (end.isBefore(start)) {
            throw ApiException.unprocessable("invalid_period", "period_end must not be before period_start");
        }
        if (!currency.equals(Rows.str(m, "currency"))) {
            throw ApiException.unprocessable("currency_mismatch", "The merchant collects in " + Rows.str(m, "currency"));
        }
        if (!m.hasNonNull("settlement_wallet_id")) {
            throw ApiException.unprocessable("merchant_not_active", "The merchant has never been activated");
        }
        if (rows.scalar("select ?::date > current_date", Boolean.class, end).orElse(false)) {
            throw ApiException.unprocessable("period_in_future", "A settlement period cannot end in the future");
        }
        List<BigDecimal> amounts = payments(merchantId, currency, start, end).stream()
                .map(t -> new BigDecimal(t.get("amount").asString())).toList();
        return tx.execute(s -> {
            rows.lock("merchant", merchantId);
            if (rows.scalar("""
                    select count(*) from merchant_settlement where merchant_id = ? and currency = ? and status <> 'FAILED'
                      and period_start <= ? and period_end >= ?""", Long.class, merchantId, currency, end, start)
                    .orElse(0L) > 0) {
                throw ApiException.conflict("settlement_overlap", "Another settlement already covers part of this period");
            }
            ObjectNode r = rows.queryOne("""
                    select json_build_object('total', coalesce(sum(amount), 0), 'count', count(*))::text from merchant_refund
                    where merchant_id = ? and currency = ? and status = 'COMPLETED'
                      and completed_at >= ?::date and completed_at < (?::date + 1)""",
                    merchantId, currency, start, end).orElseThrow();
            BigDecimal refunds = Rows.decOr0(r, "total");
            if (amounts.isEmpty() && refunds.signum() == 0) {
                throw ApiException.unprocessable("nothing_to_settle", "No payments were collected in the period");
            }
            MdrCalculator.Plan plan = plan(m);
            BigDecimal refundFees = plan.refundFee() == null || props.feeIncomeAccountId() == null ? BigDecimal.ZERO
                    : plan.refundFee().multiply(BigDecimal.valueOf(Rows.integer(r, "count")));
            MdrCalculator.Totals t = MdrCalculator.settle(plan, amounts, refunds, refundFees);
            v.put("payment_count", t.count());
            v.put("gross_amount", t.gross().toPlainString());
            v.put("mdr_amount", t.mdr().toPlainString());
            v.put("refund_amount", t.refunds().toPlainString());
            v.put("net_amount", t.net().toPlainString());
            v.put("status", "CALCULATED");
            v.put("settlement_wallet_id", Rows.str(m, "settlement_wallet_id"));
            if (m.hasNonNull("settlement_account_id")) {
                v.put("settlement_account_id", Rows.str(m, "settlement_account_id"));
            }
            return rows.insert(TABLE, v);
        });
    }

    MdrCalculator.Plan plan(ObjectNode m) {
        if (!m.hasNonNull("merchant_fee_plan_id")) {
            return MdrCalculator.Plan.NONE;
        }
        ObjectNode p = rows.get("merchant_fee_plan", Rows.uuid(m, "merchant_fee_plan_id"));
        return new MdrCalculator.Plan(Rows.dec(p, "mdr_percentage"), Rows.dec(p, "fixed_fee"), Rows.dec(p, "min_fee"),
                Rows.dec(p, "max_fee"), Rows.dec(p, "refund_fee"), Rows.dec(p, "settlement_fee"));
    }

    public ObjectNode approve(UUID id, String comment) {
        return tx.execute(s -> {
            ObjectNode st = rows.lock(TABLE, id);
            if (!"CALCULATED".equals(Rows.str(st, "status"))) {
                throw ApiException.conflict("invalid_transition", "The settlement is " + Rows.str(st, "status"));
            }
            if (TenantContext.principal().equals(Rows.str(st, "created_by"))) {
                throw ApiException.unprocessable("four_eyes_violation",
                        "A settlement must be approved by a different user than the one who calculated it");
            }
            ObjectNode v = rows.object();
            v.put("status", "APPROVED");
            v.put("approved_by", TenantContext.principal());
            rows.update(TABLE, id, v);
            rows.execute("update merchant_settlement set approved_at = now() where id = ?", id);
            ObjectNode after = rows.get(TABLE, id);
            events.publish("wallets.merchant_settlement.approved", TABLE, after);
            return after;
        });
    }

    /** Pays an APPROVED settlement (a FAILED one may be retried; wallet keys are deterministic). */
    public ObjectNode pay(UUID id) {
        ObjectNode st = rows.get(TABLE, id);
        if (!Set.of("APPROVED", "FAILED").contains(Rows.str(st, "status")) || !st.hasNonNull("approved_by")) {
            throw ApiException.conflict("invalid_transition", "Only an approved settlement can be paid (status "
                    + Rows.str(st, "status") + ")");
        }
        String currency = Rows.str(st, "currency");
        UUID wallet = Rows.uuid(st, "settlement_wallet_id");
        BigDecimal mdr = Rows.dec(st, "mdr_amount");
        BigDecimal net = Rows.dec(st, "net_amount");
        String feeTxn = null;
        String netTxn = null;
        try {
            if (mdr.signum() > 0) {
                if (props.feeIncomeAccountId() == null) {
                    throw new ApiException(500, "ledger_account_not_configured",
                            "meridian.merchant.fee-income-account-id is not configured");
                }
                ObjectNode fee = wallets.txn("FEE", "SYSTEM", "SYSTEM", mdr, currency);
                fee.put("payer_wallet_id", wallet.toString());
                fee.put("counterparty_ledger_account_id", props.feeIncomeAccountId().toString());
                fee.put("merchant_id", Rows.str(st, "merchant_id"));
                fee.put("description", "MDR fees for settlement " + id);
                feeTxn = wallets.transact(fee, "mse-" + id + "-fee").get("id").asString();
            }
            UUID account = Rows.uuid(st, "settlement_account_id");
            if (account != null && net.signum() > 0) {
                UUID ledger = wallets.depositLedgerAccount(account, currency);
                ObjectNode out = wallets.txn("WALLET_TO_BANK", "SYSTEM", "SYSTEM", net, currency);
                out.put("payer_wallet_id", wallet.toString());
                out.put("counterparty_ledger_account_id", ledger.toString());
                out.put("merchant_id", Rows.str(st, "merchant_id"));
                out.put("payee_external_ref", account.toString());
                out.put("description", "Merchant settlement " + Rows.str(st, "period_start") + " to " + Rows.str(st, "period_end"));
                netTxn = wallets.transact(out, "mse-" + id + "-net").get("id").asString();
            }
        } catch (ApiException e) {
            if (e.status() >= 500 && !"ledger_account_not_configured".equals(e.code())) {
                throw e;
            }
            String fee = feeTxn;
            tx.executeWithoutResult(s -> {
                rows.lock(TABLE, id);
                ObjectNode v = rows.object();
                v.put("status", "FAILED");
                String msg = e.code() + ": " + e.getMessage();
                v.put("failure_reason", msg.length() > 255 ? msg.substring(0, 255) : msg);
                if (fee != null) {
                    v.put("fee_wallet_transaction_id", fee);
                }
                rows.update(TABLE, id, v);
            });
            throw new ApiException(422, e.code(), "Settlement payment failed: " + e.getMessage());
        }
        String fee = feeTxn;
        String nt = netTxn;
        return tx.execute(s -> {
            rows.lock(TABLE, id);
            ObjectNode v = rows.object();
            v.put("status", "PAID");
            v.putNull("failure_reason");
            if (fee != null) {
                v.put("fee_wallet_transaction_id", fee);
            }
            if (nt != null) {
                v.put("wallet_transaction_id", nt);
            }
            rows.update(TABLE, id, v);
            rows.execute("update merchant_settlement set paid_at = now() where id = ?", id);
            ObjectNode after = rows.get(TABLE, id);
            events.publish("wallets.merchant_settlement.paid", TABLE, after);
            return after;
        });
    }

    /** CSV report: summary block, then one line per payment and per refund of the period. */
    public String report(UUID id) {
        ObjectNode st = rows.get(TABLE, id);
        ObjectNode m = rows.get("merchant", Rows.uuid(st, "merchant_id"));
        MdrCalculator.Plan plan = plan(m);
        StringBuilder sb = new StringBuilder();
        sb.append("settlement_id,merchant_code,merchant_name,period_start,period_end,currency,status,payment_count,")
                .append("gross_amount,mdr_amount,refund_amount,net_amount\n");
        sb.append(String.join(",", id.toString(), csv(Rows.str(m, "merchant_code")), csv(Rows.str(m, "trading_name")),
                Rows.str(st, "period_start"), Rows.str(st, "period_end"), Rows.str(st, "currency"), Rows.str(st, "status"),
                Rows.str(st, "payment_count"), Rows.str(st, "gross_amount"), Rows.str(st, "mdr_amount"),
                Rows.str(st, "refund_amount"), Rows.str(st, "net_amount"))).append('\n');
        sb.append('\n').append("line_type,reference,completed_at,amount,mdr_fee\n");
        for (JsonNode t : payments(Rows.uuid(m, "id"), Rows.str(st, "currency"), Rows.date(st, "period_start"),
                Rows.date(st, "period_end"))) {
            BigDecimal amt = new BigDecimal(t.get("amount").asString());
            sb.append(String.join(",", "PAYMENT", csv(t.path("reference").asString("")),
                    t.path("completed_at").asString(""), amt.toPlainString(),
                    MdrCalculator.fee(plan, amt).toPlainString())).append('\n');
        }
        for (ObjectNode r : rows.query("""
                select row_to_json(r)::text from merchant_refund r where merchant_id = ? and currency = ? and status = 'COMPLETED'
                  and completed_at >= ?::date and completed_at < (?::date + 1) order by completed_at""",
                Rows.uuid(m, "id"), Rows.str(st, "currency"), Rows.date(st, "period_start"), Rows.date(st, "period_end"))) {
            sb.append(String.join(",", "REFUND", csv(Rows.str(r, "id")), Rows.str(r, "completed_at"),
                    "-" + Rows.str(r, "amount"), "")).append('\n');
        }
        return sb.toString();
    }

    /** RFC 4180 quoting and spreadsheet formula neutralisation. */
    static String csv(String s) {
        if (s == null) {
            return "";
        }
        String v = s.matches("^[=+\\-@].*") ? "'" + s : s;
        return v.contains(",") || v.contains("\"") || v.contains("\n") ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }
}
