package io.meridian.collateral.api;

import io.meridian.collateral.api.generated.CollateralsApi;
import io.meridian.collateral.api.generated.model.Collateral;
import io.meridian.collateral.api.generated.model.CollateralCreate;
import io.meridian.collateral.api.generated.model.CollateralUpdate;
import io.meridian.collateral.api.generated.model.CollateralsLiquidateRequest;
import io.meridian.collateral.api.generated.model.CollateralsPledgeRequest;
import io.meridian.collateral.api.generated.model.CollateralsRejectRequest;
import io.meridian.collateral.api.generated.model.CollateralsReleaseRequest;
import io.meridian.collateral.application.CollateralService;
import io.meridian.collateral.application.LinkService;
import io.meridian.collateral.application.MonitoringJobs;
import io.meridian.common.tenancy.TenantContext;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Collateral registry: registration with type-specific validation, lifecycle, liquidation, LTV, EOD monitoring. */
@RestController
public class CollateralsController implements CollateralsApi {
    private final CollateralService service;
    private final LinkService links;
    private final MonitoringJobs monitoring;
    private final Actions actions;

    public CollateralsController(CollateralService service, LinkService links, MonitoringJobs monitoring, Actions actions) {
        this.service = service;
        this.links = links;
        this.monitoring = monitoring;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<Collateral> collateralsCreate(String idempotencyKey, CollateralCreate body) {
        return actions.create(idempotencyKey, body, Collateral.class, () -> service.register(body));
    }

    @Override
    public ResponseEntity<Collateral> collateralsUpdate(UUID id, String ifMatch, CollateralUpdate body) {
        TenantContext.require();
        return ResponseEntity.ok(actions.model(service.update(id, ifMatch, body), Collateral.class));
    }

    @Override
    public ResponseEntity<Collateral> collateralsVerify(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "verify", null, Collateral.class, () -> service.verify(id));
    }

    @Override
    public ResponseEntity<Collateral> collateralsReject(UUID id, String idempotencyKey, CollateralsRejectRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "reject", body, Collateral.class,
                () -> service.reject(id, Http.text(req, "reason")));
    }

    @Override
    public ResponseEntity<Collateral> collateralsPledge(UUID id, String idempotencyKey, CollateralsPledgeRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "pledge", body, Collateral.class,
                () -> service.pledge(id, Http.text(req, "registration_reference"),
                        LocalDate.parse(Http.text(req, "lien_registered_at"))));
    }

    @Override
    public ResponseEntity<Collateral> collateralsRelease(UUID id, String idempotencyKey, CollateralsReleaseRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "release", body, Collateral.class,
                () -> service.release(id, Http.text(req, "reason"), req.path("force").asBoolean(false)));
    }

    @Override
    public ResponseEntity<Map<String, Object>> collateralsLiquidate(UUID id, String idempotencyKey,
                                                                    CollateralsLiquidateRequest body) {
        JsonNode req = actions.tree(body);
        return actions.objectAction(idempotencyKey, "liquidate:" + id, body, () -> service.liquidate(id,
                Http.decimal(req, "proceeds"), LocalDate.parse(Http.text(req, "sale_date")), Http.decimal(req, "costs"),
                Http.text(req, "reference")));
    }

    @Override
    public ResponseEntity<Map<String, Object>> collateralsLtv(UUID id) {
        TenantContext.require();
        return actions.object(links.report(id));
    }

    @Override
    public ResponseEntity<Map<String, Object>> collateralsEodMonitor(LocalDate businessDate, String idempotencyKey) {
        return actions.objectAction(idempotencyKey, "eod-monitor", Map.of("business_date", String.valueOf(businessDate)),
                () -> monitoring.run(businessDate));
    }
}
