package io.meridian.biller.application;

import io.meridian.biller.domain.Lifecycle;
import io.meridian.biller.domain.ReferenceValidator;
import io.meridian.biller.infrastructure.Rows;
import io.meridian.biller.infrastructure.WalletClient;
import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Bill payments and airtime top-ups. The payer's funds are reserved in wallet-service with a two-phase wallet
 * transaction (payer wallet -> biller collection wallet) before the biller is called; the reservation is confirmed
 * when the biller accepts, cancelled when it refuses, and stays held while the provider has not answered
 * (PENDING_PROVIDER, resolved by /retry or /reverse). A wallet-service rejection (insufficient funds, wrong PIN, limits)
 * is kept as a FAILED row whose failure_reason starts with "[code]" and the request is answered 422 with that code;
 * replaying the same Idempotency-Key returns the same error.
 */
@Service
public class PaymentService {
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    static final Set<String> AIRTIME_CATEGORIES = Set.of("AIRTIME", "TELECOM", "DATA");

    /** The two payment kinds share one flow. */
    public enum Kind {
        BILL("bill_payment", "BILL_PAYMENT", "bpy", "wallets.bill_payment."),
        TOPUP("airtime_topup", "AIRTIME", "atu", "wallets.airtime_topup.");

        final String table;
        final String txnType;
        final String keyPrefix;
        final String eventPrefix;

        Kind(String table, String txnType, String keyPrefix, String eventPrefix) {
            this.table = table;
            this.txnType = txnType;
            this.keyPrefix = keyPrefix;
            this.eventPrefix = eventPrefix;
        }
    }

    private final Rows rows;
    private final WalletClient wallet;
    private final BillerGateway gateway;
    private final Events events;
    private final TransactionTemplate tx;
    private final CatalogService catalog;

    public PaymentService(Rows rows, WalletClient wallet, BillerGateway gateway, Events events, TransactionTemplate tx,
                          CatalogService catalog) {
        this.rows = rows;
        this.wallet = wallet;
        this.gateway = gateway;
        this.events = events;
        this.tx = tx;
        this.catalog = catalog;
    }

    // ------------------------------------------------------------------ creation

    public ObjectNode payBill(JsonNode req, String key) {
        Optional<ObjectNode> replay = existing(Kind.BILL, key);
        if (replay.isPresent()) {
            return replay.get();
        }
        ObjectNode v = rows.columns(Kind.BILL.table, req);
        ObjectNode biller = payableBiller(Rows.uuid(v, "biller_id"), Rows.str(v, "currency"));
        BigDecimal amount = amount(v);
        String ref = Rows.str(v, "account_reference").trim();
        v.put("account_reference", ref);
        ReferenceValidator.validate(ref, Rows.str(biller, "account_reference_regex"),
                Rows.str(biller, "account_reference_checksum")).ifPresent(msg -> {
                    throw ApiException.unprocessable("invalid_reference", msg);
                });
        UUID productId = Rows.uuid(v, "biller_product_id");
        if (productId != null) {
            checkProduct(productId, biller, amount, null);
        }
        UUID inquiryId = Rows.uuid(v, "bill_inquiry_id");
        if (inquiryId != null) {
            ObjectNode inq = rows.find("bill_inquiry", inquiryId).orElseThrow(() ->
                    ApiException.unprocessable("inquiry_not_found", "The bill inquiry does not exist"));
            if (!Rows.str(inq, "biller_id").equals(Rows.str(biller, "id")) || !ref.equals(Rows.str(inq, "account_reference"))) {
                throw ApiException.unprocessable("inquiry_mismatch", "The inquiry is for another biller or account");
            }
            if (!"00".equals(Rows.str(inq, "response_code"))) {
                throw ApiException.unprocessable("account_not_found", "The biller did not find the account");
            }
            if (rows.scalar("select expires_at < now() from bill_inquiry where id = ?", Boolean.class, inquiryId).orElse(true)) {
                throw ApiException.unprocessable("inquiry_expired", "The bill inquiry expired; look the bill up again");
            }
            Lifecycle.checkAgainstDue(amount, Rows.dec(inq, "amount_due"), Rows.bool(biller, "supports_partial_payment"),
                    Rows.bool(biller, "supports_overpayment"));
            if (!v.hasNonNull("customer_name") && inq.hasNonNull("customer_name")) {
                v.put("customer_name", Rows.str(inq, "customer_name"));
            }
        }
        checkRange(biller, amount);
        return start(Kind.BILL, v, biller, Rows.str(req, "pin"), key, ref);
    }

    public ObjectNode topup(JsonNode req, String key) {
        Optional<ObjectNode> replay = existing(Kind.TOPUP, key);
        if (replay.isPresent()) {
            return replay.get();
        }
        ObjectNode v = rows.columns(Kind.TOPUP.table, req);
        ObjectNode biller = payableBiller(Rows.uuid(v, "biller_id"), Rows.str(v, "currency"));
        if (!AIRTIME_CATEGORIES.contains(Rows.str(biller, "category"))) {
            throw ApiException.unprocessable("not_airtime_biller", "The biller does not sell airtime or data");
        }
        BigDecimal amount = amount(v);
        UUID productId = Rows.uuid(v, "biller_product_id");
        if (productId != null) {
            checkProduct(productId, biller, amount, Set.of("AIRTIME", "DATA_BUNDLE"));
        }
        checkRange(biller, amount);
        return start(Kind.TOPUP, v, biller, Rows.str(req, "pin"), key, Rows.str(v, "target_msisdn"));
    }

    /** A replay of a request already recorded under the key: its row, or its original wallet error. */
    private Optional<ObjectNode> existing(Kind k, String key) {
        Optional<ObjectNode> row = rows.queryOne("select row_to_json(t)::text from " + k.table + " t where idempotency_key = ?", key);
        row.filter(r -> "FAILED".equals(Rows.str(r, "status")) && r.hasNonNull("failure_reason")
                && Rows.str(r, "failure_reason").startsWith("[")).ifPresent(r -> {
                    throw walletError(Rows.str(r, "failure_reason"));
                });
        return row;
    }

    private static ApiException walletError(String reason) {
        int end = reason.indexOf(']');
        return ApiException.unprocessable(reason.substring(1, end), reason.substring(end + 1).trim());
    }

    private ObjectNode payableBiller(UUID billerId, String currency) {
        ObjectNode biller = catalog.biller(billerId);
        if (!"ACTIVE".equals(Rows.str(biller, "status"))) {
            throw ApiException.unprocessable("biller_not_active", "The biller is " + Rows.str(biller, "status"));
        }
        if (biller.get("settlement_wallet_id") == null || biller.get("settlement_wallet_id").isNull()) {
            throw ApiException.unprocessable("biller_not_configured", "The biller has no collection wallet");
        }
        if (!Rows.str(biller, "currency").equals(currency)) {
            throw ApiException.unprocessable("currency_mismatch", "The biller accepts " + Rows.str(biller, "currency"));
        }
        return biller;
    }

    private static BigDecimal amount(ObjectNode v) {
        BigDecimal amount = Rows.dec(v, "amount");
        if (amount == null || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2) {
            throw ApiException.unprocessable("invalid_amount", "amount must be positive with at most 2 decimals");
        }
        return amount;
    }

    private void checkProduct(UUID productId, ObjectNode biller, BigDecimal amount, Set<String> types) {
        ObjectNode p = rows.find("biller_product", productId).orElseThrow(() ->
                ApiException.unprocessable("product_not_found", "The biller product does not exist"));
        if (!Rows.str(p, "biller_id").equals(Rows.str(biller, "id")) || !Rows.bool(p, "is_active")) {
            throw ApiException.unprocessable("product_not_available", "The product is not sold by this biller");
        }
        if (types != null && !types.contains(Rows.str(p, "product_type"))) {
            throw ApiException.unprocessable("product_not_available", "The product is not an airtime or data product");
        }
        BigDecimal denomination = Rows.dec(p, "denomination");
        if (denomination != null && denomination.compareTo(amount) != 0) {
            throw ApiException.unprocessable("amount_mismatch", "The product costs " + denomination.toPlainString());
        }
    }

    private static void checkRange(ObjectNode biller, BigDecimal amount) {
        BigDecimal min = Rows.dec(biller, "min_amount");
        BigDecimal max = Rows.dec(biller, "max_amount");
        if (min != null && amount.compareTo(min) < 0 || max != null && amount.compareTo(max) > 0) {
            throw ApiException.unprocessable("amount_out_of_range", "The biller accepts amounts between "
                    + (min == null ? "0" : min.toPlainString()) + " and " + (max == null ? "any" : max.toPlainString()));
        }
    }

    /** Records the payment, reserves the funds in wallet-service, then calls the provider (attempt 1). */
    private ObjectNode start(Kind k, ObjectNode v, ObjectNode biller, String pin, String key, String externalRef) {
        v.put("status", "INITIATED");
        v.put("idempotency_key", key);
        ObjectNode row = tx.execute(s -> rows.insert(k.table, v));
        UUID id = Rows.uuid(row, "id");
        ObjectNode body = wallet.body();
        body.put("txn_type", k.txnType);
        body.put("channel", Rows.str(row, "channel"));
        body.put("initiator_type", "CUSTOMER");
        body.put("amount", Rows.str(row, "amount"));
        body.put("currency", Rows.str(row, "currency"));
        body.put("payer_wallet_id", Rows.str(row, "payer_wallet_id"));
        body.put("payee_wallet_id", Rows.str(biller, "settlement_wallet_id"));
        body.put("biller_id", Rows.str(biller, "id"));
        if (k == Kind.BILL) {
            body.put("bill_payment_id", id.toString());
        }
        body.put("payee_external_ref", externalRef);
        body.put("description", (k == Kind.BILL ? "Bill payment " : "Airtime ") + Rows.str(biller, "name") + " " + externalRef);
        body.put("two_phase", true);
        if (pin != null) {
            body.put("pin", pin);
        }
        JsonNode wtx;
        try {
            wtx = wallet.create(body, k.keyPrefix + "-" + id + "-debit");
        } catch (ApiException e) {
            String reason = "[" + e.code() + "] " + (e.getMessage() == null ? e.code() : e.getMessage());
            tx.executeWithoutResult(s -> {
                ObjectNode f = rows.object();
                f.put("status", "FAILED");
                f.put("failure_reason", reason.length() > 255 ? reason.substring(0, 255) : reason);
                events.publish(k.eventPrefix + "failed", k.table, rows.update(k.table, id, f));
            });
            throw ApiException.unprocessable(e.code(), e.getMessage());
        }
        ObjectNode processing = tx.execute(s -> {
            ObjectNode u = rows.object();
            Lifecycle.payment("INITIATED", "PROCESSING");
            u.put("status", "PROCESSING");
            u.put("wallet_transaction_id", wtx.get("id").asString());
            if (k == Kind.BILL) {
                u.put("fee_amount", wtx.path("fee_amount").asString("0"));
            }
            return rows.update(k.table, id, u);
        });
        return attempt(k, processing, biller, 1);
    }

    /** Calls the provider and applies its answer: confirm, cancel or keep the funds held (timeout). */
    private ObjectNode attempt(Kind k, ObjectNode row, ObjectNode biller, int attempt) {
        UUID id = Rows.uuid(row, "id");
        UUID wtx = Rows.uuid(row, "wallet_transaction_id");
        BillerGateway.Result r = k == Kind.BILL ? gateway.pay(biller, row, attempt) : gateway.topup(biller, row, attempt);
        switch (r.outcome()) {
            case SUCCESS -> {
                try {
                    wallet.confirm(wtx, k.keyPrefix + "-" + id + "-confirm");
                } catch (ApiException e) {
                    log.error("{} {} accepted by the provider ({}) but the funds could not be captured: {}", k.table, id,
                            r.providerReference(), e.getMessage());
                    return finish(k, id, "FAILED", null, "Provider accepted (" + r.providerReference()
                            + ") but the wallet capture failed: " + e.code(), "failed");
                }
                ObjectNode u = rows.object();
                u.put("provider_reference", r.providerReference());
                if (k == Kind.BILL) {
                    if (r.token() != null) {
                        u.put("token", r.token());
                    }
                    if (r.receiptNumber() != null) {
                        u.put("receipt_number", r.receiptNumber());
                    }
                }
                return finish(k, id, "COMPLETED", u, null, "completed");
            }
            case REJECTED -> {
                wallet.cancel(wtx, r.message(), k.keyPrefix + "-" + id + "-cancel");
                return finish(k, id, "FAILED", null, r.message(), "failed");
            }
            default -> {
                return finish(k, id, "PENDING_PROVIDER", null, null, null);
            }
        }
    }

    private ObjectNode finish(Kind k, UUID id, String status, ObjectNode extra, String failure, String event) {
        return tx.execute(s -> {
            ObjectNode cur = rows.lock(k.table, id);
            String from = Rows.str(cur, "status");
            if (from.equals(status) && !"PENDING_PROVIDER".equals(status)) {
                return cur;
            }
            Lifecycle.payment(from, status);
            ObjectNode u = extra == null ? rows.object() : extra;
            u.put("status", status);
            if (failure != null) {
                u.put("failure_reason", failure.length() > 255 ? failure.substring(0, 255) : failure);
            }
            rows.update(k.table, id, u);
            if ("COMPLETED".equals(status)) {
                rows.execute("update " + k.table + " set completed_at = now() where id = ?", id);
            }
            ObjectNode after = rows.get(k.table, id);
            if (event != null) {
                events.publish(k.eventPrefix + event, k.table, after);
            }
            return after;
        });
    }

    // ------------------------------------------------------------------ retry / reverse

    /** Asks the provider again about a payment it did not answer; only PENDING_PROVIDER payments can be retried. */
    public ObjectNode retry(Kind k, UUID id) {
        ObjectNode row = tx.execute(s -> {
            ObjectNode cur = rows.lock(k.table, id);
            if (!"PENDING_PROVIDER".equals(Rows.str(cur, "status"))) {
                throw ApiException.conflict("invalid_transition", "Only a payment pending at the provider can be retried");
            }
            rows.execute("update " + k.table + " set retry_count = retry_count + 1 where id = ?", id);
            return rows.get(k.table, id);
        });
        ObjectNode biller = catalog.biller(Rows.uuid(row, "biller_id"));
        return attempt(k, row, biller, Rows.integer(row, "retry_count") + 1);
    }

    /**
     * Reverses a completed (disputed) bill payment back to the payer through wallet-service (the wallet-side checker
     * must differ from the payment initiator), or closes a payment still pending at the provider by releasing the
     * held funds. A payment already settled to the biller cannot be reversed here.
     */
    public ObjectNode reverse(UUID id, String reason) {
        ObjectNode row = rows.get(Kind.BILL.table, id);
        UUID wtx = Rows.uuid(row, "wallet_transaction_id");
        switch (Rows.str(row, "status")) {
            case "COMPLETED" -> {
                if (row.hasNonNull("biller_settlement_id")) {
                    throw ApiException.unprocessable("payment_settled",
                            "The payment was already settled to the biller; recover it from the biller first");
                }
                wallet.reverse(wtx, "Bill payment reversal: " + reason, "bpy-" + id + "-reverse");
                return tx.execute(s -> {
                    ObjectNode cur = rows.lock(Kind.BILL.table, id);
                    if ("REVERSED".equals(Rows.str(cur, "status"))) {
                        return cur;
                    }
                    Lifecycle.payment(Rows.str(cur, "status"), "REVERSED");
                    ObjectNode u = rows.object();
                    u.put("status", "REVERSED");
                    u.put("reversal_reason", reason);
                    rows.update(Kind.BILL.table, id, u);
                    rows.execute("update bill_payment set reversed_at = now() where id = ?", id);
                    ObjectNode after = rows.get(Kind.BILL.table, id);
                    events.publish("wallets.bill_payment.reversed", Kind.BILL.table, after);
                    return after;
                });
            }
            case "PENDING_PROVIDER" -> {
                wallet.cancel(wtx, reason, "bpy-" + id + "-cancel");
                return finish(Kind.BILL, id, "FAILED", null, "Closed while pending at the provider: " + reason, "failed");
            }
            case "REVERSED" -> {
                return row;
            }
            default -> throw ApiException.conflict("invalid_transition",
                    "A " + Rows.str(row, "status") + " payment cannot be reversed");
        }
    }

    /** Printable receipt with the biller's reference and token. */
    public String receipt(UUID id) {
        ObjectNode p = rows.get("bill_payment", id);
        ObjectNode b = rows.get("biller", Rows.uuid(p, "biller_id"));
        StringBuilder sb = new StringBuilder("MERIDIAN BILL PAYMENT RECEIPT\n");
        line(sb, "Biller", Rows.str(b, "name") + " (" + Rows.str(b, "biller_code") + ")");
        line(sb, b.hasNonNull("account_reference_label") ? Rows.str(b, "account_reference_label") : "Account",
                Rows.str(p, "account_reference"));
        line(sb, "Customer", Rows.str(p, "customer_name"));
        line(sb, "Amount", Rows.str(p, "amount") + " " + Rows.str(p, "currency"));
        line(sb, "Fee", Rows.str(p, "fee_amount") + " " + Rows.str(p, "currency"));
        line(sb, "Status", Rows.str(p, "status"));
        line(sb, "Biller ref", Rows.str(p, "provider_reference"));
        line(sb, "Receipt no", Rows.str(p, "receipt_number"));
        line(sb, "Token", Rows.str(p, "token"));
        line(sb, "Date", p.hasNonNull("completed_at") ? Rows.str(p, "completed_at") : Rows.str(p, "created_at"));
        line(sb, "Payment id", id.toString());
        return sb.toString();
    }

    private static void line(StringBuilder sb, String label, String value) {
        if (value != null) {
            sb.append(String.format("%-12s%s%n", label + ":", value));
        }
    }
}
