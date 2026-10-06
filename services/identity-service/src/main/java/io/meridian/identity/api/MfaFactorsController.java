package io.meridian.identity.api;

import io.meridian.identity.api.generated.MfaFactorsApi;
import io.meridian.identity.api.generated.model.MfaFactor;
import io.meridian.identity.api.generated.model.MfaFactorCreate;
import io.meridian.identity.api.generated.model.MfaFactorsVerifyRequest;
import io.meridian.identity.application.MfaService;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** MFA factors: TOTP enrolment returns the seed and otpauth URI exactly once; verify activates the factor. */
@RestController
public class MfaFactorsController implements MfaFactorsApi {
    private final MfaService mfa;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public MfaFactorsController(MfaService mfa, CrudEngine crud, Idempotency idempotency, JsonMapper json) {
        this.mfa = mfa;
        this.crud = crud;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<MfaFactor> mfaFactorsCreate(UUID userId, String idempotencyKey, MfaFactorCreate body) {
        return Responses.secretOnce(idempotency, json, idempotencyKey,
                Map.of("user_id", userId, "body", json.valueToTree(body)), HttpStatus.CREATED, MfaFactor.class, () -> {
                    MfaService.Enrolment e = mfa.enrol(userId, body);
                    return new Responses.WithSecrets(e.factor(), e.secrets());
                }, (f, s) -> {
                    f.setTotpSecret(s.get("totp_secret"));
                    f.setOtpauthUri(s.get("otpauth_uri"));
                });
    }

    @Override
    public ResponseEntity<MfaFactor> mfaFactorsVerify(UUID userId, UUID id, String idempotencyKey,
                                                     MfaFactorsVerifyRequest body) {
        JsonNode req = body == null ? json.createObjectNode() : json.valueToTree(body);
        return idempotency.execute(idempotencyKey, Map.of("op", "verify", "id", id, "body", req), MfaFactor.class,
                () -> ResponseEntity.ok(crud.read(mfa.verify(userId, id, body == null ? null : body.getCode(),
                        req.get("assertion")), MfaFactor.class)));
    }

    @Override
    public ResponseEntity<Void> mfaFactorsDelete(UUID userId, UUID id) {
        mfa.delete(userId, id);
        return ResponseEntity.noContent().build();
    }
}
