package io.meridian.fraud.infrastructure;

import io.meridian.web.client.ServiceClient;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Calls to case-management-service and notification-service (idempotent keys derived from the alert id). */
@Component
public class Downstream {
    private final ServiceClient client;

    public Downstream(ServiceClient client) {
        this.client = client;
    }

    /** Opens an AML / fraud case for the alert and links the alert to it; returns the case. */
    public JsonNode openCase(JsonNode alert, String caseType, String reason) {
        String alertId = alert.get("id").asString();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("case_type", caseType);
        body.put("title", "Fraud alert " + alertId + " (" + alert.path("subject_type").asString() + ")");
        body.put("description", reason);
        body.put("priority", alert.path("severity").asString("MEDIUM"));
        put(body, "customer_id", Db.str(alert, "customer_id"));
        put(body, "total_amount", Db.str(alert, "amount"));
        put(body, "currency", Db.str(alert, "currency"));
        JsonNode amlCase = client.post("case-management-service", "/v1/risk/cases", body, "fraud-alert-case:" + alertId);
        String caseId = amlCase.get("id").asString();
        Map<String, Object> link = new LinkedHashMap<>();
        link.put("source", "FRAUD_ALERT");
        link.put("alert_id", alertId);
        link.put("summary", cut("Fraud alert (" + alert.path("severity").asString() + "), reasons "
                + alert.path("reason_codes").toString(), 500));
        link.put("added_reason", cut(reason, 255));
        client.post("case-management-service", "/v1/risk/cases/" + caseId + "/alerts", link, "fraud-alert-link:" + alertId);
        return amlCase;
    }

    /** Sends the confirm-or-deny message; returns the notification id. */
    public UUID contactCustomer(JsonNode alert, String channel) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("alert_id", alert.get("id").asString());
        put(vars, "amount", Db.str(alert, "amount"));
        put(vars, "currency", Db.str(alert, "currency"));
        vars.put("subject_type", alert.path("subject_type").asString());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("template_code", "FRAUD_TRANSACTION_CONFIRMATION");
        body.put("recipient_type", "CUSTOMER");
        body.put("recipient_id", alert.get("customer_id").asString());
        body.put("channel", channel);
        body.put("variables", vars);
        JsonNode n = client.post("notification-service", "/v1/notifications/send-templated", body,
                "fraud-contact:" + alert.get("id").asString() + ":" + channel);
        return n.hasNonNull("id") ? UUID.fromString(n.get("id").asString()) : null;
    }

    private static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static void put(Map<String, Object> m, String k, String v) {
        if (v != null) {
            m.put(k, v);
        }
    }
}
