package io.meridian.inbox.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.inbox.api.generated.SecureMessageThreadsApi;
import io.meridian.inbox.api.generated.model.SecureMessage;
import io.meridian.inbox.api.generated.model.SecureMessagePage;
import io.meridian.inbox.api.generated.model.SecureMessageThread;
import io.meridian.inbox.api.generated.model.SecureMessageThreadCreate;
import io.meridian.inbox.api.generated.model.SecureMessageThreadPage;
import io.meridian.inbox.api.generated.model.SecureMessageThreadUpdate;
import io.meridian.inbox.api.generated.model.SecureMessageThreadsAssignRequest;
import io.meridian.inbox.api.generated.model.SecureMessageThreadsCloseRequest;
import io.meridian.inbox.api.generated.model.SecureMessageThreadsAddMessagesRequest;
import io.meridian.inbox.api.generated.model.SecureMessageThreadsReadRequest;
import io.meridian.inbox.api.generated.model.SecureMessageThreadsReopenRequest;
import io.meridian.inbox.application.Caller;
import io.meridian.inbox.application.Json;
import io.meridian.inbox.application.ThreadService;
import io.meridian.web.crud.Crud;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Secure customer-bank threads: messages, read receipts, assignment, close / reopen. The caller (customer relayed by
 * the customer channel service, or bank-side) is derived by {@link Callers}; idempotency fingerprints include it so a
 * key replay never crosses callers.
 */
@RestController
public class SecureMessageThreadsController implements SecureMessageThreadsApi {
    private final ThreadService threads;
    private final Http http;
    private final Callers callers;

    public SecureMessageThreadsController(ThreadService threads, Http http, Callers callers) {
        this.threads = threads;
        this.http = http;
        this.callers = callers;
    }

    /** A customer only ever lists its own threads, whatever customer_id filter it sends. */
    @Override
    public ResponseEntity<SecureMessageThreadPage> secureMessageThreadsList(Integer limit, String cursor,
            OffsetDateTime createdAfter, OffsetDateTime createdBefore, UUID customerId, String status, String topic,
            UUID assignedUserId, UUID workQueueId, String customField1, String customField2, String customField3,
            String customField4, String customField5) {
        TenantContext.require();
        Caller caller = callers.current();
        UUID customer = caller.customer() ? caller.customerId() : customerId;
        return Crud.engine().list("secure_message_thread", SecureMessageThreadPage.class, Crud.params()
                .with("limit", limit).with("cursor", cursor).with("created_after", createdAfter)
                .with("created_before", createdBefore).with("customer_id", customer).with("status", status)
                .with("topic", topic).with("assigned_user_id", assignedUserId).with("work_queue_id", workQueueId)
                .with("custom_field_1", customField1).with("custom_field_2", customField2)
                .with("custom_field_3", customField3).with("custom_field_4", customField4)
                .with("custom_field_5", customField5));
    }

    @Override
    public ResponseEntity<SecureMessageThread> secureMessageThreadsGet(UUID id) {
        TenantContext.require();
        return http.withEtag(threads.get(id, callers.current()), SecureMessageThread.class);
    }

    @Override
    public ResponseEntity<SecureMessageThread> secureMessageThreadsCreate(String idempotencyKey, SecureMessageThreadCreate body) {
        Caller caller = callers.current();
        return http.created(idempotencyKey, "threads.create:" + caller.key(), body, SecureMessageThread.class,
                () -> threads.open(body, idempotencyKey, caller));
    }

    @Override
    public ResponseEntity<SecureMessageThread> secureMessageThreadsUpdate(UUID id, String ifMatch, SecureMessageThreadUpdate body) {
        TenantContext.require();
        Caller caller = callers.current();
        int version = Json.parseVersion(ifMatch);
        return http.withEtag(threads.update(id, body, version, caller), SecureMessageThread.class);
    }

    @Override
    public ResponseEntity<SecureMessagePage> secureMessageThreadsMessages(UUID id, Boolean includeInternal, Integer limit,
                                                                         String cursor) {
        TenantContext.require();
        Caller caller = callers.current();
        return ResponseEntity.ok(http.read(threads.messages(id, Boolean.TRUE.equals(includeInternal), limit, cursor, caller),
                SecureMessagePage.class));
    }

    @Override
    public ResponseEntity<SecureMessage> secureMessageThreadsAddMessages(UUID id, String idempotencyKey,
                                                                        SecureMessageThreadsAddMessagesRequest body) {
        Caller caller = callers.current();
        JsonNode req = http.tree(body);
        return http.created(idempotencyKey, "threads.message:" + id + ":" + caller.key(), body, SecureMessage.class,
                () -> threads.send(id, req, idempotencyKey, caller));
    }

    @Override
    public ResponseEntity<SecureMessageThread> secureMessageThreadsRead(UUID id, String idempotencyKey,
                                                                       SecureMessageThreadsReadRequest body) {
        Caller caller = callers.current();
        JsonNode req = http.tree(body);
        return http.ok(idempotencyKey, "threads.read:" + caller.key(), id, body, SecureMessageThread.class,
                () -> threads.read(id, Json.text(req, "reader_type"), Json.uuid(req, "reader_user_id"), caller));
    }

    @Override
    public ResponseEntity<SecureMessageThread> secureMessageThreadsAssign(UUID id, String idempotencyKey,
                                                                         SecureMessageThreadsAssignRequest body) {
        Caller caller = callers.current();
        caller.requireStaff();
        JsonNode req = http.tree(body);
        UUID user = UUID.fromString(Json.required(req, "assigned_user_id"));
        return http.ok(idempotencyKey, "threads.assign", id, body, SecureMessageThread.class,
                () -> threads.assign(id, user, caller));
    }

    @Override
    public ResponseEntity<SecureMessageThread> secureMessageThreadsClose(UUID id, String idempotencyKey,
                                                                        SecureMessageThreadsCloseRequest body) {
        Caller caller = callers.current();
        String reason = Json.text(http.tree(body), "reason");
        return http.ok(idempotencyKey, "threads.close:" + caller.key(), id, body, SecureMessageThread.class,
                () -> threads.close(id, reason, caller));
    }

    @Override
    public ResponseEntity<SecureMessageThread> secureMessageThreadsReopen(UUID id, String idempotencyKey,
                                                                         SecureMessageThreadsReopenRequest body) {
        Caller caller = callers.current();
        String reason = Json.text(http.tree(body), "reason");
        return http.ok(idempotencyKey, "threads.reopen:" + caller.key(), id, body, SecureMessageThread.class,
                () -> threads.reopen(id, reason, caller));
    }
}
