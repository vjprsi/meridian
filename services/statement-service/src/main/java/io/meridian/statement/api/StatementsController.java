package io.meridian.statement.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.statement.api.generated.StatementsApi;
import io.meridian.statement.api.generated.model.Statement;
import io.meridian.statement.api.generated.model.StatementCreate;
import io.meridian.statement.api.generated.model.StatementLinePage;
import io.meridian.statement.api.generated.model.StatementsResendRequest;
import io.meridian.statement.application.ScheduleService;
import io.meridian.statement.application.StatementService;
import io.meridian.web.idempotency.Idempotency;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Statements: generation from the ledger (create), lines, download (file or signed link), resend, regenerate and the
 * end-of-day statement cycle called by business-day-service.
 */
@RestController
public class StatementsController implements StatementsApi {
    private final StatementService statements;
    private final ScheduleService schedules;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public StatementsController(StatementService statements, ScheduleService schedules, Idempotency idempotency,
                                JsonMapper json) {
        this.statements = statements;
        this.schedules = schedules;
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

    /** The file (content type per format, checksum verified); a signed link's expires/signature are verified first. */
    @Override
    public ResponseEntity<String> statementsDownload(UUID id, Long expires, String signature) {
        TenantContext.require();
        if (expires != null || signature != null) {
            statements.verifySignature(id, String.valueOf(expires), String.valueOf(signature));
        }
        StatementService.Download d = statements.download(id);
        return Http.file(d.content(), d.contentType(), d.filename(), d.checksum());
    }

    @Override
    public ResponseEntity<Map<String, Object>> statementsDownloadLink(UUID id) {
        TenantContext.require();
        return Http.object(json, statements.signedLink(id, "statement"));
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

    /** End of day: statements for every schedule whose cycle ends on the business date (re-runs produce nothing new). */
    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public ResponseEntity<Map<String, Object>> statementsCycle(LocalDate businessDate, String idempotencyKey) {
        TenantContext.require();
        ResponseEntity raw = idempotency.execute(idempotencyKey, Map.of("action", "cycle", "business_date",
                businessDate.toString()), Map.class, () -> (ResponseEntity) Http.object(json, schedules.cycleResult(businessDate)));
        return (ResponseEntity<Map<String, Object>>) raw;
    }
}
