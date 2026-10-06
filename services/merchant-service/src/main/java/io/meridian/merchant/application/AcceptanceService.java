package io.meridian.merchant.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.merchant.config.MerchantProperties;
import io.meridian.merchant.domain.EmvQr;
import io.meridian.merchant.domain.MerchantRules;
import io.meridian.merchant.infrastructure.Rows;
import io.meridian.merchant.infrastructure.WalletClient;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Payment acceptance: EMVCo QR codes (static / dynamic) and payment links. A payment is a MERCHANT_PAYMENT wallet
 * transaction from the payer to the merchant's collection wallet; the QR / link row is locked while paying so a
 * dynamic QR or a limited link can never be used twice.
 */
@Service
public class AcceptanceService {
    private final Rows rows;
    private final WalletClient wallets;
    private final Events events;
    private final TransactionTemplate tx;
    private final MerchantProperties props;

    public AcceptanceService(Rows rows, WalletClient wallets, Events events, TransactionTemplate tx,
                             MerchantProperties props) {
        this.rows = rows;
        this.wallets = wallets;
        this.events = events;
        this.tx = tx;
        this.props = props;
    }

    // ------------------------------------------------------------------ QR codes

    public ObjectNode createQr(JsonNode req) {
        return tx.execute(s -> {
            ObjectNode v = rows.columns("merchant_qr", req);
            ObjectNode m = activeMerchant(Rows.uuid(v, "merchant_id"));
            String currency = Rows.str(v, "currency");
            if (!currency.equals(Rows.str(m, "currency"))) {
                throw ApiException.unprocessable("currency_mismatch", "The merchant accepts " + Rows.str(m, "currency"));
            }
            boolean dynamic = "DYNAMIC".equals(Rows.str(v, "qr_type"));
            BigDecimal amount = Rows.dec(v, "amount");
            if (dynamic && (amount == null || amount.signum() <= 0)) {
                throw ApiException.unprocessable("amount_required", "A dynamic QR needs a positive amount");
            }
            if (!dynamic && amount != null) {
                throw ApiException.unprocessable("static_qr_amount", "A static QR is for any amount; omit amount");
            }
            if (amount != null && amount.stripTrailingZeros().scale() > 2) {
                throw ApiException.unprocessable("invalid_amount", "amount has at most 2 decimals");
            }
            ObjectNode store = null;
            if (v.hasNonNull("merchant_store_id")) {
                store = rows.find("merchant_store", Rows.uuid(v, "merchant_store_id")).filter(x ->
                        Rows.str(x, "merchant_id").equals(Rows.str(m, "id"))).orElseThrow(() ->
                        ApiException.unprocessable("store_mismatch", "The store does not belong to the merchant"));
            }
            String terminalId = null;
            if (v.hasNonNull("merchant_terminal_id")) {
                ObjectNode t = rows.find("merchant_terminal", Rows.uuid(v, "merchant_terminal_id")).filter(x ->
                        Rows.str(x, "merchant_id").equals(Rows.str(m, "id"))).orElseThrow(() ->
                        ApiException.unprocessable("terminal_mismatch", "The terminal does not belong to the merchant"));
                if (!"ACTIVE".equals(Rows.str(t, "status"))) {
                    throw ApiException.unprocessable("terminal_not_active", "The terminal is " + Rows.str(t, "status"));
                }
                terminalId = Rows.str(t, "terminal_id");
            }
            if (dynamic) {
                if (v.hasNonNull("expires_at")) {
                    if (rows.scalar("select ?::timestamptz <= now()", Boolean.class, Rows.str(v, "expires_at")).orElse(true)) {
                        throw ApiException.unprocessable("invalid_expiry", "expires_at must be in the future");
                    }
                } else {
                    v.put("expires_at", rows.scalar("select (now() + make_interval(mins => ?))::text", String.class,
                            props.dynamicQrMinutes()).orElseThrow());
                }
            }
            UUID id = Ids.newId();
            String mcc = store != null && store.hasNonNull("mcc") ? Rows.str(store, "mcc") : Rows.str(m, "mcc");
            String city = store != null && store.hasNonNull("city") ? Rows.str(store, "city") : Rows.str(m, "city");
            v.put("id", id.toString());
            v.put("payload", EmvQr.encode(new EmvQr.Fields(dynamic, Rows.str(m, "merchant_code"), id.toString(), mcc,
                    currency, amount, Rows.str(m, "country"), Rows.str(m, "trading_name"), city, Rows.str(v, "reference"),
                    terminalId)));
            v.put("is_active", true);
            return rows.insert("merchant_qr", v);
        });
    }

    public ObjectNode deactivateQr(UUID id) {
        return tx.execute(s -> {
            ObjectNode q = rows.lock("merchant_qr", id);
            if (!Rows.bool(q, "is_active")) {
                return q;
            }
            ObjectNode v = rows.object();
            v.put("is_active", false);
            return rows.update("merchant_qr", id, v);
        });
    }

    /** Decodes a scanned payload; never fails on bad input (crc_valid / payable / reason explain the outcome). */
    public ObjectNode decode(String payload) {
        ObjectNode out = rows.object();
        String p = payload == null ? "" : payload.trim();
        out.put("crc_valid", EmvQr.crcValid(p));
        EmvQr.Fields f;
        try {
            f = EmvQr.decode(p);
        } catch (EmvQr.MalformedQr e) {
            out.put("payable", false);
            out.put("reason", "malformed: " + e.getMessage());
            return out;
        }
        ObjectNode tags = out.putObject("tags");
        EmvQr.parse(p).forEach(tags::put);
        out.put("payload_format", "01");
        out.put("point_of_initiation", f.dynamic() ? "12" : "11");
        out.put("merchant_code", f.merchantCode());
        out.put("mcc", f.mcc());
        out.put("country", f.country());
        out.put("currency", f.currency());
        out.put("amount", f.amount() == null ? null : f.amount().toPlainString());
        out.put("merchant_name", f.name());
        out.put("merchant_city", f.city());
        out.put("reference", f.reference());
        String reason = null;
        Optional<ObjectNode> qr = Optional.empty();
        if (!out.get("crc_valid").asBoolean()) {
            reason = "crc_invalid";
        } else {
            qr = resolve(f);
            if (qr.isEmpty()) {
                reason = "qr_not_found";
            }
        }
        if (qr.isPresent()) {
            ObjectNode q = qr.get();
            out.put("merchant_qr_id", Rows.str(q, "id"));
            out.put("merchant_id", Rows.str(q, "merchant_id"));
            reason = payableReason(q, p);
        }
        out.put("payable", reason == null);
        out.put("reason", reason);
        return out;
    }

    private Optional<ObjectNode> resolve(EmvQr.Fields f) {
        if (f.qrId() == null) {
            return Optional.empty();
        }
        try {
            return rows.find("merchant_qr", UUID.fromString(f.qrId()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Why a stored QR cannot be paid now (null = payable). The presented payload must be the issued one. */
    private String payableReason(ObjectNode q, String presented) {
        if (!presented.equals(Rows.str(q, "payload"))) {
            return "payload_mismatch";
        }
        if (!Rows.bool(q, "is_active")) {
            return "DYNAMIC".equals(Rows.str(q, "qr_type")) && Rows.integer(q, "scan_count") > 0
                    ? "qr_already_used" : "qr_inactive";
        }
        if (q.hasNonNull("expires_at") && rows.scalar("select expires_at < now() from merchant_qr where id = ?",
                Boolean.class, Rows.uuid(q, "id")).orElse(false)) {
            return "qr_expired";
        }
        ObjectNode m = rows.get("merchant", Rows.uuid(q, "merchant_id"));
        if (!"ACTIVE".equals(Rows.str(m, "status"))) {
            return "merchant_not_active";
        }
        return null;
    }

    public ObjectNode payQr(JsonNode req, String key) {
        String payload = Rows.str(req, "payload") == null ? "" : Rows.str(req, "payload").trim();
        if (!EmvQr.crcValid(payload)) {
            throw ApiException.unprocessable("invalid_qr_crc", "The QR code checksum is invalid");
        }
        EmvQr.Fields f;
        try {
            f = EmvQr.decode(payload);
        } catch (EmvQr.MalformedQr e) {
            throw ApiException.unprocessable("invalid_qr", e.getMessage());
        }
        UUID qrId = resolve(f).map(q -> Rows.uuid(q, "id")).orElseThrow(() ->
                ApiException.unprocessable("qr_not_found", "The QR code was not issued by this platform"));
        return tx.execute(s -> {
            ObjectNode q = rows.lock("merchant_qr", qrId);
            String reason = payableReason(q, payload);
            if (reason != null) {
                throw "qr_already_used".equals(reason) || "qr_inactive".equals(reason)
                        ? ApiException.conflict(reason, "The QR code cannot be paid (" + reason + ")")
                        : ApiException.unprocessable(reason, "The QR code cannot be paid (" + reason + ")");
            }
            boolean dynamic = "DYNAMIC".equals(Rows.str(q, "qr_type"));
            BigDecimal amount = amount(req, Rows.dec(q, "amount"), dynamic);
            requireCurrency(req, Rows.str(q, "currency"));
            ObjectNode m = rows.get("merchant", Rows.uuid(q, "merchant_id"));
            ObjectNode extra = rows.object();
            extra.put("description", "QR payment to " + Rows.str(m, "trading_name")
                    + (q.hasNonNull("reference") ? " ref " + Rows.str(q, "reference") : ""));
            extra.putObject("metadata").put("merchant_qr_id", qrId.toString());
            JsonNode txn = pay(m, req, amount, Rows.str(q, "currency"), channel(req, "QR"), "mqr-pay-" + key, extra);
            ObjectNode v = rows.object();
            v.put("scan_count", Rows.integer(q, "scan_count") + 1);
            if (dynamic) {
                v.put("is_active", false);
            }
            rows.update("merchant_qr", qrId, v);
            rows.execute("update merchant_qr set last_paid_at = now() where id = ?", qrId);
            events.publish("wallets.merchant_qr.paid", "merchant_qr", rows.get("merchant_qr", qrId));
            return (ObjectNode) txn;
        });
    }

    // ------------------------------------------------------------------ payment links

    public ObjectNode createLink(JsonNode req) {
        return tx.execute(s -> {
            ObjectNode v = rows.columns("merchant_payment_link", req);
            ObjectNode m = activeMerchant(Rows.uuid(v, "merchant_id"));
            if (!Rows.str(v, "currency").equals(Rows.str(m, "currency"))) {
                throw ApiException.unprocessable("currency_mismatch", "The merchant accepts " + Rows.str(m, "currency"));
            }
            BigDecimal amount = Rows.dec(v, "amount");
            if (amount != null && (amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2)) {
                throw ApiException.unprocessable("invalid_amount", "amount must be positive with at most 2 decimals");
            }
            if (v.hasNonNull("max_uses") && Rows.integer(v, "max_uses") < 1) {
                throw ApiException.unprocessable("invalid_max_uses", "max_uses must be at least 1");
            }
            if (v.hasNonNull("expires_at")
                    && rows.scalar("select ?::timestamptz <= now()", Boolean.class, Rows.str(v, "expires_at")).orElse(true)) {
                throw ApiException.unprocessable("invalid_expiry", "expires_at must be in the future");
            }
            String code;
            do {
                code = MerchantRules.linkCode();
            } while (rows.scalar("select count(*) from merchant_payment_link where link_code = ?", Long.class, code)
                    .orElse(0L) > 0);
            v.put("link_code", code);
            String base = props.paymentLinkBaseUrl();
            v.put("url", (base.endsWith("/") ? base : base + "/") + code);
            v.put("is_active", true);
            return rows.insert("merchant_payment_link", v);
        });
    }

    public ObjectNode deactivateLink(UUID id) {
        return tx.execute(s -> {
            ObjectNode l = rows.lock("merchant_payment_link", id);
            if (!Rows.bool(l, "is_active")) {
                return l;
            }
            ObjectNode v = rows.object();
            v.put("is_active", false);
            return rows.update("merchant_payment_link", id, v);
        });
    }

    public ObjectNode payLink(UUID id, JsonNode req, String key) {
        return tx.execute(s -> {
            ObjectNode l = rows.lock("merchant_payment_link", id);
            if (!Rows.bool(l, "is_active")) {
                throw ApiException.conflict("link_inactive", "The payment link is no longer active");
            }
            if (l.hasNonNull("expires_at") && rows.scalar("select expires_at < now() from merchant_payment_link where id = ?",
                    Boolean.class, id).orElse(false)) {
                throw ApiException.unprocessable("link_expired", "The payment link has expired");
            }
            Integer max = Rows.integer(l, "max_uses");
            int used = Rows.integer(l, "use_count");
            if (max != null && used >= max) {
                throw ApiException.conflict("link_exhausted", "The payment link reached its maximum number of payments");
            }
            ObjectNode m = rows.get("merchant", Rows.uuid(l, "merchant_id"));
            if (!"ACTIVE".equals(Rows.str(m, "status"))) {
                throw ApiException.unprocessable("merchant_not_active", "The merchant is " + Rows.str(m, "status"));
            }
            BigDecimal fixed = Rows.dec(l, "amount");
            BigDecimal amount = amount(req, fixed, fixed != null);
            ObjectNode extra = rows.object();
            extra.put("description", l.hasNonNull("description") ? Rows.str(l, "description")
                    : "Payment link " + Rows.str(l, "link_code"));
            if (l.hasNonNull("reference")) {
                extra.put("payee_external_ref", Rows.str(l, "reference"));
            }
            extra.putObject("metadata").put("merchant_payment_link_id", id.toString());
            JsonNode txn = pay(m, req, amount, Rows.str(l, "currency"), channel(req, "WEB"), "mpl-pay-" + key, extra);
            ObjectNode v = rows.object();
            v.put("use_count", used + 1);
            if (max != null && used + 1 >= max) {
                v.put("is_active", false);
            }
            ObjectNode after = rows.update("merchant_payment_link", id, v);
            events.publish("wallets.merchant_payment_link.paid", "merchant_payment_link", after);
            return (ObjectNode) txn;
        });
    }

    // ------------------------------------------------------------------ shared

    /** Daily limit check, then the MERCHANT_PAYMENT wallet transaction into the merchant's collection wallet. */
    private JsonNode pay(ObjectNode m, JsonNode req, BigDecimal amount, String currency, String channel, String key,
                         ObjectNode extra) {
        UUID merchantId = Rows.uuid(m, "id");
        if (!m.hasNonNull("settlement_wallet_id")) {
            throw ApiException.unprocessable("merchant_not_active", "The merchant has no collection wallet");
        }
        if (m.hasNonNull("daily_limit")) {
            String dayStart = rows.scalar("select (date_trunc('day', now() at time zone 'UTC') at time zone 'UTC')::text",
                    String.class).orElseThrow().replace(' ', 'T');
            BigDecimal today = wallets.collectedSince(merchantId, currency, dayStart);
            if (today.add(amount).compareTo(Rows.dec(m, "daily_limit")) > 0) {
                throw ApiException.unprocessable("merchant_daily_limit_exceeded",
                        "The merchant's daily collection limit would be exceeded");
            }
        }
        ObjectNode body = wallets.txn("MERCHANT_PAYMENT", channel, "CUSTOMER", amount, currency);
        body.put("payer_wallet_id", Rows.str(req, "payer_wallet_id"));
        body.put("payee_wallet_id", Rows.str(m, "settlement_wallet_id"));
        body.put("merchant_id", merchantId.toString());
        if (Rows.str(req, "pin") != null) {
            body.put("pin", Rows.str(req, "pin"));
        }
        body.setAll(extra);
        return wallets.transact(body, key);
    }

    private static BigDecimal amount(JsonNode req, BigDecimal fixed, boolean isFixed) {
        BigDecimal given = Rows.dec(req, "amount");
        if (isFixed) {
            if (given != null && given.compareTo(fixed) != 0) {
                throw ApiException.unprocessable("amount_mismatch", "The amount is fixed at " + fixed.toPlainString());
            }
            return fixed;
        }
        if (given == null || given.signum() <= 0 || given.stripTrailingZeros().scale() > 2) {
            throw ApiException.unprocessable("amount_required", "Enter a positive amount with at most 2 decimals");
        }
        return given;
    }

    private static void requireCurrency(JsonNode req, String currency) {
        String given = Rows.str(req, "currency");
        if (given != null && !given.equals(currency)) {
            throw ApiException.unprocessable("currency_mismatch", "The QR code is in " + currency);
        }
    }

    private static String channel(JsonNode req, String dflt) {
        String c = Rows.str(req, "channel");
        return c == null ? dflt : c;
    }

    private ObjectNode activeMerchant(UUID id) {
        if (id == null) {
            throw ApiException.unprocessable("merchant_required", "merchant_id is required");
        }
        ObjectNode m = rows.find("merchant", id).orElseThrow(() ->
                ApiException.unprocessable("merchant_not_found", "The merchant does not exist"));
        if (!"ACTIVE".equals(Rows.str(m, "status"))) {
            throw ApiException.unprocessable("merchant_not_active", "The merchant is " + Rows.str(m, "status"));
        }
        return m;
    }
}
