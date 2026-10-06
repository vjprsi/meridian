package io.meridian.transactionmonitoring.infrastructure;

import io.meridian.web.client.ServiceClient;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** case-management-service calls for TM alert escalation (idempotent per alert). */
@Component
public class Downstream {
    private final ServiceClient client;

    public Downstream(ServiceClient client) {
        this.client = client;
    }

    /** Opens a case for the alert (or uses the given one) and links the alert; returns the case. */
    public JsonNode escalate(JsonNode alert, UUID existingCase, String caseType, String reason, String scenarioCode) {
        String alertId = alert.get("id").asString();
        JsonNode amlCase;
        if (existingCase != null) {
            amlCase = client.get("case-management-service", "/v1/risk/cases/" + existingCase);
        } else {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("case_type", caseType == null ? "AML" : caseType);
            body.put("priority", alert.get("severity").asString());
            body.put("customer_id", alert.get("customer_id").asString());
            body.put("title", "TM alert " + scenarioCode + " for customer " + alert.get("customer_id").asString());
            body.put("description", reason);
            body.put("total_amount", alert.get("total_amount").asString());
            body.put("currency", alert.get("currency").asString().trim());
            body.put("risk_score", alert.get("score").asInt());
            amlCase = client.post("case-management-service", "/v1/risk/cases", body, "tm-alert-case:" + alertId);
        }
        Map<String, Object> link = new LinkedHashMap<>();
        link.put("source", "TM_ALERT");
        link.put("alert_id", alertId);
        String summary = scenarioCode + ": " + alert.get("transaction_count").asInt() + " transactions, "
                + alert.get("total_amount").asString() + " " + alert.get("currency").asString().trim();
        link.put("summary", summary.length() > 500 ? summary.substring(0, 500) : summary);
        link.put("added_reason", reason.length() > 255 ? reason.substring(0, 255) : reason);
        client.post("case-management-service", "/v1/risk/cases/" + amlCase.get("id").asString() + "/alerts", link,
                "tm-alert-link:" + alertId);
        return amlCase;
    }
}
