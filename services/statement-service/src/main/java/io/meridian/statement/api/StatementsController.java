package io.meridian.statement.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.statement.api.generated.StatementsApi;
import io.meridian.statement.api.generated.model.Statement;
import io.meridian.statement.api.generated.model.StatementCreate;
import io.meridian.statement.api.generated.model.StatementLinePage;
import io.meridian.statement.api.generated.model.StatementsResendRequest;
import io.meridian.statement.application.StatementService;
import io.meridian.web.idempotency.Idempotency;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Statements: generation from the ledger (create), lines, download (content or signed URL), resend, regenerate. */
@RestController
public class StatementsController implements StatementsApi {
    private final StatementService statements;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public StatementsController(StatementService statements, Idempotency idempotency, JsonMapper json) {
        this.statements = statements;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<Statement> statementsCreate(String idempotencyKey, StatementCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, Statement.class,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(json.treeToValue(statements.create(body), Statement.class)));
    }

    @Override
    public ResponseEntity<StatementLinePage> statementsLines(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        return ResponseEntity.ok(json.treeToValue(statements.lines(id, limit, cursor), StatementLinePage.class));
    }

    /**
     * GET /v1/statements/{id}/download: the file itself (content type per format, checksum verified), or with
     * {@code ?as=url} a signed expiring link; a signed link ({@code expires}, {@code signature}) is verified first.
     */
    @Override
    public ResponseEntity<Void> statementsDownload(UUID id) {
        TenantContext.require();
        String expires = Http.query("expires");
        String signature = Http.query("signature");
        if (expires != null || signature != null) {
            statements.verifySignature(id, String.valueOf(expires), String.valueOf(signature));
        }
        if ("url".equalsIgnoreCase(Http.query("as"))) {
            return Http.json(json, statements.signedLink(id, "statement"));
        }
        StatementService.Download d = statements.download(id);
        return Http.file(d.content(), d.contentType(), d.filename(), d.checksum());
    }

    @Override
    public ResponseEntity<Statement> statementsResend(UUID id, String idempotencyKey, StatementsResendRequest body) {
        TenantContext.require();
        JsonNode req = body == null ? json.createObjectNode() : json.valueToTree(body);
        return idempotency.execute(idempotencyKey, Map.of("id", id.toString(), "action", "resend", "body", req),
                Statement.class, () -> ResponseEntity.ok(json.treeToValue(statements.resend(id,
                        Http.text(req, "delivery_channel"), Http.text(req, "email")), Statement.class)));
    }

    @Override
    public ResponseEntity<Statement> statementsRegenerate(UUID id, String idempotencyKey) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, Map.of("id", id.toString(), "action", "regenerate"), Statement.class,
                () -> ResponseEntity.ok(json.treeToValue(statements.regenerate(id), Statement.class)));
    }
}
