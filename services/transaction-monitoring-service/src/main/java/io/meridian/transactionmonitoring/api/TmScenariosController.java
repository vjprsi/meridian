package io.meridian.transactionmonitoring.api;

import io.meridian.transactionmonitoring.api.generated.TmScenariosApi;
import io.meridian.transactionmonitoring.api.generated.model.TmRun;
import io.meridian.transactionmonitoring.api.generated.model.TmScenario;
import io.meridian.transactionmonitoring.api.generated.model.TmScenarioVersion;
import io.meridian.transactionmonitoring.api.generated.model.TmScenarioVersionPage;
import io.meridian.transactionmonitoring.api.generated.model.TmScenariosActivateRequest;
import io.meridian.transactionmonitoring.api.generated.model.TmScenariosBacktestRequest;
import io.meridian.transactionmonitoring.api.generated.model.TmScenariosRetireRequest;
import io.meridian.transactionmonitoring.api.generated.model.TmScenariosAddVersionsRequest;
import io.meridian.transactionmonitoring.application.RunService;
import io.meridian.transactionmonitoring.application.ScenarioService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Monitoring scenarios: threshold versions, maker-checker activation, retirement and backtests. */
@RestController
public class TmScenariosController implements TmScenariosApi {
    private final ScenarioService scenarios;
    private final RunService runs;
    private final CrudEngine crud;
    private final Http http;

    public TmScenariosController(ScenarioService scenarios, RunService runs, CrudEngine crud, Http http) {
        this.scenarios = scenarios;
        this.runs = runs;
        this.crud = crud;
        this.http = http;
    }

    @Override
    public ResponseEntity<TmScenarioVersionPage> tmScenariosVersions(UUID id, Integer limit, String cursor) {
        crud.get("tm_scenario", Object.class, Crud.params().with("id", id));
        return crud.list("tm_scenario_version", TmScenarioVersionPage.class,
                Crud.params().with("tm_scenario_id", id).with("limit", limit).with("cursor", cursor));
    }

    @Override
    public ResponseEntity<TmScenarioVersion> tmScenariosAddVersions(UUID id, String idempotencyKey, TmScenariosAddVersionsRequest body) {
        return http.once(idempotencyKey, body, TmScenarioVersion.class,
                () -> http.ok(scenarios.addVersion(id, http.tree(body)), TmScenarioVersion.class));
    }

    @Override
    public ResponseEntity<TmScenario> tmScenariosActivate(UUID id, String idempotencyKey, TmScenariosActivateRequest body) {
        int version = http.tree(body).get("scenario_version").asInt();
        return http.once(idempotencyKey, body, TmScenario.class, () -> http.ok(scenarios.activate(id, version), TmScenario.class));
    }

    @Override
    public ResponseEntity<TmScenario> tmScenariosRetire(UUID id, String idempotencyKey, TmScenariosRetireRequest body) {
        String reason = Http.text(http.tree(body), "reason");
        return http.once(idempotencyKey, body, TmScenario.class, () -> http.ok(scenarios.retire(id, reason), TmScenario.class));
    }

    @Override
    public ResponseEntity<TmRun> tmScenariosBacktest(UUID id, String idempotencyKey, TmScenariosBacktestRequest body) {
        JsonNode req = http.tree(body);
        return http.once(idempotencyKey, body, TmRun.class, () -> http.ok(runs.backtest(id, req.get("scenario_version").asInt(),
                Http.text(req, "from_ts"), Http.text(req, "to_ts")), TmRun.class));
    }
}
