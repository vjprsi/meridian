package io.meridian.cardauthorization.api;

import io.meridian.cardauthorization.api.generated.CardAuthorizationsApi;
import io.meridian.cardauthorization.api.generated.model.CardAuthorization;
import io.meridian.cardauthorization.api.generated.model.CardAuthorizationCreate;
import io.meridian.cardauthorization.api.generated.model.CardAuthorizationPage;
import io.meridian.cardauthorization.api.generated.model.CardAuthorizationsClearRequest;
import io.meridian.cardauthorization.api.generated.model.CardAuthorizationsExpireRequest;
import io.meridian.cardauthorization.api.generated.model.CardAuthorizationsSimulateClearingRequest;
import io.meridian.cardauthorization.api.generated.model.CardAuthorizationsSimulateIncrementalRequest;
import io.meridian.cardauthorization.api.generated.model.CardAuthorizationsSimulateRequest;
import io.meridian.cardauthorization.api.generated.model.CardAuthorizationsSimulateReversalRequest;
import io.meridian.cardauthorization.api.generated.model.CardJitFundingRequestPage;
import io.meridian.cardauthorization.api.generated.model.ClearingRecord;
import io.meridian.cardauthorization.application.AuthorizationService;
import io.meridian.cardauthorization.application.LifecycleService;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Card authorisations: live ISO 8583 messages (create), sandbox simulations, clearing notifications from
 * card-clearing-service, expiry and usage. Declines are valid ISO answers and are returned as 201 with the row.
 */
@RestController
public class CardAuthorizationsController implements CardAuthorizationsApi {
    private final AuthorizationService auth;
    private final LifecycleService lifecycle;
    private final Actions actions;
    private final CrudEngine crud;
    private final io.micrometer.core.instrument.MeterRegistry meters;

    public CardAuthorizationsController(AuthorizationService auth, LifecycleService lifecycle, Actions actions, CrudEngine crud,
                                        io.micrometer.core.instrument.MeterRegistry meters) {
        this.meters = meters;
        this.auth = auth;
        this.lifecycle = lifecycle;
        this.actions = actions;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<CardAuthorization> cardAuthorizationsCreate(String idempotencyKey, CardAuthorizationCreate body) {
        return actions.create(idempotencyKey, body, CardAuthorization.class, () -> timed(actions.tree(body)));
    }

    /**
     * GAP-10 / SLO J2: decision latency at the service ({@code meridian.cards.authorization.duration}, tags
     * {@code message_type}, {@code decision} APPROVED|PARTIAL|DECLINED|ERROR and {@code stand_in}).
     */
    private tools.jackson.databind.node.ObjectNode timed(JsonNode req) {
        io.micrometer.core.instrument.Timer.Sample sample = io.micrometer.core.instrument.Timer.start(meters);
        String type = req.path("message_type").asString("AUTHORIZATION");
        String decision = "ERROR";
        String standIn = "false";
        try {
            tools.jackson.databind.node.ObjectNode row = auth.process(req);
            decision = row.path("decision").asString("UNKNOWN");
            standIn = String.valueOf(row.path("stand_in").asBoolean(false));
            return row;
        } finally {
            sample.stop(meters.timer("meridian.cards.authorization.duration", "message_type", type,
                    "decision", decision, "stand_in", standIn));
        }
    }

    @Override
    public ResponseEntity<CardAuthorization> cardAuthorizationsSimulate(String idempotencyKey, CardAuthorizationsSimulateRequest body) {
        return actions.action(idempotencyKey, null, "simulate", body, CardAuthorization.class,
                () -> lifecycle.simulate(actions.tree(body)));
    }

    @Override
    public ResponseEntity<CardAuthorization> cardAuthorizationsSimulateReversal(UUID id, String idempotencyKey,
                                                                               CardAuthorizationsSimulateReversalRequest body) {
        JsonNode b = actions.tree(body);
        return actions.action(idempotencyKey, id, "simulate-reversal", body, CardAuthorization.class,
                () -> lifecycle.simulateReversal(id, decimal(b, "amount")));
    }

    @Override
    public ResponseEntity<CardAuthorization> cardAuthorizationsSimulateIncremental(UUID id, String idempotencyKey,
                                                                                  CardAuthorizationsSimulateIncrementalRequest body) {
        JsonNode b = actions.tree(body);
        return actions.action(idempotencyKey, id, "simulate-incremental", body, CardAuthorization.class,
                () -> lifecycle.simulateIncremental(id, decimal(b, "amount")));
    }

    @Override
    public ResponseEntity<ClearingRecord> cardAuthorizationsSimulateClearing(UUID id, String idempotencyKey,
                                                                            CardAuthorizationsSimulateClearingRequest body) {
        JsonNode b = actions.tree(body);
        return actions.action(idempotencyKey, id, "simulate-clearing", body, ClearingRecord.class,
                () -> lifecycle.simulateClearing(id, decimal(b, "amount"), idempotencyKey));
    }

    @Override
    public ResponseEntity<CardAuthorization> cardAuthorizationsClear(UUID id, String idempotencyKey,
                                                                    CardAuthorizationsClearRequest body) {
        JsonNode b = actions.tree(body);
        return actions.action(idempotencyKey, id, "clear", body, CardAuthorization.class,
                () -> lifecycle.clear(id, decimal(b, "amount"), b.path("final").asBoolean(false)));
    }

    @Override
    public ResponseEntity<CardAuthorization> cardAuthorizationsExpire(UUID id, String idempotencyKey,
                                                                     CardAuthorizationsExpireRequest body) {
        JsonNode b = actions.tree(body);
        return actions.action(idempotencyKey, id, "expire", body, CardAuthorization.class,
                () -> lifecycle.expire(id, b.hasNonNull("reason") ? b.get("reason").asString() : null));
    }

    @Override
    public ResponseEntity<Map<String, Object>> cardAuthorizationsExpireStale(String idempotencyKey, OffsetDateTime asOf) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("as_of", asOf == null ? null : asOf.toString());
        return actions.objectAction(idempotencyKey, "expire-stale", q, () -> lifecycle.expireStale(asOf));
    }

    @Override
    public ResponseEntity<Map<String, Object>> cardAuthorizationsUsage(UUID cardId) {
        TenantContext.require();
        return actions.object(lifecycle.usage(cardId));
    }

    @Override
    public ResponseEntity<CardJitFundingRequestPage> cardAuthorizationsJitRequests(UUID id, Integer limit, String cursor) {
        requireExists(id);
        return crud.list("card_jit_funding_request", CardJitFundingRequestPage.class,
                Crud.params().with("limit", limit).with("cursor", cursor).with("card_authorization_id", id));
    }

    @Override
    public ResponseEntity<CardAuthorizationPage> cardAuthorizationsRelated(UUID id, Integer limit, String cursor) {
        requireExists(id);
        return crud.list("card_authorization", CardAuthorizationPage.class,
                Crud.params().with("limit", limit).with("cursor", cursor).with("parent_authorization_id", id));
    }

    private void requireExists(UUID id) {
        TenantContext.require();
        if (crud.findOne("card_authorization", Map.of("id", id)).isEmpty()) {
            throw ApiException.notFound("card authorization", id);
        }
    }

    private static BigDecimal decimal(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? null : new BigDecimal(v.asString());
    }
}
