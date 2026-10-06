package io.meridian.inbox.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.inbox.api.generated.SecureMessageThreadsApi;
import io.meridian.inbox.api.generated.model.SecureMessage;
import io.meridian.inbox.api.generated.model.SecureMessagePage;
import io.meridian.inbox.api.generated.model.SecureMessageThread;
import io.meridian.inbox.api.generated.model.SecureMessageThreadCreate;
import io.meridian.inbox.api.generated.model.SecureMessageThreadUpdate;
import io.meridian.inbox.api.generated.model.SecureMessageThreadsAssignRequest;
import io.meridian.inbox.api.generated.model.SecureMessageThreadsCloseRequest;
import io.meridian.inbox.api.generated.model.SecureMessageThreadsMessagesRequest;
import io.meridian.inbox.api.generated.model.SecureMessageThreadsReadRequest;
import io.meridian.inbox.api.generated.model.SecureMessageThreadsReopenRequest;
import io.meridian.inbox.application.Json;
import io.meridian.inbox.application.ThreadService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Secure customer-bank threads: messages, read receipts, assignment, close / reopen. */
@RestController
public class SecureMessageThreadsController implements SecureMessageThreadsApi {
    private final ThreadService threads;
    private final Http http;

    public SecureMessageThreadsController(ThreadService threads, Http http) {
        this.threads = threads;
        this.http = http;
    }

    @Override
    public ResponseEntity<SecureMessageThread> secureMessageThreadsCreate(String idempotencyKey, SecureMessageThreadCreate body) {
        return http.created(idempotencyKey, "threads.create", body, SecureMessageThread.class,
                () -> threads.open(body, idempotencyKey));
    }

    @Override
    public ResponseEntity<SecureMessageThread> secureMessageThreadsUpdate(UUID id, String ifMatch, SecureMessageThreadUpdate body) {
        TenantContext.require();
        int version = Json.parseVersion(ifMatch);
        return http.withEtag(threads.update(id, body, version), SecureMessageThread.class);
    }

    @Override
    public ResponseEntity<SecureMessagePage> secureMessageThreadsMessages(UUID id, Boolean includeInternal, Integer limit,
                                                                         String cursor) {
        TenantContext.require();
        return ResponseEntity.ok(http.read(threads.messages(id, Boolean.TRUE.equals(includeInternal), limit, cursor),
                SecureMessagePage.class));
    }

    @Override
    public ResponseEntity<SecureMessage> secureMessageThreadsAddMessages(UUID id, String idempotencyKey,
                                                                        SecureMessageThreadsMessagesRequest body) {
        JsonNode req = http.tree(body);
        return http.created(idempotencyKey, "threads.message:" + id, body, SecureMessage.class,
                () -> threads.send(id, req, idempotencyKey));
    }

    @Override
    public ResponseEntity<SecureMessageThread> secureMessageThreadsRead(UUID id, String idempotencyKey,
                                                                       SecureMessageThreadsReadRequest body) {
        JsonNode req = http.tree(body);
        return http.ok(idempotencyKey, "threads.read", id, body, SecureMessageThread.class,
                () -> threads.read(id, Json.required(req, "reader_type"), Json.uuid(req, "reader_user_id")));
    }

    @Override
    public ResponseEntity<SecureMessageThread> secureMessageThreadsAssign(UUID id, String idempotencyKey,
                                                                         SecureMessageThreadsAssignRequest body) {
        JsonNode req = http.tree(body);
        UUID user = UUID.fromString(Json.required(req, "assigned_user_id"));
        return http.ok(idempotencyKey, "threads.assign", id, body, SecureMessageThread.class, () -> threads.assign(id, user));
    }

    @Override
    public ResponseEntity<SecureMessageThread> secureMessageThreadsClose(UUID id, String idempotencyKey,
                                                                        SecureMessageThreadsCloseRequest body) {
        String reason = Json.text(http.tree(body), "reason");
        return http.ok(idempotencyKey, "threads.close", id, body, SecureMessageThread.class, () -> threads.close(id, reason));
    }

    @Override
    public ResponseEntity<SecureMessageThread> secureMessageThreadsReopen(UUID id, String idempotencyKey,
                                                                         SecureMessageThreadsReopenRequest body) {
        String reason = Json.text(http.tree(body), "reason");
        return http.ok(idempotencyKey, "threads.reopen", id, body, SecureMessageThread.class, () -> threads.reopen(id, reason));
    }
}
