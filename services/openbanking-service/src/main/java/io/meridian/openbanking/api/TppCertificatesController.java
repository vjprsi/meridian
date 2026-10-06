package io.meridian.openbanking.api;

import io.meridian.openbanking.api.generated.TppCertificatesApi;
import io.meridian.openbanking.api.generated.model.TppCertificate;
import io.meridian.openbanking.api.generated.model.TppCertificateCreate;
import io.meridian.openbanking.api.generated.model.TppCertificatesRevokeRequest;
import io.meridian.openbanking.application.TppService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** TPP certificates: PEM upload parsed and validated (validity, eIDAS PSD2 roles, NCA), revocation. */
@RestController
public class TppCertificatesController implements TppCertificatesApi {
    private final TppService service;
    private final Actions actions;

    public TppCertificatesController(TppService service, Actions actions) {
        this.service = service;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<TppCertificate> tppCertificatesCreate(String idempotencyKey, TppCertificateCreate body) {
        return actions.create(idempotencyKey, body, TppCertificate.class, () -> service.addCertificate(body));
    }

    @Override
    public ResponseEntity<TppCertificate> tppCertificatesRevoke(UUID id, String idempotencyKey,
                                                                TppCertificatesRevokeRequest body) {
        return actions.action(idempotencyKey, id, "revoke", body, TppCertificate.class,
                () -> service.revokeCertificate(id, Http.text(actions.tree(body), "reason")));
    }
}
