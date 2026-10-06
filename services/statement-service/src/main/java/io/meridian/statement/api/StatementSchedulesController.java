package io.meridian.statement.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.statement.api.generated.StatementSchedulesApi;
import io.meridian.statement.api.generated.model.StatementSchedule;
import io.meridian.statement.api.generated.model.StatementScheduleCreate;
import io.meridian.statement.api.generated.model.StatementScheduleUpdate;
import io.meridian.statement.application.ScheduleService;
import io.meridian.statement.infrastructure.Rows;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

/** Statement schedules: generic CRUD with validated formats, cycle day and delivery channel requirements. */
@RestController
public class StatementSchedulesController implements StatementSchedulesApi {
    private final ScheduleService schedules;
    private final CrudEngine crud;
    private final Rows rows;

    public StatementSchedulesController(ScheduleService schedules, CrudEngine crud, Rows rows) {
        this.schedules = schedules;
        this.crud = crud;
        this.rows = rows;
    }

    @Override
    public ResponseEntity<StatementSchedule> statementSchedulesCreate(String idempotencyKey, StatementScheduleCreate body) {
        TenantContext.require();
        schedules.validate(rows.columns("statement_schedule", body), true);
        return crud.create("statement_schedule", StatementSchedule.class, body, idempotencyKey, Crud.params());
    }

    @Override
    public ResponseEntity<StatementSchedule> statementSchedulesUpdate(UUID id, String ifMatch, StatementScheduleUpdate body) {
        TenantContext.require();
        ObjectNode merged = rows.get("statement_schedule", id);
        ObjectNode changes = rows.columns("statement_schedule", body);
        if (changes.has("deposit_account_id") && !changes.get("deposit_account_id").asString()
                .equals(merged.get("deposit_account_id").asString())) {
            throw io.meridian.common.error.ApiException.unprocessable("field_not_updatable",
                    "deposit_account_id cannot change; create a schedule for the other account");
        }
        merged.setAll(changes);
        schedules.validate(merged, false);
        return crud.update("statement_schedule", StatementSchedule.class, body, ifMatch, Crud.params().with("id", id));
    }
}
