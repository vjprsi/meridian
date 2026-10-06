package io.meridian.treasurydeal.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.treasurydeal.api.generated.TreasuryConfirmationsApi;
import io.meridian.treasurydeal.api.generated.model.TreasuryConfirmationsDisputeRequest;
import io.meridian.treasurydeal.api.generated.model.TreasuryConfirmationsGenerateRequest;
import io.meridian.treasurydeal.api.generated.model.TreasuryConfirmationsMatchRequest;
import io.meridian.treasurydeal.api.generated.model.TreasuryDealConfirmation;
import io.meridian.treasurydeal.api.generated.model.TreasuryDealConfirmationCreate;
import io.meridian.treasurydeal.application.ConfirmationService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Deal confirmations: outbound generation (MT320 / MT300), inbound capture, matching and disputes. */
@RestController
public class TreasuryConfirmationsController implements TreasuryConfirmationsApi {
    private final ConfirmationService service;
    private final Actions actions;

    public TreasuryConfirmationsController(ConfirmationService service, Actions actions) {
        this.service = service;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<TreasuryDealConfirmation> treasuryConfirmationsCreate(String idempotencyKey,
                                                                               TreasuryDealConfirmationCreate body) {
        return actions.create(idempotencyKey, body, TreasuryDealConfirmation.class, () -> service.receive(actions.tree(body)));
    }

    @Override
    public ResponseEntity<TreasuryDealConfirmation> treasuryConfirmationsGenerate(String idempotencyKey,
                                                                                 TreasuryConfirmationsGenerateRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, null, "generate", body, TreasuryDealConfirmation.class, () -> service.generate(
                Http.text(req, "deal_type"), Http.uuid(req, "deal_id"), Http.text(req, "channel")));
    }

    @Override
    public ResponseEntity<TreasuryDealConfirmation> treasuryConfirmationsMatch(UUID id, String idempotencyKey,
                                                                              TreasuryConfirmationsMatchRequest body) {
        return actions.action(idempotencyKey, id, "match", body, TreasuryDealConfirmation.class,
                () -> service.match(id, Http.uuid(actions.tree(body), "deal_id")));
    }

    @Override
    public ResponseEntity<TreasuryDealConfirmation> treasuryConfirmationsDispute(UUID id, String idempotencyKey,
                                                                                TreasuryConfirmationsDisputeRequest body) {
        return actions.action(idempotencyKey, id, "dispute", body, TreasuryDealConfirmation.class,
                () -> service.dispute(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<String> treasuryConfirmationsMessage(UUID id) {
        TenantContext.require();
        return Actions.text(service.message(id), "confirmation-" + id + ".txt");
    }
}
