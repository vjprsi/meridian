package io.meridian.treasurydeal.api;

import io.meridian.treasurydeal.api.generated.SecuritiesHoldingsApi;
import io.meridian.treasurydeal.api.generated.model.SecuritiesHolding;
import io.meridian.treasurydeal.api.generated.model.SecuritiesHoldingsEncumberRequest;
import io.meridian.treasurydeal.api.generated.model.SecuritiesHoldingsRevalueRequest;
import io.meridian.treasurydeal.application.SecuritiesService;
import io.meridian.treasurydeal.infrastructure.Rows;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Securities holdings (maintained by trade settlement): revaluation and encumbrance. */
@RestController
public class SecuritiesHoldingsController implements SecuritiesHoldingsApi {
    private final SecuritiesService service;
    private final Actions actions;

    public SecuritiesHoldingsController(SecuritiesService service, Actions actions) {
        this.service = service;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<Map<String, Object>> securitiesHoldingsRevalue(String idempotencyKey,
                                                                         SecuritiesHoldingsRevalueRequest body) {
        JsonNode req = actions.tree(body);
        return actions.objectAction(idempotencyKey, "holdings-revalue", body,
                () -> service.revalue(Rows.date(req, "valuation_date"), Http.uuid(req, "legal_entity_id")));
    }

    @Override
    public ResponseEntity<SecuritiesHolding> securitiesHoldingsEncumber(UUID id, String idempotencyKey,
                                                                        SecuritiesHoldingsEncumberRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "encumber", body, SecuritiesHolding.class, () -> service.encumber(id,
                req.hasNonNull("is_encumbered") ? req.get("is_encumbered").asBoolean() : null, Http.text(req, "reason")));
    }
}
