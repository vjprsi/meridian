package io.meridian.biller.application;

import io.meridian.biller.config.BillerProperties;
import io.meridian.biller.domain.Lifecycle;
import io.meridian.biller.infrastructure.Rows;
import io.meridian.biller.infrastructure.WalletClient;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Biller settlements: collected bill payments and top-ups of a period are stamped with the settlement (each paid out
 * once), approved by a checker and paid out of the biller's collection wallet to the settlement clearing account.
 */
@Service
public class SettlementService {
    static final String TABLE = "biller_settlement";

    private final Rows rows;
    private final WalletClient wallet;
    private final Events events;
    private final TransactionTemplate tx;
    private final BillerProperties props;
    private final CatalogService catalog;

    public SettlementService(Rows rows, WalletClient wallet, Events events, TransactionTemplate tx, BillerProperties props,
                             CatalogService catalog) {
        this.rows = rows;
        this.wallet = wallet;
        this.events = events;
        this.tx = tx;
        this.props = props;
        this.catalog = catalog;
    }

    @Transactional
    public ObjectNode create(JsonNode req) {
        ObjectNode v = rows.columns(TABLE, req);
        ObjectNode biller = catalog.biller(Rows.uuid(v, "biller_id"));
        LocalDate start = Rows.date(v, "period_start");
        LocalDate end = Rows.date(v, "period_end");
        if (end.isBefore(start)) {
            throw ApiException.unprocessable("invalid_period", "period_end must not be before period_start");
        }
        String currency = Rows.str(v, "currency");
        if (!currency.equals(Rows.str(biller, "currency"))) {
            throw ApiException.unprocessable("currency_mismatch", "The biller collects in " + Rows.str(biller, "currency"));
        }
        v.put("status", "PENDING");
        ObjectNode row = rows.insert(TABLE, v);
        UUID id = Rows.uuid(row, "id");
        UUID billerId = Rows.uuid(biller, "id");
        String window = " where biller_id = ? and currency = ? and status = 'COMPLETED' and biller_settlement_id is null"
                + " and (completed_at at time zone 'UTC')::date between ?::date and ?::date";
        int payments = rows.execute("update bill_payment set biller_settlement_id = ?" + window, id, billerId, currency, start, end);
        int topups = rows.execute("update airtime_topup set biller_settlement_id = ?" + window, id, billerId, currency, start, end);
        if (payments + topups == 0) {
            throw ApiException.unprocessable("nothing_to_settle", "No unsettled completed payments in the period");
        }
        ObjectNode totals = rows.queryOne("""
                select json_build_object(
                  'gross', (select coalesce(sum(amount), 0) from bill_payment where biller_settlement_id = ?)
                         + (select coalesce(sum(amount), 0) from airtime_topup where biller_settlement_id = ?),
                  'fee', (select coalesce(sum(fee_amount), 0) from bill_payment where biller_settlement_id = ?))::text""",
                id, id, id).orElseThrow();
        BigDecimal gross = Rows.dec(totals, "gross");
        BigDecimal commission = BigDecimal.ZERO; // commissions are paid by commission-service from the platform pool
        ObjectNode u = rows.object();
        u.put("payment_count", payments);
        u.put("topup_count", topups);
        u.put("gross_amount", gross.toPlainString());
        u.put("fee_amount", Rows.dec(totals, "fee").toPlainString());
        u.put("commission_amount", commission.toPlainString());
        u.put("net_amount", gross.subtract(commission).toPlainString());
        Lifecycle.settlement("PENDING", "CALCULATED");
        u.put("status", "CALCULATED");
        return rows.update(TABLE, id, u);
    }

    @Transactional
    public ObjectNode approve(UUID id) {
        ObjectNode s = rows.lock(TABLE, id);
        Lifecycle.settlement(Rows.str(s, "status"), "APPROVED");
        if (TenantContext.principal().equals(Rows.str(s, "created_by"))) {
            throw ApiException.unprocessable("four_eyes_violation", "The settlement must be approved by a different user than its maker");
        }
        ObjectNode u = rows.object();
        u.put("status", "APPROVED");
        u.put("approved_by", TenantContext.principal());
        rows.update(TABLE, id, u);
        rows.execute("update biller_settlement set approved_at = now() where id = ?", id);
        ObjectNode after = rows.get(TABLE, id);
        events.publish("wallets.biller_settlement.approved", TABLE, after);
        return after;
    }

    /** Moves the net amount out of the collection wallet to the clearing account and records the bank payment. */
    public ObjectNode markPaid(UUID id, String paymentReference) {
        ObjectNode s = rows.get(TABLE, id);
        if ("PAID".equals(Rows.str(s, "status"))) {
            return s;
        }
        Lifecycle.settlement(Rows.str(s, "status"), "PAID");
        if (props.settlementClearingAccountId() == null) {
            throw new ApiException(500, "settlement_account_not_configured",
                    "meridian.biller.settlement-clearing-account-id is not configured");
        }
        ObjectNode biller = catalog.biller(Rows.uuid(s, "biller_id"));
        if (biller.get("settlement_wallet_id") == null || biller.get("settlement_wallet_id").isNull()) {
            throw ApiException.unprocessable("biller_not_configured", "The biller has no collection wallet");
        }
        BigDecimal net = Rows.dec(s, "net_amount");
        String txnId = null;
        if (net.signum() > 0) {
            ObjectNode body = wallet.body();
            body.put("txn_type", "WALLET_TO_BANK");
            body.put("channel", "SYSTEM");
            body.put("initiator_type", "SYSTEM");
            body.put("amount", net.toPlainString());
            body.put("currency", Rows.str(s, "currency"));
            body.put("payer_wallet_id", Rows.str(biller, "settlement_wallet_id"));
            body.put("counterparty_ledger_account_id", props.settlementClearingAccountId().toString());
            body.put("biller_id", Rows.str(biller, "id"));
            body.put("payee_external_ref", paymentReference);
            body.put("description", "Biller settlement " + Rows.str(biller, "biller_code") + " " + Rows.str(s, "period_start")
                    + ".." + Rows.str(s, "period_end"));
            txnId = wallet.create(body, "bst-" + id + "-pay").get("id").asString();
        }
        String wtx = txnId;
        return tx.execute(st -> {
            ObjectNode cur = rows.lock(TABLE, id);
            if ("PAID".equals(Rows.str(cur, "status"))) {
                return cur;
            }
            ObjectNode u = rows.object();
            u.put("status", "PAID");
            u.put("payment_reference", paymentReference);
            if (wtx != null) {
                u.put("wallet_transaction_id", wtx);
            }
            rows.update(TABLE, id, u);
            rows.execute("update biller_settlement set paid_at = now() where id = ?", id);
            ObjectNode after = rows.get(TABLE, id);
            events.publish("wallets.biller_settlement.paid", TABLE, after);
            return after;
        });
    }
}
