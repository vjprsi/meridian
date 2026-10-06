package io.meridian.routing.api;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.routing.api.generated.PaymentRailsApi;
import io.meridian.routing.api.generated.model.PaymentRail;
import io.meridian.routing.api.generated.model.PaymentRailCreate;
import io.meridian.routing.api.generated.model.PaymentRailUpdate;
import io.meridian.routing.api.generated.model.PaymentRailsInboundRequest;
import io.meridian.routing.api.generated.model.PaymentRailsMetricsRequest;
import io.meridian.routing.api.generated.model.PaymentRailsResumeRequest;
import io.meridian.routing.api.generated.model.PaymentRailsSuspendRequest;
import io.meridian.routing.api.generated.model.RailCutoffPage;
import io.meridian.routing.api.generated.model.RailMessage;
import io.meridian.routing.application.InboundService;
import io.meridian.routing.application.RailService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Payment rails: catalogue with validation, suspend / resume, monitoring metrics, cut-offs, next execution date,
 * and the public inbound callback through which rails push ISO 20022 messages.
 * Contract: generated/openapi/services/routing-service.openapi.yaml
 */
@RestController
public class PaymentRailsController implements PaymentRailsApi {
    private final RailService rails;
    private final InboundService inbound;
    private final Actions actions;
    private final CrudEngine crud;

    public PaymentRailsController(RailService rails, InboundService inbound, Actions actions, CrudEngine crud) {
        this.rails = rails;
        this.inbound = inbound;
        this.actions = actions;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<PaymentRail> paymentRailsCreate(String idempotencyKey, PaymentRailCreate body) {
        TenantContext.require();
        rails.validate(actions.tree(body), true);
        return crud.create(RailService.RAIL, PaymentRail.class, body, idempotencyKey, Crud.params());
    }

    @Override
    public ResponseEntity<PaymentRail> paymentRailsUpdate(UUID id, String ifMatch, PaymentRailUpdate body) {
        TenantContext.require();
        JsonNode req = actions.tree(body);
        if (req.hasNonNull("status")) {
            throw ApiException.unprocessable("field_not_updatable", "status cannot be changed with PATCH; use suspend / resume");
        }
        rails.validate(req, false);
        return crud.update(RailService.RAIL, PaymentRail.class, body, ifMatch, Crud.params().with("id", id));
    }

    @Override
    public ResponseEntity<PaymentRail> paymentRailsSuspend(UUID id, String idempotencyKey, PaymentRailsSuspendRequest body) {
        JsonNode req = actions.tree(body);
        String reason = Http.text(req, "reason");
        if (reason == null || reason.isBlank()) {
            throw ApiException.unprocessable("reason_required", "reason is required");
        }
        return actions.action(idempotencyKey, id, "suspend", body, PaymentRail.class, () -> rails.suspend(id, reason));
    }

    @Override
    public ResponseEntity<PaymentRail> paymentRailsResume(UUID id, String idempotencyKey, PaymentRailsResumeRequest body) {
        String reason = Http.text(actions.tree(body), "reason");
        return actions.action(idempotencyKey, id, "resume", body, PaymentRail.class, () -> rails.resume(id, reason));
    }

    @Override
    public ResponseEntity<PaymentRail> paymentRailsMetrics(UUID id, String idempotencyKey, PaymentRailsMetricsRequest body) {
        JsonNode req = actions.tree(body);
        BigDecimal rate = Http.decimal(req, "success_rate_7d");
        Integer p95 = req.hasNonNull("p95_latency_ms") ? req.get("p95_latency_ms").asInt() : null;
        return actions.action(idempotencyKey, id, "metrics", body, PaymentRail.class, () -> rails.metrics(id, rate, p95));
    }

    @Override
    public ResponseEntity<RailCutoffPage> paymentRailsCutoffs(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        crud.findOne(RailService.RAIL, Map.of("id", id)).orElseThrow(() -> ApiException.notFound("payment rail", id));
        return crud.list("rail_cutoff", RailCutoffPage.class, Crud.params().with("payment_rail_id", id)
                .with("limit", limit).with("cursor", cursor));
    }

    @Override
    public ResponseEntity<Map<String, Object>> paymentRailsNextExecution(UUID id, String paymentType, String currency,
                                                                       OffsetDateTime at) {
        TenantContext.require();
        if (currency != null && !currency.matches("[A-Z]{3}")) {
            throw ApiException.badRequest("invalid_currency", "currency must be an ISO 4217 code");
        }
        return ResponseEntity.ok(actions.map(rails.nextExecution(id, paymentType, currency, at, null)));
    }

    /** [public] rail callback: no token / tenant header; tenant from the rail, HMAC signature verified. */
    @Override
    public ResponseEntity<RailMessage> paymentRailsInbound(UUID id, PaymentRailsInboundRequest body) {
        JsonNode req = actions.tree(body);
        String signature = Http.request().getHeader("X-Rail-Signature");
        JsonNode row = inbound.receive(id, Http.text(req, "message_type"), Http.text(req, "payload"), signature);
        return ResponseEntity.ok(actions.model(row, RailMessage.class));
    }
}
