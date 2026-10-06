package io.meridian.statement.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.statement.api.generated.TaxCertificatesApi;
import io.meridian.statement.api.generated.model.TaxCertificate;
import io.meridian.statement.api.generated.model.TaxCertificateCreate;
import io.meridian.statement.application.StatementService;
import io.meridian.statement.application.TaxCertificateService;
import io.meridian.statement.domain.StatementRenderer;
import io.meridian.web.idempotency.Idempotency;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/** Tax certificates: computed from deposit-service interest postings at creation; downloadable as text or signed URL. */
@RestController
public class TaxCertificatesController implements TaxCertificatesApi {
    private final TaxCertificateService certificates;
    private final StatementService statements;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public TaxCertificatesController(TaxCertificateService certificates, StatementService statements,
                                     Idempotency idempotency, JsonMapper json) {
        this.certificates = certificates;
        this.statements = statements;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<TaxCertificate> taxCertificatesCreate(String idempotencyKey, TaxCertificateCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, TaxCertificate.class, () -> ResponseEntity
                .status(HttpStatus.CREATED).body(json.treeToValue(certificates.create(body), TaxCertificate.class)));
    }

    @Override
    public ResponseEntity<String> taxCertificatesDownload(UUID id, Long expires, String signature) {
        TenantContext.require();
        if (expires != null || signature != null) {
            statements.verifySignature(id, String.valueOf(expires), String.valueOf(signature));
        }
        byte[] content = certificates.render(id).getBytes(StandardCharsets.UTF_8);
        return Http.file(content, "text/plain;charset=UTF-8", "tax-certificate-" + id + ".txt",
                StatementRenderer.sha256(content));
    }

    @Override
    public ResponseEntity<Map<String, Object>> taxCertificatesDownloadLink(UUID id) {
        TenantContext.require();
        return Http.object(json, statements.signedLink(id, "tax_certificate"));
    }
}
