package io.meridian.payment.application;

import static io.meridian.payment.infrastructure.Rows.bool;
import static io.meridian.payment.infrastructure.Rows.dec;
import static io.meridian.payment.infrastructure.Rows.integer;
import static io.meridian.payment.infrastructure.Rows.str;
import static io.meridian.payment.infrastructure.Rows.ts;
import static io.meridian.payment.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.payment.config.PaymentProperties;
import io.meridian.payment.domain.AliasRules;
import io.meridian.payment.domain.EmvQr;
import io.meridian.payment.infrastructure.CoreBankingClient;
import io.meridian.payment.infrastructure.Rows;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Ways to get paid: request-to-pay, payment links and EMVCo QR codes. Paying any of them creates an internal
 * (book) payment through the normal pipeline; the receivable is marked paid when that payment settles.
 */
@Service
public class ReceivablesService {
    static final String RTP = "request_to_pay";
    static final String LINK = "payment_link";
    static final String QR = "payment_qr_code";
    private static final Set<String> METHODS = Set.of("ACCOUNT", "WALLET", "CARD", "QR");
    private static final String SLUG_CHARS = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final Rows rows;
    private final Events events;
    private final PaymentProcessor processor;
    private final PayeeService payees;
    private final CoreBankingClient core;
    private final PaymentProperties props;
    private final TransactionTemplate tx;
    private final SecureRandom random = new SecureRandom();

    public ReceivablesService(Rows rows, Events events, PaymentProcessor processor, PayeeService payees,
                              CoreBankingClient core, PaymentProperties props, TransactionTemplate tx) {
        this.rows = rows;
        this.events = events;
        this.processor = processor;
        this.payees = payees;
        this.core = core;
        this.props = props;
        this.tx = tx;
    }

    // ================================================================== request-to-pay

    public ObjectNode createRequest(JsonNode req) {
        ObjectNode v = rows.columns(RTP, req);
        BigDecimal amount = dec(v, "amount");
        PaymentProcessor.validateAmount(amount, str(v, "currency"), "amount");
        UUID account = uuid(v, "requester_account_id");
        if (account == null) {
            throw ApiException.unprocessable("requester_account_required", "requester_account_id is required");
        }
        JsonNode acc = core.depositAccount(account, "requester");
        if (!uuid(acc, "customer_id").equals(uuid(v, "requester_customer_id"))) {
            throw ApiException.unprocessable("account_not_owned", "The account does not belong to the requester");
        }
        if (!str(v, "currency").equals(str(acc, "currency"))) {
            throw ApiException.unprocessable("currency_mismatch", "The request currency must be the account currency");
        }
        if (v.hasNonNull("payer_alias_type") != v.hasNonNull("payer_alias_value")) {
            throw ApiException.unprocessable("invalid_payer_alias", "payer_alias_type and payer_alias_value go together");
        }
        if (v.hasNonNull("payer_alias_type")) {
            ObjectNode alias = payees.resolve(str(v, "payer_alias_type"), str(v, "payer_alias_value"));
            try {
                v.put("payer_alias_value", AliasRules.normalise(str(v, "payer_alias_type"), str(v, "payer_alias_value")));
            } catch (AliasRules.InvalidAlias e) {
                throw ApiException.unprocessable("invalid_alias", e.getMessage());
            }
            if (!v.hasNonNull("payer_customer_id")) {
                rows.queryOne("select row_to_json(t)::text from payee_alias t where id = ?", uuid(alias, "id"))
                        .map(a -> str(a, "customer_id")).ifPresent(c -> v.put("payer_customer_id", c));
            }
        }
        return tx.execute(s -> {
            OffsetDateTime expires = OffsetDateTime.parse(str(v, "expires_at"));
            OffsetDateTime now = rows.now();
            if (!expires.isAfter(now) || expires.isAfter(now.plusDays(90))) {
                throw ApiException.unprocessable("invalid_expiry", "expires_at must be in the future and within 90 days");
            }
            v.put("status", "SENT");
            ObjectNode r = rows.insert(RTP, v);
            events.publish("payments.request_to_pay.sent", RTP, r);
            return r;
        });
    }

    /** Payer accepts: an internal payment from the payer's account to the requester's account. */
    public ObjectNode acceptRequest(UUID id, UUID debtorAccountId, BigDecimal amount, String key) {
        ObjectNode r = rows.get(RTP, id);
        if (!Set.of("SENT", "VIEWED", "ACCEPTED").contains(str(r, "status"))) {
            throw ApiException.conflict("invalid_transition", "Request-to-pay is " + str(r, "status"));
        }
        if (expired(RTP, id, r)) {
            throw ApiException.conflict("request_expired", "The request-to-pay has expired");
        }
        BigDecimal requested = dec(r, "amount");
        BigDecimal pay = amount == null ? requested : amount;
        if (pay.compareTo(requested) > 0 || (!bool(r, "allow_partial") && pay.compareTo(requested) != 0)) {
            throw ApiException.unprocessable("invalid_amount", bool(r, "allow_partial")
                    ? "Amount must not exceed the requested " + requested.toPlainString()
                    : "This request must be paid in full (" + requested.toPlainString() + ")");
        }
        JsonNode debtor = core.depositAccount(debtorAccountId, "debtor");
        if (r.hasNonNull("payer_customer_id") && !uuid(r, "payer_customer_id").equals(uuid(debtor, "customer_id"))) {
            throw ApiException.unprocessable("payer_mismatch", "The request was sent to another payer");
        }
        ObjectNode req = internalPayment("REQUEST_TO_PAY", debtorAccountId, uuid(r, "requester_account_id"), pay, str(r, "currency"));
        req.put("request_to_pay_id", id.toString());
        req.put("remittance_unstructured", PaymentProcessor.truncate(firstNonNull(str(r, "invoice_reference"),
                str(r, "description"), "Request to pay"), 140));
        if (r.hasNonNull("invoice_reference")) {
            req.put("reference", PaymentProcessor.truncate(str(r, "invoice_reference"), 35));
        }
        ObjectNode payment = processor.initiate(req, "rtp:" + id + ":" + key, PaymentProcessor.Options.DEFAULT);
        if (!Set.of("REJECTED", "FAILED", "CANCELLED").contains(str(payment, "status"))) {
            tx.execute(s -> {
                ObjectNode cur = rows.lock(RTP, id);
                if (!"PAID".equals(str(cur, "status"))) {
                    ObjectNode c = rows.object();
                    c.put("status", "ACCEPTED");
                    c.put("payer_customer_id", str(debtor, "customer_id"));
                    c.put("responded_at", rows.now().toString());
                    rows.update(RTP, id, c);
                }
                return null;
            });
        }
        return processor.resume(UUID.fromString(str(payment, "id")), PaymentProcessor.Options.DEFAULT);
    }

    public ObjectNode declineRequest(UUID id, String reason, boolean byRequester) {
        return tx.execute(s -> {
            ObjectNode r = rows.lock(RTP, id);
            if (!Set.of("SENT", "VIEWED").contains(str(r, "status"))) {
                throw ApiException.conflict("invalid_transition", "Request-to-pay is " + str(r, "status"));
            }
            ObjectNode c = rows.object();
            c.put("status", byRequester ? "CANCELLED" : "DECLINED");
            c.put("responded_at", rows.now().toString());
            ObjectNode updated = rows.update(RTP, id, c);
            events.publish("payments.request_to_pay.declined", RTP, updated);
            return updated;
        });
    }

    // ================================================================== payment links

    public ObjectNode createLink(JsonNode req) {
        ObjectNode v = rows.columns(LINK, req);
        UUID account = uuid(v, "credit_account_id");
        if (account == null) {
            throw ApiException.unprocessable("credit_account_required", "credit_account_id is required");
        }
        JsonNode acc = core.depositAccount(account, "credit");
        if (!str(v, "currency").equals(str(acc, "currency"))) {
            throw ApiException.unprocessable("currency_mismatch", "The link currency must be the account currency");
        }
        if (v.hasNonNull("customer_id") && !uuid(v, "customer_id").equals(uuid(acc, "customer_id"))) {
            throw ApiException.unprocessable("account_not_owned", "The account does not belong to the link owner");
        }
        if (!v.hasNonNull("customer_id") && !v.hasNonNull("merchant_id")) {
            v.put("customer_id", str(acc, "customer_id"));
        }
        validateLinkAmounts(v);
        if (v.has("allowed_methods")) {
            for (JsonNode m : v.get("allowed_methods")) {
                if (!METHODS.contains(m.asString())) {
                    throw ApiException.unprocessable("invalid_method", "allowed_methods entries must be ACCOUNT, WALLET, CARD or QR");
                }
            }
        }
        return tx.execute(s -> {
            if (v.hasNonNull("expires_at") && !OffsetDateTime.parse(str(v, "expires_at")).isAfter(rows.now())) {
                throw ApiException.unprocessable("invalid_expiry", "expires_at must be in the future");
            }
            String slug = slug();
            v.put("slug", slug);
            v.put("url", props.linkBaseUrl().replaceAll("/+$", "") + "/l/" + slug);
            v.put("status", "ACTIVE");
            v.put("payment_count", 0);
            return rows.insert(LINK, v);
        });
    }

    public void validateLinkAmounts(ObjectNode v) {
        BigDecimal amount = dec(v, "amount");
        BigDecimal min = dec(v, "min_amount");
        BigDecimal max = dec(v, "max_amount");
        if (amount != null) {
            PaymentProcessor.validateAmount(amount, str(v, "currency"), "amount");
            if (min != null || max != null) {
                throw ApiException.unprocessable("invalid_amount", "A fixed-amount link has no min_amount / max_amount");
            }
        }
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw ApiException.unprocessable("invalid_amount_range", "min_amount is greater than max_amount");
        }
        if (v.hasNonNull("max_payments") && (!bool(v, "reusable") || integer(v, "max_payments") < 1)) {
            throw ApiException.unprocessable("invalid_max_payments", "max_payments needs reusable=true and a value >= 1");
        }
    }

    public ObjectNode payLink(UUID id, UUID debtorAccountId, BigDecimal amount, String payerName, String key) {
        ObjectNode link = rows.get(LINK, id);
        if (!"ACTIVE".equals(str(link, "status"))) {
            throw ApiException.conflict("link_not_active", "Payment link is " + str(link, "status"));
        }
        if (expired(LINK, id, link)) {
            throw ApiException.conflict("link_expired", "The payment link has expired");
        }
        BigDecimal pay;
        if (link.hasNonNull("amount")) {
            pay = dec(link, "amount");
            if (amount != null && amount.compareTo(pay) != 0) {
                throw ApiException.unprocessable("invalid_amount", "This link is for exactly " + pay.toPlainString());
            }
        } else {
            if (amount == null) {
                throw ApiException.unprocessable("amount_required", "This link needs the payer to enter an amount");
            }
            pay = amount;
            if (link.hasNonNull("min_amount") && pay.compareTo(dec(link, "min_amount")) < 0
                    || link.hasNonNull("max_amount") && pay.compareTo(dec(link, "max_amount")) > 0) {
                throw ApiException.unprocessable("amount_out_of_range", "Amount must be between the link minimum and maximum");
            }
        }
        if (link.path("allowed_methods").isArray() && !link.get("allowed_methods").isEmpty()) {
            boolean account = false;
            for (JsonNode m : link.get("allowed_methods")) {
                account |= "ACCOUNT".equals(m.asString());
            }
            if (!account) {
                throw ApiException.unprocessable("method_not_allowed", "This link does not accept account payments");
            }
        }
        ObjectNode req = internalPayment("LINK_PAYMENT", debtorAccountId, uuid(link, "credit_account_id"), pay, str(link, "currency"));
        req.putObject("metadata").put("payment_link_id", id.toString()).put("payment_link_slug", str(link, "slug"));
        if (link.hasNonNull("merchant_id")) {
            req.put("creditor_merchant_id", str(link, "merchant_id"));
        }
        if (payerName != null) {
            req.put("debtor_name", PaymentProcessor.truncate(payerName, 140));
        }
        req.put("remittance_unstructured", PaymentProcessor.truncate(str(link, "title"), 140));
        return processor.initiate(req, "plk:" + id + ":" + key, PaymentProcessor.Options.DEFAULT);
    }

    public ObjectNode disableLink(UUID id) {
        return tx.execute(s -> {
            ObjectNode link = rows.lock(LINK, id);
            if (!"ACTIVE".equals(str(link, "status"))) {
                throw ApiException.conflict("invalid_transition", "Payment link is " + str(link, "status"));
            }
            ObjectNode updated = rows.update(LINK, id, rows.object().put("status", "DISABLED"));
            events.publish("payments.payment_link.disabled", LINK, updated);
            return updated;
        });
    }

    public ObjectNode linkPayments(UUID id, Integer limit, String cursor) {
        rows.get(LINK, id);
        return rows.page("payment", "t.metadata->>'payment_link_id' = ?", limit, cursor, id.toString());
    }

    // ================================================================== QR codes

    public ObjectNode createQr(JsonNode req) {
        ObjectNode v = rows.columns(QR, req);
        String type = str(v, "qr_type");
        UUID account = uuid(v, "credit_account_id");
        if (account == null) {
            throw ApiException.unprocessable("credit_account_required", "credit_account_id is required");
        }
        JsonNode acc = core.depositAccount(account, "credit");
        if (!str(v, "currency").equals(str(acc, "currency"))) {
            throw ApiException.unprocessable("currency_mismatch", "The QR currency must be the account currency");
        }
        if (!v.hasNonNull("customer_id") && !v.hasNonNull("merchant_id")) {
            v.put("customer_id", str(acc, "customer_id"));
        }
        BigDecimal amount = dec(v, "amount");
        if (amount != null) {
            PaymentProcessor.validateAmount(amount, str(v, "currency"), "amount");
        }
        if ("DYNAMIC".equals(type) && amount == null) {
            throw ApiException.unprocessable("amount_required", "A dynamic QR carries the amount to pay");
        }
        String mcc = str(v, "merchant_category_code");
        if (mcc != null && !mcc.matches("\\d{4}")) {
            throw ApiException.unprocessable("invalid_mcc", "merchant_category_code must be 4 digits (ISO 18245)");
        }
        String scheme = str(v, "scheme");
        if (!Set.of("EMVCO", "PIX", "UPI", "PAYNOW", "PROMPTPAY", "DUITNOW", "BHARAT_QR", "INTERNAL").contains(scheme)) {
            throw ApiException.unprocessable("invalid_scheme", "Unsupported QR scheme " + scheme);
        }
        return tx.execute(s -> {
            OffsetDateTime now = rows.now();
            if ("DYNAMIC".equals(type) && !v.hasNonNull("expires_at")) {
                v.put("expires_at", now.plusMinutes(15).toString());
            }
            if (v.hasNonNull("expires_at") && !OffsetDateTime.parse(str(v, "expires_at")).isAfter(now)) {
                throw ApiException.unprocessable("invalid_expiry", "expires_at must be in the future");
            }
            UUID id = Ids.newId();
            String payload;
            try {
                payload = EmvQr.encode(new EmvQr.Spec("DYNAMIC".equals(type), props.qrGuid(), id.toString(),
                        mcc == null ? "0000" : mcc, str(v, "currency"), amount, str(v, "country_code"),
                        str(v, "merchant_name"), str(v, "merchant_city"), str(v, "reference")));
            } catch (IllegalArgumentException e) {
                throw ApiException.unprocessable("invalid_qr_data", e.getMessage());
            }
            v.put("id", id.toString());
            v.put("payload", payload);
            v.put("status", "ACTIVE");
            ObjectNode qr = rows.insert(QR, v);
            events.publish("payments.payment_qr_code.created", QR, qr);
            return qr;
        });
    }

    /** Decodes a scanned payload, verifies the CRC and matches it to one of our QR codes. */
    public ObjectNode decode(String payload) {
        EmvQr.Decoded d;
        try {
            d = EmvQr.decode(payload == null ? null : payload.trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable(e.getMessage().contains(":") ? "invalid_qr_payload" : e.getMessage(),
                    "The QR payload is not a valid EMVCo TLV string");
        }
        if (!d.crcValid()) {
            throw ApiException.unprocessable("invalid_qr_crc", "The QR CRC does not match (expected " + d.expectedCrc() + ")");
        }
        ObjectNode out = rows.object();
        out.put("crc_valid", true);
        out.put("payload_format_indicator", d.fields().get(EmvQr.PAYLOAD_FORMAT));
        out.put("point_of_initiation", "12".equals(d.fields().get(EmvQr.POINT_OF_INITIATION)) ? "DYNAMIC" : "STATIC");
        d.field(EmvQr.MCC).ifPresent(x -> out.put("merchant_category_code", x));
        d.field(EmvQr.CURRENCY).flatMap(EmvQr::alphaCurrency).ifPresent(x -> out.put("currency", x));
        d.field(EmvQr.AMOUNT).ifPresent(x -> out.put("amount", x));
        d.field(EmvQr.COUNTRY).ifPresent(x -> out.put("country_code", x));
        d.field(EmvQr.NAME).ifPresent(x -> out.put("merchant_name", x));
        d.field(EmvQr.CITY).ifPresent(x -> out.put("merchant_city", x));
        ObjectNode tags = out.putObject("tags");
        d.fields().forEach(tags::put);
        ObjectNode templates = out.putObject("templates");
        for (Map.Entry<String, Map<String, String>> t : d.templates().entrySet()) {
            ObjectNode n = templates.putObject(t.getKey());
            t.getValue().forEach(n::put);
        }
        Map<String, String> mai = d.templates().get(EmvQr.MERCHANT_ACCOUNT);
        Map<String, String> add = d.templates().get(EmvQr.ADDITIONAL);
        if (add != null && add.containsKey("05")) {
            out.put("reference", add.get("05"));
        }
        boolean ours = mai != null && props.qrGuid().equals(mai.get("00"));
        out.put("on_us", ours);
        if (ours) {
            Optional<ObjectNode> qr = Optional.empty();
            try {
                qr = rows.find(QR, UUID.fromString(mai.get("01")));
            } catch (IllegalArgumentException ignored) {
                // not one of our identifiers
            }
            if (qr.isPresent()) {
                ObjectNode q = qr.get();
                boolean expired = q.hasNonNull("expires_at") && ts(q, "expires_at").isBefore(rows.now());
                out.put("payment_qr_code_id", str(q, "id"));
                out.put("status", expired && "ACTIVE".equals(str(q, "status")) ? "EXPIRED" : str(q, "status"));
                out.put("payable", "ACTIVE".equals(str(q, "status")) && !expired);
                out.put("qr_type", str(q, "qr_type"));
            } else {
                out.put("payable", false);
            }
        } else {
            out.put("payable", false);
        }
        return out;
    }

    public ObjectNode payQr(UUID id, UUID debtorAccountId, BigDecimal amount, String key) {
        ObjectNode qr = rows.get(QR, id);
        if (!"ACTIVE".equals(str(qr, "status"))) {
            throw ApiException.conflict("qr_not_active", "QR code is " + str(qr, "status"));
        }
        if (expired(QR, id, qr)) {
            throw ApiException.conflict("qr_expired", "The QR code has expired");
        }
        BigDecimal pay;
        if (qr.hasNonNull("amount")) {
            pay = dec(qr, "amount");
            if (amount != null && amount.compareTo(pay) != 0) {
                throw ApiException.unprocessable("invalid_amount", "This QR is for exactly " + pay.toPlainString());
            }
        } else {
            if (amount == null) {
                throw ApiException.unprocessable("amount_required", "This static QR needs the payer to enter an amount");
            }
            pay = amount;
        }
        ObjectNode req = internalPayment("QR_PAYMENT", debtorAccountId, uuid(qr, "credit_account_id"), pay, str(qr, "currency"));
        req.putObject("metadata").put("payment_qr_code_id", id.toString());
        if (qr.hasNonNull("merchant_id")) {
            req.put("creditor_merchant_id", str(qr, "merchant_id"));
        }
        req.put("creditor_name", str(qr, "merchant_name"));
        if (qr.hasNonNull("reference")) {
            req.put("reference", str(qr, "reference"));
            req.put("remittance_unstructured", str(qr, "reference"));
        }
        return processor.initiate(req, "pqr:" + id + ":" + key, PaymentProcessor.Options.DEFAULT);
    }

    public ObjectNode disableQr(UUID id) {
        return tx.execute(s -> {
            ObjectNode qr = rows.lock(QR, id);
            if (!"ACTIVE".equals(str(qr, "status"))) {
                throw ApiException.conflict("invalid_transition", "QR code is " + str(qr, "status"));
            }
            return rows.update(QR, id, rows.object().put("status", "DISABLED"));
        });
    }

    // ================================================================== helpers

    private ObjectNode internalPayment(String type, UUID debtor, UUID creditor, BigDecimal amount, String currency) {
        ObjectNode req = rows.object();
        req.put("payment_type", type);
        req.put("direction", "INTERNAL");
        req.put("channel", "API");
        req.put("debtor_account_id", debtor.toString());
        req.put("creditor_account_id", creditor.toString());
        req.put("amount", amount.toPlainString());
        req.put("currency", currency);
        req.put("charge_bearer", "SLEV");
        return req;
    }

    /** Marks the receivable EXPIRED (committed) when its expiry has passed on the database clock. */
    private boolean expired(String table, UUID id, ObjectNode row) {
        if (!row.hasNonNull("expires_at") || !ts(row, "expires_at").isBefore(rows.now())) {
            return false;
        }
        tx.execute(s -> rows.update(table, id, rows.object().put("status", "EXPIRED")));
        return true;
    }

    private String slug() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append(SLUG_CHARS.charAt(random.nextInt(SLUG_CHARS.length())));
        }
        return sb.toString();
    }

    private static String firstNonNull(String... values) {
        for (String v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }
}
