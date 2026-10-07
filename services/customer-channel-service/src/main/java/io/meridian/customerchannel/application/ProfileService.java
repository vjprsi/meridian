package io.meridian.customerchannel.application;

import static io.meridian.customerchannel.application.Ownership.me;
import static io.meridian.customerchannel.application.Ownership.mine;

import io.meridian.common.error.ApiException;
import io.meridian.customerchannel.application.Downstream.Query;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Profile, contact points, addresses, devices and notification preferences of the signed-in customer. */
@Service
public class ProfileService {
    private static final String CUSTOMERS = "customer-service";
    private static final String NOTIFY = "notification-service";

    private final Downstream down;
    private final Ownership own;
    private final StepUp stepUp;
    private final ActionAudit audit;

    public ProfileService(Downstream down, Ownership own, StepUp stepUp, ActionAudit audit) {
        this.down = down;
        this.own = own;
        this.stepUp = stepUp;
        this.audit = audit;
    }

    private String base() {
        return "/v1/customers/" + me();
    }

    public JsonNode profile() {
        JsonNode c = down.get(CUSTOMERS, base());
        if (!me().toString().equals(c.path("id").asString(null))) {
            throw Ownership.notFound("CUSTOMER", me());
        }
        return c;
    }

    /** Only allow-listed self-service fields; the customer record's row_version guards concurrent changes. */
    public JsonNode updateProfile(JsonNode req) {
        String language = text(req, "preferred_language");
        if (language == null || !language.matches("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*")) {
            throw ApiException.unprocessable("invalid_language", "preferred_language must be a BCP 47 language tag");
        }
        JsonNode current = profile();
        ObjectNode patch = down.json().createObjectNode().put("preferred_language", language);
        JsonNode updated = down.patch(CUSTOMERS, base(), patch, current.path("row_version").asInt());
        audit.succeeded("myProfileUpdate", "CUSTOMER", me(), null);
        return updated;
    }

    // ------------------------------------------------------------------ contacts

    public JsonNode contacts(Integer limit, String cursor) {
        return down.page(down.get(CUSTOMERS, base() + "/contacts", Query.none().paging(limit, cursor)),
                r -> mine(r, "customer_id"));
    }

    public JsonNode addContact(JsonNode req, String key) {
        stepUp.require("myContactsCreate", "CONTACT", null);
        String type = text(req, "contact_type");
        ObjectNode body = down.json().createObjectNode().put("contact_type", type);
        if ("EMAIL".equals(type)) {
            body.put("email", required(req, "email"));
        } else {
            body.put("phone", required(req, "phone"));
        }
        body.put("label", text(req, "label"));
        body.put("is_primary", false);                 // primary changes go through servicing
        body.put("can_receive_otp", true);
        body.put("can_receive_marketing", false);
        JsonNode created = down.post(CUSTOMERS, base() + "/contacts", body, key, "create");
        audit.succeeded("myContactsCreate", "CONTACT", null, created.path("id").asString(null));
        return created;
    }

    public JsonNode sendVerification(UUID contactId, String key) {
        own.contact(contactId, "myContactsSendVerification");
        return down.post(CUSTOMERS, base() + "/contacts/" + contactId + "/send-verification", null, key, "send");
    }

    public JsonNode verifyContact(UUID contactId, JsonNode req, String key) {
        own.contact(contactId, "myContactsVerify");
        JsonNode r = down.post(CUSTOMERS, base() + "/contacts/" + contactId + "/verify",
                down.json().createObjectNode().put("code", required(req, "code")), key, "verify");
        audit.succeeded("myContactsVerify", "CONTACT", contactId, null);
        return r;
    }

    public JsonNode addresses(Integer limit, String cursor) {
        return down.page(down.get(CUSTOMERS, base() + "/addresses", Query.none().paging(limit, cursor)),
                r -> mine(r, "customer_id"));
    }

    // ------------------------------------------------------------------ devices

    public JsonNode devices(Integer limit, String cursor) {
        return down.page(down.get(CUSTOMERS, base() + "/devices", Query.none().paging(limit, cursor)),
                r -> mine(r, "customer_id"));
    }

    public JsonNode revokeDevice(UUID deviceId, JsonNode req, String key) {
        own.device(deviceId, "myDevicesRevoke");
        stepUp.require("myDevicesRevoke", "DEVICE", deviceId);
        String reason = text(req, "reason");
        JsonNode r = down.post(CUSTOMERS, base() + "/devices/" + deviceId + "/revoke", down.json().createObjectNode()
                .put("reason", reason == null || reason.isBlank() ? "Revoked by the customer" : reason), key, "revoke");
        audit.succeeded("myDevicesRevoke", "DEVICE", deviceId, null);
        return r;
    }

    // ------------------------------------------------------------------ notification preferences

    private Query recipient() {
        return Query.of("recipient_type", "CUSTOMER").with("recipient_id", me());
    }

    private static boolean isMyPreference(JsonNode r) {
        return "CUSTOMER".equals(r.path("recipient_type").asString(null)) && mine(r, "recipient_id");
    }

    public JsonNode preferences(Integer limit, String cursor) {
        return down.page(down.get(NOTIFY, "/v1/notification-preferences", recipient().paging(limit, cursor)),
                ProfileService::isMyPreference);
    }

    /** Creates or updates the customer's preference for one category and channel. */
    public JsonNode setPreference(JsonNode req, String key) {
        String category = required(req, "category");
        String channel = required(req, "channel");
        boolean enabled = req.path("is_enabled").asBoolean(true);
        JsonNode existing = null;
        for (JsonNode p : down.get(NOTIFY, "/v1/notification-preferences",
                recipient().with("category", category).with("channel", channel).with("limit", 10)).path("data")) {
            if (isMyPreference(p) && category.equals(p.path("category").asString(null))
                    && channel.equals(p.path("channel").asString(null))) {
                existing = p;
                break;
            }
        }
        JsonNode result;
        if (existing != null) {
            result = down.patch(NOTIFY, "/v1/notification-preferences/" + existing.path("id").asString(),
                    down.json().createObjectNode().put("is_enabled", enabled), existing.path("row_version").asInt());
        } else {
            result = down.post(NOTIFY, "/v1/notification-preferences", down.json().createObjectNode()
                    .put("recipient_type", "CUSTOMER").put("recipient_id", me().toString())
                    .put("category", category).put("channel", channel).put("is_enabled", enabled), key, "create");
        }
        audit.succeeded("myNotificationPreferencesReplace", "NOTIFICATION_PREFERENCE", result.path("id").asString(null),
                null);
        return result;
    }

    // ------------------------------------------------------------------ helpers

    static String text(JsonNode n, String f) {
        JsonNode v = n == null ? null : n.get(f);
        return v == null || v.isNull() ? null : v.asString();
    }

    static String required(JsonNode n, String f) {
        String v = text(n, f);
        if (v == null || v.isBlank()) {
            throw ApiException.unprocessable("missing_field", f + " is required");
        }
        return v.trim();
    }
}
