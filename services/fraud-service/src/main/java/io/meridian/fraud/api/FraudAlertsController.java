package io.meridian.fraud.api;

import io.meridian.fraud.api.generated.FraudAlertsApi;
import io.meridian.fraud.api.generated.model.AmlCase;
import io.meridian.fraud.api.generated.model.FraudAlert;
import io.meridian.fraud.api.generated.model.FraudAlertsAssignRequest;
import io.meridian.fraud.api.generated.model.FraudAlertsContactCustomerRequest;
import io.meridian.fraud.api.generated.model.FraudAlertsEscalateRequest;
import io.meridian.fraud.api.generated.model.FraudAlertsResolveRequest;
import io.meridian.fraud.application.FraudAlertService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Analyst actions on fraud alerts. */
@RestController
public class FraudAlertsController implements FraudAlertsApi {
    private final FraudAlertService alerts;
    private final Http http;

    public FraudAlertsController(FraudAlertService alerts, Http http) {
        this.alerts = alerts;
        this.http = http;
    }

    @Override
    public ResponseEntity<FraudAlert> fraudAlertsAssign(UUID id, String idempotencyKey, FraudAlertsAssignRequest body) {
        JsonNode req = http.tree(body);
        return http.once(idempotencyKey, body, FraudAlert.class,
                () -> http.ok(alerts.assign(id, Http.text(req, "assigned_to")), FraudAlert.class));
    }

    @Override
    public ResponseEntity<FraudAlert> fraudAlertsResolve(UUID id, String idempotencyKey, FraudAlertsResolveRequest body) {
        JsonNode req = http.tree(body);
        return http.once(idempotencyKey, body, FraudAlert.class, () -> http.ok(alerts.resolve(id,
                Http.text(req, "status"), Http.text(req, "typology"), Http.text(req, "resolution")), FraudAlert.class));
    }

    @Override
    public ResponseEntity<AmlCase> fraudAlertsEscalate(UUID id, String idempotencyKey, FraudAlertsEscalateRequest body) {
        JsonNode req = http.tree(body);
        return http.once(idempotencyKey, body, AmlCase.class, () -> ResponseEntity.ok(http.read(
                alerts.escalate(id, Http.text(req, "case_type"), Http.text(req, "reason")), AmlCase.class)));
    }

    @Override
    public ResponseEntity<FraudAlert> fraudAlertsContactCustomer(UUID id, String idempotencyKey, FraudAlertsContactCustomerRequest body) {
        JsonNode req = http.tree(body);
        return http.once(idempotencyKey, body, FraudAlert.class,
                () -> http.ok(alerts.contactCustomer(id, Http.text(req, "channel")), FraudAlert.class));
    }
}
