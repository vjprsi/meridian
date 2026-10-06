package io.meridian.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.document.application.DocumentService;
import io.meridian.document.application.EsignService;
import io.meridian.document.application.ObjectStorage;
import io.meridian.document.application.TemplateService;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end over HTTP against PostgreSQL 18 with row-level security: document lifecycle with presigned
 * SigV4 URLs, scan outcome, legal hold / retention, templates with maker-checker publish, generation and
 * e-signature envelopes with signed provider callbacks.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.documents.consumer-enabled=false", "meridian.documents.jobs-enabled=false",
                "meridian.documents.s3.endpoint=http://localhost:19000", "meridian.documents.s3.bucket=docs-test"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DocumentFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000d1";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-0000000000d2";
    static final String SHA = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DocumentService documents;

    @Autowired
    TemplateService templates;

    @Autowired
    EsignService esign;

    @MockitoBean
    ServiceClient services;

    @MockitoSpyBean
    ObjectStorage storage;

    final HttpClient http = HttpClient.newHttpClient();
    final UUID owner = UUID.randomUUID();
    String availableDoc;
    String templateV1;
    String templateV2;

    // ------------------------------------------------------------------ documents

    @Test
    @Order(1)
    void documentUploadScanAndDownload() throws Exception {
        JsonNode doc = post("/v1/documents", docBody("CONTRACT", "Loan contract.pdf", "application/pdf", null, null), 201);
        String id = doc.get("id").asString();
        assertThat(doc.get("status").asString()).isEqualTo("UPLOADING");
        assertThat(doc.get("size_bytes").asLong()).isZero();
        assertThat(doc.has("storage_uri")).isFalse();

        HttpResponse<String> up = send("POST", "/v1/documents/" + id + "/upload-url", null, "up-" + id, TENANT);
        assertThat(up.statusCode()).as(up.body()).isEqualTo(200);
        JsonNode upload = json.readTree(up.body());
        String url = upload.get("url").asString();
        assertThat(upload.get("method").asString()).isEqualTo("PUT");
        assertThat(url).startsWith("http://localhost:19000/docs-test/" + TENANT + "/" + id + "/Loan_contract.pdf?")
                .contains("X-Amz-Algorithm=AWS4-HMAC-SHA256", "X-Amz-Credential=", "X-Amz-Expires=900",
                        "X-Amz-SignedHeaders=host", "X-Amz-Date=")
                .containsPattern("X-Amz-Signature=[0-9a-f]{64}$");
        assertThat(URLDecoder.decode(url, StandardCharsets.UTF_8)).contains("/us-east-1/s3/aws4_request");
        HttpResponse<String> again = send("POST", "/v1/documents/" + id + "/upload-url", null, "up-" + id, TENANT);
        assertThat(again.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json.readTree(again.body()).get("url").asString()).isEqualTo(url);

        assertThat(code(send("GET", "/v1/documents/" + id + "/download-url", null, null, TENANT), 409))
                .isEqualTo("document_not_downloadable");

        assertThat(code(send("POST", "/v1/documents/" + id + "/complete-upload", "{\"size_bytes\":2048}", key(), TENANT), 400))
                .isEqualTo("validation_failed");
        JsonNode scanning = post("/v1/documents/" + id + "/complete-upload",
                "{\"size_bytes\":2048,\"checksum\":\"" + SHA + "\"}", 200);
        assertThat(scanning.get("status").asString()).isEqualTo("SCANNING");
        assertThat(scanning.get("size_bytes").asLong()).isEqualTo(2048);
        assertThat(code(send("POST", "/v1/documents/" + id + "/complete-upload",
                "{\"size_bytes\":2048,\"checksum\":\"" + SHA + "\"}", key(), TENANT), 409)).isEqualTo("invalid_transition");
        JsonNode done = json.readTree(as(() -> documents.recordScan(UUID.fromString(id), "CLEAN")));
        assertThat(done.get("status").asString()).isEqualTo("AVAILABLE");
        assertThat(done.get("checksum").asString()).isEqualTo(SHA);
        assertThat(events(id)).contains("platform.document.uploaded");
        assertThat(code(send("POST", "/v1/documents/" + id + "/upload-url", null, "up2-" + id, TENANT), 409))
                .isEqualTo("invalid_transition");

        HttpResponse<String> down = send("GET", "/v1/documents/" + id + "/download-url", null, null, TENANT);
        assertThat(down.statusCode()).isEqualTo(200);
        String downUrl = json.readTree(down.body()).get("url").asString();
        assertThat(downUrl).contains("X-Amz-Expires=300", "response-content-disposition=attachment", "X-Amz-Signature=");
        availableDoc = id;
    }

    @Test
    @Order(2)
    void validationErrors() throws Exception {
        assertThat(code(send("POST", "/v1/documents", docBody("OTHER", "x.exe", "application/x-msdownload", null, null),
                key(), TENANT), 422)).isEqualTo("unsupported_content_type");
        assertThat(code(send("POST", "/v1/documents", docBody("PASSPORT", "p.pdf", "application/pdf", "2024-01-01", "2023-01-01"),
                key(), TENANT), 422)).isEqualTo("invalid_expiry_date");
        assertThat(code(send("POST", "/v1/documents", """
                {"owner_type":"CUSTOMER","document_type":"OTHER","file_name":"a.pdf","content_type":"application/pdf"}""",
                key(), TENANT), 400)).isEqualTo("validation_failed");
        assertThat(code(send("POST", "/v1/documents", docBody("NOT_A_TYPE", "a.pdf", "application/pdf", null, null),
                key(), TENANT), 400)).isIn("malformed_request", "validation_failed");
    }

    @Test
    @Order(3)
    void infectedUploadIsQuarantined() throws Exception {
        String id = post("/v1/documents", docBody("PROOF_OF_ADDRESS", "bill.png", "image/png", null, null), 201).get("id").asString();
        post("/v1/documents/" + id + "/complete-upload", "{\"size_bytes\":10,\"checksum\":\"" + SHA + "\"}", 200);
        JsonNode q = json.readTree(as(() -> documents.recordScan(UUID.fromString(id), "INFECTED")));
        assertThat(q.get("status").asString()).isEqualTo("QUARANTINED");
        assertThat(q.get("virus_scan_result").asString()).isEqualTo("INFECTED");
        assertThat(events(id)).contains("platform.document.quarantined");
        assertThat(code(send("GET", "/v1/documents/" + id + "/download-url", null, null, TENANT), 409))
                .isEqualTo("document_not_downloadable");
        assertThat(code(send("POST", "/v1/documents/" + id + "/archive", null, key(), TENANT), 409)).isEqualTo("invalid_transition");
    }

    @Test
    @Order(4)
    void legalHoldArchiveRetentionAndDelete() throws Exception {
        String id = availableDoc;
        JsonNode held = post("/v1/documents/" + id + "/legal-hold", "{\"legal_hold\":true,\"reason\":\"litigation 42\"}", 200);
        assertThat(held.get("legal_hold").asBoolean()).isTrue();
        assertThat(held.get("legal_hold_reason").asString()).isEqualTo("litigation 42");
        assertThat(held.get("legal_hold_changed_by").asString()).isEqualTo("dev-user");
        assertThat(held.hasNonNull("legal_hold_changed_at")).isTrue();
        assertThat(held.hasNonNull("metadata")).isFalse();
        assertThat(events(id)).contains("platform.document.legal_hold_placed");
        assertThat(code(send("POST", "/v1/documents/" + id + "/legal-hold", "{\"legal_hold\":true,\"reason\":\"again\"}",
                key(), TENANT), 409)).isEqualTo("invalid_transition");
        assertThat(code(send("DELETE", "/v1/documents/" + id, null, null, TENANT), 409)).isEqualTo("legal_hold_active");
        JsonNode cur = get("/v1/documents/" + id, TENANT);
        // legal_hold is read-only (changed through /legal-hold): a client value is ignored
        HttpResponse<String> patch = sendPatch("/v1/documents/" + id, "{\"legal_hold\":false,\"uploaded_via\":\"CONSOLE\"}",
                cur.get("row_version").asInt());
        assertThat(patch.statusCode()).as(patch.body()).isEqualTo(200);
        assertThat(json.readTree(patch.body()).get("legal_hold").asBoolean()).isTrue();
        cur = get("/v1/documents/" + id, TENANT);
        assertThat(code(sendPatch("/v1/documents/" + id, "{\"file_name\":\"renamed.pdf\"}", cur.get("row_version").asInt()), 409))
                .isEqualTo("content_immutable");

        JsonNode archived = post("/v1/documents/" + id + "/archive", null, 200);
        assertThat(archived.get("status").asString()).isEqualTo("ARCHIVED");
        assertThat(archived.hasNonNull("archived_at")).isTrue();
        assertThat(archived.get("storage_class").asString()).isEqualTo("GLACIER_IR");
        assertThat(events(id)).contains("platform.document.archived");
        assertThat(code(send("POST", "/v1/documents/" + id + "/archive", null, key(), TENANT), 409)).isEqualTo("invalid_transition");
        assertThat(send("GET", "/v1/documents/" + id + "/download-url", null, null, TENANT).statusCode()).isEqualTo(200);

        JsonNode lifted = post("/v1/documents/" + id + "/legal-hold", "{\"legal_hold\":false,\"reason\":\"case closed\"}", 200);
        assertThat(lifted.get("legal_hold").asBoolean()).isFalse();
        assertThat(lifted.get("legal_hold_reason").asString()).isEqualTo("case closed");
        assertThat(events(id)).contains("platform.document.legal_hold_lifted");
        assertThat(send("DELETE", "/v1/documents/" + id, null, null, TENANT).statusCode()).isEqualTo(204);
        assertThat(events(id)).contains("platform.document.deleted");
        assertThat(get("/v1/documents/" + id, TENANT).get("status").asString()).isEqualTo("DELETED");
        assertThat(send("DELETE", "/v1/documents/" + id, null, null, TENANT).statusCode()).isEqualTo(404);

        String retained = post("/v1/documents", """
                {"owner_type":"CUSTOMER","owner_id":"%s","document_type":"BANK_STATEMENT","file_name":"s.pdf",
                 "content_type":"application/pdf","retention_until":"%s"}""".formatted(owner, LocalDate.now().plusYears(7)), 201)
                .get("id").asString();
        assertThat(code(send("DELETE", "/v1/documents/" + retained, null, null, TENANT), 409)).isEqualTo("retention_period_active");
    }

    @Test
    @Order(5)
    void expiringIdentityDocumentsAreAnnouncedOnce() throws Exception {
        String id = post("/v1/documents", docBody("PASSPORT", "passport.jpg", "image/jpeg",
                LocalDate.now().minusYears(9).toString(), LocalDate.now().plusDays(10).toString()), 201).get("id").asString();
        uploadClean(id, 999);
        assertThat(as(() -> documents.notifyExpiring(LocalDate.now(), 30))).isEqualTo(1);
        assertThat(as(() -> documents.notifyExpiring(LocalDate.now(), 30))).isZero();
        assertThat(events(id)).containsOnlyOnce("platform.document.expiring");
        assertThat(get("/v1/documents/" + id, TENANT).has("expiry_notified_on")).isFalse();
    }

    // ------------------------------------------------------------------ templates & generation

    @Test
    @Order(6)
    void templateVersioningPreviewAndPublishApproval() throws Exception {
        assertThat(code(send("POST", "/v1/document-templates", templateBody("WELCOME", "Hello {{#if x}}"), key(), TENANT), 422))
                .isEqualTo("invalid_template");
        JsonNode v1 = post("/v1/document-templates", templateBody("WELCOME", "<h1>Welcome {{customer.name}}</h1><p>Ref {{owner_id}}</p>"), 201);
        assertThat(v1.get("version").asInt()).isEqualTo(1);
        assertThat(v1.get("status").asString()).isEqualTo("DRAFT");
        templateV1 = v1.get("id").asString();
        // status is read-only: a client value on create is ignored
        JsonNode ignored = post("/v1/document-templates", templateBody("IGNORED", "x").replace("\"locale\":\"en\"",
                "\"locale\":\"en\",\"status\":\"ACTIVE\""), 201);
        assertThat(ignored.get("status").asString()).isEqualTo("DRAFT");

        HttpResponse<String> prev = send("POST", "/v1/document-templates/" + templateV1 + "/preview",
                "{\"data\":{\"customer\":{\"name\":\"<Ann & Bob>\"}}}", key(), TENANT);
        assertThat(prev.statusCode()).as(prev.body()).isEqualTo(200);
        JsonNode p = json.readTree(prev.body());
        assertThat(p.get("rendered").asString()).isEqualTo("<h1>Welcome &lt;Ann &amp; Bob&gt;</h1><p>Ref </p>");
        assertThat(p.get("missing_variables").get(0).asString()).isEqualTo("owner_id");

        String gen = """
                {"document_template_id":"%s","owner_type":"CUSTOMER","owner_id":"%s","data":{"customer":{"name":"Ann"}}}"""
                .formatted(templateV1, owner);
        assertThat(code(send("POST", "/v1/generated-documents", gen, key(), TENANT), 422)).isEqualTo("template_not_active");

        when(services.post(eq("workflow-service"), eq("/v1/approval-requests"), any(), anyString()))
                .thenAnswer(inv -> approval(inv.getArgument(2)));
        JsonNode approval = post("/v1/document-templates/" + templateV1 + "/publish", null, 200);
        assertThat(approval.get("resource_type").asString()).isEqualTo("document_template");
        assertThat(approval.get("resource_id").asString()).isEqualTo(templateV1);
        assertThat(approval.get("status").asString()).isEqualTo("PENDING");
        JsonNode pending = get("/v1/document-templates/" + templateV1, TENANT);
        assertThat(pending.get("status").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(code(send("POST", "/v1/document-templates/" + templateV1 + "/publish", null, key(), TENANT), 409))
                .isEqualTo("invalid_transition");
        assertThat(code(sendPatchTo("/v1/document-templates/" + templateV1, "{\"name\":\"x\"}", pending.get("row_version").asInt()), 409))
                .isEqualTo("template_not_editable");

        assertThat(as(() -> templates.onApprovalDecided(UUID.fromString(templateV1), "APPROVED"))).isTrue();
        assertThat(get("/v1/document-templates/" + templateV1, TENANT).get("status").asString()).isEqualTo("ACTIVE");
        assertThat(events(templateV1)).containsExactly("platform.document_template.activated");
        assertThat(as(() -> templates.onApprovalDecided(UUID.fromString(templateV1), "APPROVED"))).isFalse();

        JsonNode v2 = post("/v1/document-templates", templateBody("WELCOME", "<h1>Hi {{customer.name}}</h1>"), 201);
        assertThat(v2.get("version").asInt()).isEqualTo(2);
        templateV2 = v2.get("id").asString();
        JsonNode edited = sendPatchJson("/v1/document-templates/" + templateV2, "{\"name\":\"Welcome letter v2\"}",
                v2.get("row_version").asInt());
        assertThat(edited.get("name").asString()).isEqualTo("Welcome letter v2");
        post("/v1/document-templates/" + templateV2 + "/publish", null, 200);
        as(() -> templates.onApprovalDecided(UUID.fromString(templateV2), "APPROVED"));
        assertThat(get("/v1/document-templates/" + templateV2, TENANT).get("status").asString()).isEqualTo("ACTIVE");
        assertThat(get("/v1/document-templates/" + templateV1, TENANT).get("status").asString()).isEqualTo("RETIRED");
        assertThat(code(send("POST", "/v1/document-templates/" + templateV1 + "/retire", null, key(), TENANT), 409))
                .isEqualTo("invalid_transition");

        JsonNode rejected = post("/v1/document-templates", templateBody("NOTICE", "Notice {{x}}"), 201);
        post("/v1/document-templates/" + rejected.get("id").asString() + "/publish", null, 200);
        as(() -> templates.onApprovalDecided(UUID.fromString(rejected.get("id").asString()), "REJECTED"));
        assertThat(get("/v1/document-templates/" + rejected.get("id").asString(), TENANT).get("status").asString()).isEqualTo("DRAFT");
        assertThat(post("/v1/document-templates/" + rejected.get("id").asString() + "/retire", null, 200)
                .get("status").asString()).isEqualTo("RETIRED");

        for (String outcome : List.of("CHANGES_REQUESTED", "CANCELLED")) {
            String t = post("/v1/document-templates", templateBody("NOTICE_" + outcome, "Notice {{x}}"), 201).get("id").asString();
            post("/v1/document-templates/" + t + "/publish", null, 200);
            assertThat(as(() -> templates.onApprovalDecided(UUID.fromString(t), outcome))).isTrue();
            assertThat(get("/v1/document-templates/" + t, TENANT).get("status").asString()).isEqualTo("DRAFT");
        }
    }

    @Test
    @Order(7)
    void generateDocumentFromActiveTemplate() throws Exception {
        doNothing().when(storage).put(anyString(), any(), anyString());
        String body = """
                {"document_template_id":"%s","owner_type":"CUSTOMER","owner_id":"%s","data":{"customer":{"name":"Ann <3"}}}"""
                .formatted(templateV2, owner);
        String k = key();
        HttpResponse<String> r = send("POST", "/v1/generated-documents", body, k, TENANT);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        JsonNode g = json.readTree(r.body());
        assertThat(g.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(g.get("input_hash").asString()).hasSize(64);
        assertThat(g.get("generated_at").isNull()).isFalse();
        JsonNode doc = get("/v1/documents/" + g.get("document_id").asString(), TENANT);
        assertThat(doc.get("status").asString()).isEqualTo("AVAILABLE");
        assertThat(doc.get("content_type").asString()).isEqualTo("text/html");
        assertThat(doc.get("size_bytes").asLong()).isEqualTo("<h1>Hi Ann &lt;3</h1>".length());
        assertThat(events(g.get("id").asString())).contains("platform.generated_document.completed");
        assertThat(g.get("data").get("customer").get("name").asString()).isEqualTo("Ann <3");

        HttpResponse<String> replay = send("POST", "/v1/generated-documents", body, k, TENANT);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json.readTree(replay.body()).get("id").asString()).isEqualTo(g.get("id").asString());
        HttpResponse<String> reused = send("POST", "/v1/generated-documents", body.replace("Ann <3", "Eve"), k, TENANT);
        assertThat(code(reused, 422)).isEqualTo("idempotency_key_reused");

        JsonNode failed = post("/v1/generated-documents", """
                {"document_template_id":"%s","owner_type":"CUSTOMER","owner_id":"%s","data":{}}""".formatted(templateV2, owner), 201);
        assertThat(failed.get("status").asString()).isEqualTo("FAILED");
        assertThat(failed.get("error").asString()).contains("customer.name");
        assertThat(events(failed.get("id").asString())).containsExactly("platform.generated_document.failed");
    }

    // ------------------------------------------------------------------ e-signature

    @Test
    @Order(8)
    void esignEnvelopeSignedThroughCallbacks() throws Exception {
        String doc = post("/v1/documents", docBody("LOAN_AGREEMENT", "loan.pdf", "application/pdf", null, null), 201).get("id").asString();
        assertThat(code(send("POST", "/v1/esign-requests", esignBody(doc, "[{\"name\":\"Ann\",\"email\":\"ann@example.com\"}]"),
                key(), TENANT), 422)).isEqualTo("document_not_available");
        uploadClean(doc, 5000);
        assertThat(code(send("POST", "/v1/esign-requests", esignBody(doc, "[]"), key(), TENANT), 422)).isEqualTo("signers_required");
        assertThat(code(send("POST", "/v1/esign-requests", esignBody(doc, "[{\"name\":\"Ann\",\"email\":\"nope\"}]"), key(), TENANT), 422))
                .isEqualTo("invalid_signer");

        JsonNode env = post("/v1/esign-requests", esignBody(doc,
                "[{\"name\":\"Ann\",\"email\":\"ann@example.com\"},{\"name\":\"Bank officer\",\"email\":\"officer@bank.example\"}]"), 201);
        String id = env.get("id").asString();
        assertThat(env.get("status").asString()).isEqualTo("DRAFT");
        assertThat(code(send("POST", "/v1/esign-requests/" + id + "/remind", null, key(), TENANT), 409)).isEqualTo("invalid_transition");

        JsonNode sent = post("/v1/esign-requests/" + id + "/send", null, 200);
        assertThat(sent.get("status").asString()).isEqualTo("SENT");
        String envelope = sent.get("provider_envelope_id").asString();
        assertThat(envelope).startsWith("docusign-env-");
        assertThat(code(send("POST", "/v1/esign-requests/" + id + "/send", null, key(), TENANT), 409)).isEqualTo("invalid_transition");
        JsonNode reminded = post("/v1/esign-requests/" + id + "/remind", null, 200);
        assertThat(reminded.get("reminder_count").asInt()).isEqualTo(1);
        assertThat(reminded.hasNonNull("last_reminded_at")).isTrue();
        assertThat(reminded.get("signers")).hasSize(2);
        assertThat(reminded.get("signers").get(0).get("status").asString()).isEqualTo("PENDING");

        String viewed = callbackBody(envelope, "VIEWED", null);
        HttpResponse<String> unsigned = sendCallback("DOCUSIGN", viewed, "sha256=deadbeef");
        assertThat(code(unsigned, 401)).isEqualTo("invalid_signature");
        assertThat(callback("DOCUSIGN", viewed).get("status").asString()).isEqualTo("VIEWED");
        assertThat(callback("DOCUSIGN", callbackBody(envelope, "SIGNED", "ann@example.com")).get("status").asString())
                .isEqualTo("PARTIALLY_SIGNED");
        assertThat(callback("DOCUSIGN", callbackBody(envelope, "SIGNED", "officer@bank.example")).get("status").asString())
                .isEqualTo("SIGNED");
        JsonNode signed = get("/v1/esign-requests/" + id, TENANT);
        assertThat(signed.get("completed_at").isNull()).isFalse();
        assertThat(events(id)).containsExactly("platform.esign_request.sent", "platform.esign_request.completed");
        JsonNode duplicate = callback("DOCUSIGN", callbackBody(envelope, "SIGNED", "officer@bank.example"));
        assertThat(duplicate.get("outcome").asString()).isEqualTo("DUPLICATE");
        assertThat(events(id)).containsOnlyOnce("platform.esign_request.completed");
        assertThat(code(send("POST", "/v1/esign-requests/" + id + "/cancel", "{\"reason\":\"too late\"}", key(), TENANT), 409))
                .isEqualTo("invalid_transition");
        assertThat(code(sendCallback("DOCUSIGN", callbackBody(envelope, "DECLINED", null),
                esign.sign(callbackBody(envelope, "DECLINED", null).getBytes(StandardCharsets.UTF_8))), 409))
                .isEqualTo("invalid_transition");

        JsonNode env2 = post("/v1/esign-requests", esignBody(doc, "[{\"name\":\"Ann\",\"email\":\"ann@example.com\"}]"), 201);
        String envelope2 = post("/v1/esign-requests/" + env2.get("id").asString() + "/send", null, 200)
                .get("provider_envelope_id").asString();
        // the tenant is resolved from (provider, envelope): another provider's or an unknown envelope is not found
        String declined = callbackBody(envelope2, "DECLINED", null).replace("}", ",\"reason\":\"terms\"}");
        assertThat(sendCallback("ADOBE_SIGN", declined, esign.sign(declined.getBytes(StandardCharsets.UTF_8))).statusCode())
                .isEqualTo(404);
        String unknown = callbackBody("docusign-env-unknown", "VIEWED", null);
        assertThat(sendCallback("DOCUSIGN", unknown, esign.sign(unknown.getBytes(StandardCharsets.UTF_8))).statusCode())
                .isEqualTo(404);
        assertThat(sendCallback("FAXSIGN", declined, esign.sign(declined.getBytes(StandardCharsets.UTF_8))).statusCode())
                .isEqualTo(404);
        assertThat(callback("DOCUSIGN", declined).get("status").asString()).isEqualTo("DECLINED");
        assertThat(get("/v1/esign-requests/" + env2.get("id").asString(), TENANT).get("decline_reason").asString())
                .isEqualTo("terms");
        assertThat(events(env2.get("id").asString()))
                .containsExactly("platform.esign_request.sent", "platform.esign_request.declined");

        JsonNode env3 = post("/v1/esign-requests", esignBody(doc, "[{\"name\":\"Ann\",\"phone\":\"+447700900123\"}]"), 201);
        assertThat(code(send("POST", "/v1/esign-requests/" + env3.get("id").asString() + "/cancel", "{}", key(), TENANT), 400))
                .isEqualTo("validation_failed");
        JsonNode cancelled = post("/v1/esign-requests/" + env3.get("id").asString() + "/cancel", "{\"reason\":\"wrong document\"}", 200);
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("cancellation_reason").asString()).isEqualTo("wrong document");
        assertThat(cancelled.get("cancelled_by").asString()).isEqualTo("dev-user");
        assertThat(events(env3.get("id").asString())).containsExactly("platform.esign_request.cancelled");
    }

    // ------------------------------------------------------------------ isolation & idempotency

    @Test
    @Order(9)
    void otherTenantSeesNothing() throws Exception {
        assertThat(send("GET", "/v1/documents/" + availableDoc, null, null, OTHER_TENANT).statusCode()).isEqualTo(404);
        assertThat(get("/v1/documents", OTHER_TENANT).get("data")).isEmpty();
        assertThat(get("/v1/document-templates", OTHER_TENANT).get("data")).isEmpty();
        assertThat(send("POST", "/v1/document-templates/" + templateV2 + "/retire", null, key(), OTHER_TENANT).statusCode()).isEqualTo(404);
        assertThat(get("/v1/document-templates/" + templateV2, TENANT).get("status").asString()).isEqualTo("ACTIVE");
    }

    @Test
    @Order(10)
    void idempotentReplayOfActions() throws Exception {
        String id = post("/v1/documents", docBody("OTHER", "r.pdf", "application/pdf", null, null), 201).get("id").asString();
        String k = key();
        HttpResponse<String> first = send("POST", "/v1/documents/" + id + "/legal-hold", "{\"legal_hold\":true,\"reason\":\"audit\"}", k, TENANT);
        assertThat(first.statusCode()).isEqualTo(200);
        HttpResponse<String> replay = send("POST", "/v1/documents/" + id + "/legal-hold", "{\"legal_hold\":true,\"reason\":\"audit\"}", k, TENANT);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(get("/v1/documents/" + id, TENANT).get("legal_hold_reason").asString()).isEqualTo("audit");
        assertThat(events(id)).containsOnlyOnce("platform.document.legal_hold_placed");
        assertThat(code(send("POST", "/v1/documents/" + availableDoc + "/legal-hold",
                "{\"legal_hold\":true,\"reason\":\"audit\"}", k, TENANT), 422)).isEqualTo("idempotency_key_reused");
        assertThat(send("POST", "/v1/documents/" + id + "/archive", null, null, TENANT).statusCode()).isEqualTo(400);
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode approval(Object request) {
        JsonNode req = json.valueToTree(request);
        return json.readTree("""
                {"id":"%s","resource_type":"%s","resource_id":"%s","action":"publish","title":%s,"payload":%s,
                 "priority":"NORMAL","status":"PENDING","maker":"dev-user","required_approvals":1,"approvals_received":0,
                 "current_level":1,"row_version":1,"created_at":"2026-10-06T10:00:00Z","updated_at":"2026-10-06T10:00:00Z"}"""
                .formatted(UUID.randomUUID(), req.get("resource_type").asString(), req.get("resource_id").asString(),
                        json.writeValueAsString(req.get("title").asString()), req.get("payload").toString()));
    }

    private String docBody(String type, String file, String contentType, String issued, String expires) {
        return """
                {"owner_type":"CUSTOMER","owner_id":"%s","document_type":"%s","file_name":"%s","content_type":"%s"%s%s}"""
                .formatted(owner, type, file, contentType,
                        issued == null ? "" : ",\"issued_on\":\"" + issued + "\"",
                        expires == null ? "" : ",\"expires_on\":\"" + expires + "\"");
    }

    private static String templateBody(String code, String body) {
        return """
                {"code":"%s","name":"Welcome letter","category":"LETTER","output_format":"HTML","locale":"en",
                 "body":%s,"variables_schema":{"required":["customer.name"]}}"""
                .formatted(code, "\"" + body.replace("\"", "\\\"") + "\"");
    }

    private String esignBody(String doc, String signers) {
        return """
                {"document_id":"%s","owner_type":"LOAN_APPLICATION","owner_id":"%s","provider":"DOCUSIGN","signers":%s}"""
                .formatted(doc, owner, signers);
    }

    private static String callbackBody(String envelope, String event, String signer) {
        return "{\"envelope_id\":\"" + envelope + "\",\"event\":\"" + event + "\""
                + (signer == null ? "" : ",\"signer_email\":\"" + signer + "\"") + "}";
    }

    private JsonNode callback(String provider, String body) throws Exception {
        HttpResponse<String> r = sendCallback(provider, body, esign.sign(body.getBytes(StandardCharsets.UTF_8)));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private HttpResponse<String> sendCallback(String provider, String body, String signature) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/esign-requests/callbacks/" + provider + "/events"))
                // public: no bearer token, tenant header or Idempotency-Key; only the provider signature
                .header("Content-Type", "application/json").header("X-Esign-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /** Upload completion through the API, then the (internal) virus scanner reports CLEAN. */
    private void uploadClean(String id, long size) throws Exception {
        post("/v1/documents/" + id + "/complete-upload", "{\"size_bytes\":" + size + ",\"checksum\":\"" + SHA + "\"}", 200);
        as(() -> documents.recordScan(UUID.fromString(id), "CLEAN"));
    }

    private List<String> events(String aggregateId) {
        return jdbc.sql("select event_type from outbox where aggregate_id = ?::uuid order by created_at, id")
                .param(aggregateId).query(String.class).list();
    }

    private <T> T as(Callable<T> body) throws Exception {
        return TenantContext.callAs(UUID.fromString(TENANT), "tester", body);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private String code(HttpResponse<String> r, int expected) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);
        return json.readTree(r.body()).path("code").asString();
    }

    private JsonNode post(String path, String body, int expected) throws Exception {
        HttpResponse<String> r = send("POST", path, body, key(), TENANT);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode get(String path, String tenant) throws Exception {
        HttpResponse<String> r = send("GET", path, null, null, tenant);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private HttpResponse<String> sendPatch(String path, String body, int version) throws Exception {
        return sendPatchTo(path, body, version);
    }

    private HttpResponse<String> sendPatchTo(String path, String body, int version) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("Content-Type", "application/json")
                .header("If-Match", "\"" + version + "\"")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode sendPatchJson(String path, String body, int version) throws Exception {
        HttpResponse<String> r = sendPatchTo(path, body, version);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private HttpResponse<String> send(String method, String path, String body, String idemKey, String tenant) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", tenant).header("Content-Type", "application/json");
        if (idemKey != null) {
            b.header("Idempotency-Key", idemKey);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
