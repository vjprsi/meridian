package io.meridian.customer.api;

import io.meridian.customer.api.generated.ConsentsApi;
import io.meridian.customer.api.generated.model.Consent;
import io.meridian.customer.api.generated.model.ConsentCreate;
import io.meridian.customer.api.generated.model.ConsentsRenewRequest;
import io.meridian.customer.api.generated.model.ConsentsWithdrawRequest;
import io.meridian.customer.application.ConsentService;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Consents: capture, withdrawal and renewal with expiry handling. */
@RestController
public class ConsentsController implements ConsentsApi {
    private final ConsentService consents;
    private final Http http;

    public ConsentsController(ConsentService consents, Http http) {
        this.consents = consents;
        this.http = http;
    }

    @Override
    public ResponseEntity<Consent> consentsCreate(String idempotencyKey, ConsentCreate body) {
        return http.created(idempotencyKey, "consents.create", null, body, Consent.class, () -> consents.create(body));
    }

    @Override
    public ResponseEntity<Consent> consentsWithdraw(UUID id, String idempotencyKey, ConsentsWithdrawRequest body) {
        JsonNode req = http.tree(body);
        String reason = req.hasNonNull("reason") ? req.get("reason").asString() : null;
        return http.ok(idempotencyKey, "consents.withdraw", id, body, Consent.class, () -> consents.withdraw(id, reason));
    }

    @Override
    public ResponseEntity<Consent> consentsRenew(UUID id, String idempotencyKey, ConsentsRenewRequest body) {
        JsonNode req = http.tree(body);
        OffsetDateTime expires = OffsetDateTime.parse(req.get("expires_at").asString());
        JsonNode evidence = req.get("evidence");
        return http.ok(idempotencyKey, "consents.renew", id, body, Consent.class, () -> consents.renew(id, expires, evidence));
    }
}
