package io.meridian.transactionmonitoring.api;

import io.meridian.transactionmonitoring.api.generated.TmAlertsApi;
import io.meridian.transactionmonitoring.api.generated.model.AmlCase;
import io.meridian.transactionmonitoring.api.generated.model.TmAlert;
import io.meridian.transactionmonitoring.api.generated.model.TmAlertTransactionPage;
import io.meridian.transactionmonitoring.api.generated.model.TmAlertsAssignRequest;
import io.meridian.transactionmonitoring.api.generated.model.TmAlertsCloseRequest;
import io.meridian.transactionmonitoring.api.generated.model.TmAlertsEscalateRequest;
import io.meridian.transactionmonitoring.application.AlertService;
import io.meridian.transactionmonitoring.infrastructure.Db;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** TM alert triage, contributing transactions and escalation to AML cases. */
@RestController
public class TmAlertsController implements TmAlertsApi {
    private final AlertService alerts;
    private final CrudEngine crud;
    private final Http http;

    public TmAlertsController(AlertService alerts, CrudEngine crud, Http http) {
        this.alerts = alerts;
        this.crud = crud;
        this.http = http;
    }

    @Override
    public ResponseEntity<TmAlertTransactionPage> tmAlertsTransactions(UUID id, Integer limit, String cursor) {
        crud.get("tm_alert", Object.class, Crud.params().with("id", id));
        return crud.list("tm_alert_transaction", TmAlertTransactionPage.class,
                Crud.params().with("tm_alert_id", id).with("limit", limit).with("cursor", cursor));
    }

    @Override
    public ResponseEntity<TmAlert> tmAlertsAssign(UUID id, String idempotencyKey, TmAlertsAssignRequest body) {
        String analyst = Http.text(http.tree(body), "assigned_to");
        return http.once(idempotencyKey, body, TmAlert.class, () -> http.ok(alerts.assign(id, analyst), TmAlert.class));
    }

    @Override
    public ResponseEntity<TmAlert> tmAlertsClose(UUID id, String idempotencyKey, TmAlertsCloseRequest body) {
        JsonNode req = http.tree(body);
        return http.once(idempotencyKey, body, TmAlert.class, () -> http.ok(alerts.close(id,
                Http.text(req, "closed_reason"), Http.text(req, "triage_note")), TmAlert.class));
    }

    @Override
    public ResponseEntity<AmlCase> tmAlertsEscalate(UUID id, String idempotencyKey, TmAlertsEscalateRequest body) {
        JsonNode req = http.tree(body);
        return http.once(idempotencyKey, body, AmlCase.class, () -> ResponseEntity.ok(http.read(alerts.escalate(id,
                Db.uuid(req, "aml_case_id"), Http.text(req, "case_type"), Http.text(req, "reason")), AmlCase.class)));
    }
}
