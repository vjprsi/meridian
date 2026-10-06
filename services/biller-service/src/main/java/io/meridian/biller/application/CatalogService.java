package io.meridian.biller.application;

import io.meridian.biller.domain.Lifecycle;
import io.meridian.biller.domain.ReferenceValidator;
import io.meridian.biller.infrastructure.Rows;
import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Biller catalogue: billers, products, status changes, account reference validation and bill inquiries. */
@Service
public class CatalogService {
    static final Set<String> FEE_BEARERS = Set.of("CUSTOMER", "BILLER", "SHARED");

    private final Rows rows;
    private final Events events;
    private final BillerGateway gateway;

    public CatalogService(Rows rows, Events events, BillerGateway gateway) {
        this.rows = rows;
        this.events = events;
        this.gateway = gateway;
    }

    /** Validates a biller definition (create, or the merged result of a PATCH). */
    public static void validateBiller(JsonNode b) {
        String regex = Rows.str(b, "account_reference_regex");
        if (regex != null && !ReferenceValidator.compiles(regex)) {
            throw ApiException.unprocessable("invalid_regex", "account_reference_regex is not a valid regular expression");
        }
        BigDecimal min = Rows.dec(b, "min_amount");
        BigDecimal max = Rows.dec(b, "max_amount");
        if (min != null && min.signum() < 0 || max != null && max.signum() <= 0) {
            throw ApiException.unprocessable("invalid_amount_range", "min_amount must be >= 0 and max_amount > 0");
        }
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw ApiException.unprocessable("invalid_amount_range", "min_amount must be <= max_amount");
        }
        String bearer = Rows.str(b, "fee_bearer");
        if (bearer != null && !FEE_BEARERS.contains(bearer)) {
            throw ApiException.unprocessable("invalid_fee_bearer", "fee_bearer must be one of " + FEE_BEARERS);
        }
        Integer validity = Rows.integer(b, "inquiry_validity_minutes");
        if (validity != null && (validity < 1 || validity > 7 * 24 * 60)) {
            throw ApiException.unprocessable("invalid_inquiry_validity", "inquiry_validity_minutes must be 1-10080");
        }
    }

    @Transactional
    public ObjectNode setStatus(UUID id, String to, String reason) {
        ObjectNode b = rows.lock("biller", id);
        Lifecycle.biller(Rows.str(b, "status"), to);
        ObjectNode v = rows.object();
        v.put("status", to);
        if (reason != null) {
            v.put("status_reason", reason);
        } else {
            v.putNull("status_reason");
        }
        ObjectNode after = rows.update("biller", id, v);
        events.publish("wallets.biller.status_changed", "biller", after);
        return after;
    }

    /** Products: the biller must exist, the currency is the biller's, codes are unique per biller. */
    public static void validateProduct(JsonNode p, JsonNode biller) {
        if (!Rows.str(biller, "currency").equals(Rows.str(p, "currency"))) {
            throw ApiException.unprocessable("currency_mismatch", "A product must be in the biller currency " + Rows.str(biller, "currency"));
        }
        BigDecimal d = Rows.dec(p, "denomination");
        if (d != null && d.signum() <= 0) {
            throw ApiException.unprocessable("invalid_denomination", "denomination must be positive");
        }
        Integer days = Rows.integer(p, "validity_days");
        if (days != null && days <= 0) {
            throw ApiException.unprocessable("invalid_validity", "validity_days must be positive");
        }
    }

    public ObjectNode biller(UUID id) {
        return rows.find("biller", id).orElseThrow(() -> ApiException.unprocessable("biller_not_found", "The biller does not exist"));
    }

    /**
     * Validates the reference and asks the biller for the bill. The inquiry is stored with the biller's response code
     * ("00" found, "14" unknown account) and expires after the biller's inquiry validity (database clock).
     */
    @Transactional
    public ObjectNode inquire(UUID billerId, String reference, boolean requireInquirySupport) {
        ObjectNode biller = biller(billerId);
        if (!"ACTIVE".equals(Rows.str(biller, "status"))) {
            throw ApiException.unprocessable("biller_not_active", "The biller is " + Rows.str(biller, "status"));
        }
        String ref = reference == null ? null : reference.trim();
        ReferenceValidator.validate(ref, Rows.str(biller, "account_reference_regex"),
                Rows.str(biller, "account_reference_checksum")).ifPresent(msg -> {
                    throw ApiException.unprocessable("invalid_reference", msg);
                });
        boolean supports = Rows.bool(biller, "supports_inquiry");
        if (!supports && requireInquirySupport) {
            throw ApiException.unprocessable("inquiry_not_supported", "The biller does not support bill inquiries");
        }
        ObjectNode v = rows.object();
        v.put("biller_id", billerId.toString());
        v.put("account_reference", ref);
        v.put("currency", Rows.str(biller, "currency"));
        if (supports) {
            BillerGateway.Inquiry r = gateway.inquire(biller, ref);
            v.put("response_code", r.responseCode());
            v.put("response_message", r.message());
            if (r.found()) {
                v.put("customer_name", r.customerName());
                v.put("amount_due", r.amountDue().toPlainString());
                v.put("due_date", r.dueDate().toString());
                v.put("inquiry_reference", r.inquiryReference());
            }
            ObjectNode raw = rows.object();
            raw.put("response_code", r.responseCode());
            raw.put("message", r.message());
            raw.put("inquiry_reference", r.inquiryReference());
            v.set("raw_response", raw);
        } else {
            v.put("response_code", "00");
            v.put("response_message", "Reference format valid; the biller does not return bill details");
        }
        v.put("expires_at", rows.scalar("select (now() + make_interval(mins => ?))::text", String.class,
                Rows.integer(biller, "inquiry_validity_minutes")).orElseThrow());
        return rows.insert("bill_inquiry", v);
    }
}
