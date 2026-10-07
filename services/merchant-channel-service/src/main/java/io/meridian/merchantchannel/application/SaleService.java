package io.meridian.merchantchannel.application;

import static io.meridian.merchantchannel.application.CurrentUser.merchant;
import static io.meridian.merchantchannel.application.CurrentUser.userId;

import io.meridian.common.error.ApiException;
import io.meridian.events.OutboxWriter;
import io.meridian.merchantchannel.application.Downstream.Query;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Sales started in the app. A sale is one dynamic EMVCo QR code issued by merchant-service for exactly the amount, in
 * the merchant's currency, for a store (and terminal) in the cashier's scope; the body sent downstream is built here
 * (merchant, currency and QR type never come from the client). Reading a PENDING sale refreshes it from its QR: paid
 * (scan_count &gt; 0: the payment is looked up by its QR and recorded), expired, or still pending. Cancelling deactivates
 * the QR; a sale paid meanwhile stays PAID.
 */
@Service
public class SaleService {
    static final String TABLE = "merchant_sale";
    static final String MERCHANTS = Access.MERCHANTS;
    static final Set<String> SELLERS = Set.of("OWNER", "MANAGER", "CASHIER");

    private final Access access;
    private final Rows rows;
    private final Downstream down;
    private final ActionAudit audit;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;

    public SaleService(Access access, Rows rows, Downstream down, ActionAudit audit, OutboxWriter outbox,
                       TransactionTemplate tx) {
        this.access = access;
        this.rows = rows;
        this.down = down;
        this.audit = audit;
        this.outbox = outbox;
        this.tx = tx;
    }

    public JsonNode create(JsonNode req, String key) {
        String action = "merchantAppSalesCreate";
        UUID storeId = Req.uuid(req, "store_id", true);
        JsonNode store = access.store(storeId, action);
        access.requireRole(action, "SALE", null, SELLERS.toArray(String[]::new));
        if (!"ACTIVE".equals(store.path("status").asString("ACTIVE"))) {
            throw ApiException.unprocessable("store_not_active", "The store is " + store.path("status").asString());
        }
        UUID terminalId = Req.uuid(req, "terminal_id", false);
        if (terminalId != null) {
            JsonNode t = access.terminal(terminalId, action);
            if (!storeId.toString().equals(t.path("merchant_store_id").asString(null))) {
                throw ApiException.unprocessable("terminal_store_mismatch", "The terminal belongs to another store");
            }
            if (!"ACTIVE".equals(t.path("status").asString(""))) {
                throw ApiException.unprocessable("terminal_not_active", "The terminal is " + t.path("status").asString());
            }
        }
        BigDecimal amount = Req.amount(req, "amount");
        String reference = Req.text(req, "reference");
        if (reference != null && (reference.isBlank() || reference.length() > 25)) {
            throw ApiException.unprocessable("invalid_reference", "reference has 1 to 25 characters");
        }
        JsonNode merchant = access.merchantRecord();
        if (!"ACTIVE".equals(merchant.path("status").asString(""))) {
            throw ApiException.unprocessable("merchant_not_active", "The merchant is " + merchant.path("status").asString());
        }
        String currency = merchant.path("currency").asString();
        ObjectNode body = rows.object()
                .put("merchant_id", merchant().toString())
                .put("merchant_store_id", storeId.toString())
                .put("qr_type", "DYNAMIC")
                .put("amount", amount.toPlainString())
                .put("currency", currency);
        if (terminalId != null) {
            body.put("merchant_terminal_id", terminalId.toString());
        }
        if (reference != null) {
            body.put("reference", reference.trim());
        }
        JsonNode qr;
        try {
            qr = down.post(MERCHANTS, "/v1/merchant-qr-codes", body, key, "sale-qr");
        } catch (ApiException e) {
            audit.failed(action, "SALE", null, e.code());
            throw e;
        }
        ObjectNode sale = tx.execute(s -> {
            ObjectNode v = rows.object()
                    .put("merchant_id", merchant().toString())
                    .put("merchant_store_id", storeId.toString())
                    .put("merchant_terminal_id", terminalId == null ? null : terminalId.toString())
                    .put("cashier_user_id", userId().toString())
                    .put("amount", amount.toPlainString())
                    .put("currency", currency)
                    .put("reference", reference == null ? null : reference.trim())
                    .put("merchant_qr_id", qr.path("id").asString())
                    .put("qr_payload", qr.path("payload").asString())
                    .put("status", "PENDING")
                    .put("expires_at", qr.path("expires_at").asString(null));
            ObjectNode r = rows.insert(TABLE, v);
            outbox.publish("channels.merchant_sale.created", TABLE, UUID.fromString(r.path("id").asString()), r);
            return r;
        });
        audit.succeeded(action, "SALE", null, sale.path("id").asString());
        return sale;
    }

    public JsonNode list(UUID storeId, String status, OffsetDateTime from, OffsetDateTime to, Integer limit,
                         String cursor) {
        int n = Rows.limit(limit);
        UUID after = Rows.cursor(cursor);
        if (storeId != null) {
            access.store(storeId, "merchantAppSalesList");
        }
        Set<UUID> scope = access.scopeStoreIds();
        String stores = scope == null ? null : Rows.uuidArray(scope);
        return rows.page(rows.list("select row_to_json(t)::text from merchant_sale t where t.merchant_id = ? "
                        + "and (?::uuid is null or t.merchant_store_id = ?::uuid) "
                        + "and (?::uuid[] is null or t.merchant_store_id = any(?::uuid[])) "
                        + "and (?::text is null or t.status = ?::text) "
                        + "and (?::timestamptz is null or t.created_at >= ?::timestamptz) "
                        + "and (?::timestamptz is null or t.created_at < ?::timestamptz) "
                        + "and (?::uuid is null or t.id < ?::uuid) order by t.id desc limit ?",
                merchant(), storeId, storeId, stores, stores, status, status, ts(from), ts(from), ts(to), ts(to),
                after, after, n + 1), n);
    }

    public JsonNode get(UUID id) {
        ObjectNode sale = sale(id, "merchantAppSalesGet");
        return "PENDING".equals(sale.path("status").asString()) ? refresh(sale) : sale;
    }

    public JsonNode cancel(UUID id, String key) {
        String action = "merchantAppSalesCancel";
        ObjectNode sale = sale(id, action);
        access.requireRole(action, "SALE", id, SELLERS.toArray(String[]::new));
        if (!"PENDING".equals(sale.path("status").asString())) {
            return sale;
        }
        ObjectNode current = refresh(sale);
        if (!"PENDING".equals(current.path("status").asString())) {
            return current;
        }
        UUID qrId = Req.rowUuid(sale, "merchant_qr_id");
        JsonNode qr = down.post(MERCHANTS, "/v1/merchant-qr-codes/" + qrId + "/deactivate", null, key, "sale-cancel");
        if (qr.path("scan_count").asInt(0) > 0) {
            return paid(id, qr);                                // paid just before the deactivation
        }
        ObjectNode r = tx.execute(s -> {
            ObjectNode locked = rows.lock(TABLE, merchant(), id).orElseThrow(() -> Access.notFound("SALE", id));
            if (!"PENDING".equals(locked.path("status").asString())) {
                return locked;
            }
            return rows.update(TABLE, id, rows.object().put("status", "CANCELLED")
                    .put("cancelled_by", userId().toString()));
        });
        audit.succeeded(action, "SALE", id, null);
        return r;
    }

    /** A sale of the merchant in a store of the user's scope; else 404 (audited). */
    ObjectNode sale(UUID id, String action) {
        ObjectNode sale = rows.find(TABLE, merchant(), id).orElseThrow(() -> access.deny(action, "SALE", id));
        access.requireScope(Req.rowUuid(sale, "merchant_store_id"), action, "SALE", id);
        return sale;
    }

    /** The sale's QR from merchant-service: paid, expired or still pending. */
    ObjectNode refresh(ObjectNode sale) {
        UUID id = UUID.fromString(sale.path("id").asString());
        UUID qrId = Req.rowUuid(sale, "merchant_qr_id");
        if (qrId == null) {
            return sale;
        }
        JsonNode qr = down.get(MERCHANTS, "/v1/merchant-qr-codes/" + qrId);
        if (!Access.ofMerchant(qr)) {
            return sale;
        }
        if (qr.path("scan_count").asInt(0) > 0) {
            return paid(id, qr);
        }
        boolean expired = rows.scalar("select ?::timestamptz <= now()", Boolean.class,
                qr.path("expires_at").asString(sale.path("expires_at").asString(null))).orElse(false);
        if (expired || !qr.path("is_active").asBoolean(true)) {
            return tx.execute(s -> {
                ObjectNode locked = rows.lock(TABLE, merchant(), id).orElseThrow(() -> Access.notFound("SALE", id));
                return "PENDING".equals(locked.path("status").asString())
                        ? rows.update(TABLE, id, rows.object().put("status", "EXPIRED")) : locked;
            });
        }
        return sale;
    }

    /** Records the payment of a sale (once): the wallet transaction paying its QR, when it can be found. */
    private ObjectNode paid(UUID id, JsonNode qr) {
        String qrId = qr.path("id").asString();
        Optional<JsonNode> txn = Optional.empty();
        OffsetDateTime since;
        ObjectNode sale = rows.find(TABLE, merchant(), id).orElseThrow(() -> Access.notFound("SALE", id));
        if (!"PENDING".equals(sale.path("status").asString())) {
            return sale;
        }
        since = OffsetDateTime.parse(sale.path("created_at").asString()).minusMinutes(5);
        for (JsonNode t : down.all(MERCHANTS, "/v1/merchants/" + merchant() + "/transactions",
                Query.of("from", since.toString()), 1000)) {
            if (Access.ofMerchant(t) && qrId.equals(t.path("metadata").path("merchant_qr_id").asString(null))
                    && "MERCHANT_PAYMENT".equals(t.path("txn_type").asString(""))) {
                txn = Optional.of(t);
                break;
            }
        }
        Optional<JsonNode> found = txn;
        ObjectNode r = tx.execute(s -> {
            ObjectNode locked = rows.lock(TABLE, merchant(), id).orElseThrow(() -> Access.notFound("SALE", id));
            if (!"PENDING".equals(locked.path("status").asString())) {
                return locked;
            }
            ObjectNode v = rows.object().put("status", "PAID")
                    .put("paid_at", qr.path("last_paid_at").asString(null));
            found.ifPresent(t -> v.put("wallet_transaction_id", t.path("id").asString()));
            if (!v.hasNonNull("paid_at")) {
                v.remove("paid_at");
                rows.execute("update merchant_sale set paid_at = now() where id = ?", id);
            }
            ObjectNode after = rows.update(TABLE, id, v);
            outbox.publish("channels.merchant_sale.paid", TABLE, id, after);
            return after;
        });
        return r;
    }

    private static String ts(OffsetDateTime t) {
        return t == null ? null : t.toString();
    }
}
