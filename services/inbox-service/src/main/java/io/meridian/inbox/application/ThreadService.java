package io.meridian.inbox.application;

import static io.meridian.inbox.application.Json.text;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.inbox.api.generated.model.SecureMessage;
import io.meridian.inbox.api.generated.model.SecureMessageReceipt;
import io.meridian.inbox.api.generated.model.SecureMessageThread;
import io.meridian.inbox.domain.ThreadRules;
import io.meridian.inbox.infrastructure.ExternalServices;
import io.meridian.inbox.infrastructure.Rows;
import io.meridian.web.crud.CrudEngine;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Secure customer-bank conversations. Messages are append-only; the thread row carries the status machine and the
 * unread counters, and is locked for every message so counters never drift. Attachments are verified with
 * document-service (fail-closed); CRM routing and customer alerts are best effort (fail-open, logged).
 */
@Service
public class ThreadService {
    static final String THREAD = "secure_message_thread";
    static final String MESSAGE = "secure_message";
    static final String RECEIPT = "secure_message_receipt";
    private static final java.util.Set<String> IMMUTABLE_ON_UPDATE = java.util.Set.of("customer_id", "opened_by", "work_queue_id");

    private final Rows rows;
    private final CrudEngine crud;
    private final Events events;
    private final ExternalServices external;
    private final int reopenWindowDays;

    public ThreadService(Rows rows, CrudEngine crud, Events events, ExternalServices external,
                         @Value("${meridian.inbox.reopen-window-days:30}") int reopenWindowDays) {
        this.rows = rows;
        this.crud = crud;
        this.events = events;
        this.external = external;
        this.reopenWindowDays = reopenWindowDays;
    }

    @Transactional
    public ObjectNode open(Object body, String idempotencyKey, Caller caller) {
        JsonNode req = rows.toTree(body);
        ObjectNode v = crud.toColumns(THREAD, body);
        if (text(v, "subject") == null || text(v, "subject").isBlank()) {
            throw ApiException.badRequest("validation_failed", "subject is required");
        }
        UUID customer = caller.customerFor(Json.uuid(v, "customer_id"));
        if (customer == null) {
            throw ApiException.badRequest("validation_failed", "customer_id is required");
        }
        v.put("customer_id", customer.toString());
        // Who opened the thread is the caller, never a body value.
        String openedBy = caller.customer() ? "CUSTOMER" : "STAFF";
        String initial = text(req, "initial_message");
        List<UUID> attachments = uuids(req.get("initial_attachment_document_ids"));
        if (initial == null && !attachments.isEmpty()) {
            throw ApiException.unprocessable("initial_message_required", "Attachments need an initial_message");
        }
        v.put("status", "OPEN");
        v.put("opened_by", openedBy);
        ObjectNode thread = rows.insert(THREAD, v);
        UUID id = Json.uuid(thread, "id");
        if (text(v, "work_queue_id") != null) {
            Optional<UUID> item = external.openWorkItem(Json.uuid(v, "work_queue_id"), id,
                    Json.truncate("Secure message: " + text(v, "subject"), 255), "inbox-thread-work-item:" + id);
            if (item.isPresent()) {
                ObjectNode w = rows.object();
                w.put("work_item_id", item.get().toString());
                thread = rows.update(THREAD, id, w);
            }
        }
        events.publish("engagement.secure_message_thread.opened", THREAD, thread, SecureMessageThread.class);
        if (initial != null) {
            ObjectNode m = rows.object();
            m.put("body", initial);
            m.put("sender_type", openedBy);
            ArrayNode a = m.putArray("attachment_document_ids");
            attachments.forEach(x -> a.add(x.toString()));
            send(id, m, idempotencyKey == null ? null : "inbox-initial:" + idempotencyKey, caller);
            thread = rows.get(THREAD, id);
        }
        return thread;
    }

    @Transactional
    public ObjectNode update(UUID id, Object body, int expectedVersion, Caller caller) {
        caller.requireStaff();
        ObjectNode current = rows.get(THREAD, id);
        ObjectNode v = crud.toColumns(THREAD, body);
        for (String f : IMMUTABLE_ON_UPDATE) {
            if (v.has(f) && !v.get(f).asString().equals(text(current, f))) {
                throw ApiException.unprocessable("immutable_field", f + " cannot be changed after the thread is opened");
            }
            v.remove(f);
        }
        if ("CLOSED".equals(text(current, "status"))) {
            throw ApiException.conflict("thread_closed", "A closed thread cannot be edited");
        }
        if (v.isEmpty()) {
            return current;
        }
        return rows.update(THREAD, id, v, expectedVersion).orElseThrow(() -> Json.versionConflict(expectedVersion));
    }

    /**
     * Sends a message: checks the thread state and sender, verifies attachments, de-duplicates on client_message_id,
     * moves the status, updates the counters and alerts the customer for bank-side messages.
     */
    @Transactional
    public ObjectNode send(UUID threadId, JsonNode req, String key, Caller caller) {
        ObjectNode thread = rows.lock(THREAD, threadId);
        caller.requireOwner("secure message thread", threadId, text(thread, "customer_id"));
        String body = text(req, "body");
        if (body == null || body.isBlank()) {
            throw ApiException.badRequest("validation_failed", "body is required");
        }
        String sender = ThreadRules.senderFor(caller.customer(), text(req, "sender_type"));
        if (sender == null) {
            throw ApiException.unprocessable("sender_mismatch", "sender_type must match the caller ("
                    + (caller.customer() ? "CUSTOMER" : "STAFF, SYSTEM or BOT") + ")");
        }
        UUID senderUser = caller.userId();
        UUID claimedUser = Json.uuid(req, "sender_user_id");
        if (claimedUser != null && !claimedUser.equals(senderUser)) {
            throw ApiException.unprocessable("sender_mismatch", "sender_user_id must be the calling staff user");
        }
        boolean internal = Json.bool(req, "is_internal_note");
        String clientId = text(req, "client_message_id");
        if (clientId != null) {
            Optional<ObjectNode> dup = rows.query("""
                    select row_to_json(t)::text from secure_message t
                    where t.secure_message_thread_id = ? and t.client_message_id = ?""", threadId, clientId)
                    .stream().findFirst();
            if (dup.isPresent()) {
                return dup.get();
            }
        }
        ThreadRules.Check check = ThreadRules.canSend(text(thread, "status"), sender, internal);
        if (!check.allowed()) {
            throw "thread_closed".equals(check.error()) ? ApiException.conflict(check.error(), check.detail())
                    : ApiException.unprocessable(check.error(), check.detail());
        }
        List<UUID> attachments = uuids(req.get("attachment_document_ids"));
        if (attachments.size() > 10) {
            throw ApiException.unprocessable("too_many_attachments", "At most 10 attachments per message");
        }
        attachments.forEach(external::requireAvailableDocument);

        Boolean forCustomer = ThreadRules.unreadForCustomer(sender, internal);
        ObjectNode m = rows.object();
        m.put("secure_message_thread_id", threadId.toString());
        m.put("sender_type", sender);
        if (senderUser != null && !"CUSTOMER".equals(sender)) {
            m.put("sender_user_id", senderUser.toString());
        }
        m.put("body", body);
        ArrayNode att = m.putArray("attachment_document_ids");
        attachments.forEach(a -> att.add(a.toString()));
        m.put("is_internal_note", internal);
        if (clientId != null) {
            m.put("client_message_id", Json.truncate(clientId, 64));
        }
        if (Boolean.TRUE.equals(forCustomer)) {
            external.notifyCustomer(Json.uuid(thread, "customer_id"), threadId, text(thread, "subject"),
                    key == null ? null : "inbox-alert:" + key)
                    .ifPresent(n -> m.put("notification_id", n.toString()));
        }
        ObjectNode message = rows.insert(MESSAGE, m);

        ObjectNode t = rows.object();
        t.put("status", ThreadRules.statusAfter(text(thread, "status"), sender, internal));
        t.put("message_count", thread.path("message_count").asInt() + 1);
        if (forCustomer != null) {
            t.put("last_message_at", text(message, "created_at"));
            t.put("last_sender_type", sender);
            if (forCustomer) {
                t.put("unread_customer", thread.path("unread_customer").asInt() + 1);
            } else {
                t.put("unread_staff", thread.path("unread_staff").asInt() + 1);
            }
        }
        rows.update(THREAD, threadId, t);
        events.publish("engagement.secure_message.sent", MESSAGE, message, SecureMessage.class);
        return message;
    }

    /** Marks every unread message of the other party read for the caller; one immutable receipt per message. */
    @Transactional
    public ObjectNode read(UUID threadId, String readerType, UUID readerUserId, Caller caller) {
        ObjectNode thread = rows.lock(THREAD, threadId);
        caller.requireOwner("secure message thread", threadId, text(thread, "customer_id"));
        if (readerType != null && !ThreadRules.SENDERS.contains(readerType)) {
            throw ApiException.badRequest("validation_failed", "reader_type must be one of " + ThreadRules.SENDERS);
        }
        // The reader is the caller; a body value may only confirm it.
        String reader = caller.customer() ? "CUSTOMER" : "STAFF";
        if (readerType != null && !reader.equals("CUSTOMER".equals(readerType) ? "CUSTOMER" : "STAFF")) {
            throw ApiException.unprocessable("reader_mismatch", "reader_type must match the caller (" + reader + ")");
        }
        UUID readerUser = caller.userId();
        if (readerUserId != null && !readerUserId.equals(readerUser)) {
            throw ApiException.unprocessable("reader_mismatch", "reader_user_id must be the calling staff user");
        }
        String user = readerUser == null ? null : readerUser.toString();
        List<ObjectNode> messages = rows.query("""
                select row_to_json(t)::text from secure_message t where t.secure_message_thread_id = ?
                order by t.created_at, t.id""", threadId);
        for (ObjectNode msg : messages) {
            if (!ThreadRules.marksAsRead(reader, text(msg, "sender_type"), Json.bool(msg, "is_internal_note"))) {
                continue;
            }
            UUID msgId = Json.uuid(msg, "id");
            List<ObjectNode> inserted = rows.query("""
                    insert into secure_message_receipt as t (id, tenant_id, secure_message_id, reader_type, reader_user_id,
                                                         created_by)
                    select uuidv7(), ?::uuid, ?::uuid, ?, ?::uuid, ?
                    where not exists (select 1 from secure_message_receipt r where r.secure_message_id = ?::uuid
                                        and r.reader_type = ? and r.reader_user_id is not distinct from ?::uuid)
                    returning row_to_json(t)::text""",
                    TenantContext.require().toString(), msgId.toString(), reader, user, TenantContext.principal(),
                    msgId.toString(), reader, user);
            inserted.forEach(r -> events.publish("engagement.secure_message.read", RECEIPT, r, SecureMessageReceipt.class));
        }
        ObjectNode t = rows.object();
        t.put("CUSTOMER".equals(reader) ? "unread_customer" : "unread_staff", 0);
        return rows.update(THREAD, threadId, t);
    }

    @Transactional
    public ObjectNode assign(UUID id, UUID userId, Caller caller) {
        caller.requireStaff();
        ObjectNode thread = rows.lock(THREAD, id);
        ThreadRules.Check c = ThreadRules.canAssign(text(thread, "status"));
        if (!c.allowed()) {
            throw ApiException.conflict(c.error(), c.detail());
        }
        ObjectNode v = rows.object();
        v.put("assigned_user_id", userId.toString());
        ObjectNode row = rows.update(THREAD, id, v);
        external.workItemAction(Json.uuid(thread, "work_item_id"), "assign", Map.of("assigned_to", userId.toString()),
                "inbox-assign:" + id + ":" + userId);
        events.publish("engagement.secure_message_thread.assigned", THREAD, row, SecureMessageThread.class);
        return row;
    }

    @Transactional
    public ObjectNode close(UUID id, String reason, Caller caller) {
        ObjectNode thread = rows.lock(THREAD, id);
        caller.requireOwner("secure message thread", id, text(thread, "customer_id"));
        ThreadRules.Check c = ThreadRules.canClose(text(thread, "status"));
        if (!c.allowed()) {
            throw ApiException.conflict(c.error(), c.detail());
        }
        ObjectNode v = rows.object();
        v.put("status", "CLOSED");
        v.put("closed_at", rows.now().toString());
        v.put("closed_by", Json.truncate(TenantContext.principal(), 64));
        ObjectNode row = rows.update(THREAD, id, v);
        external.workItemAction(Json.uuid(thread, "work_item_id"), "complete", null,
                "inbox-close:" + id + ":" + thread.path("reopened_count").asInt());
        events.publish("engagement.secure_message_thread.closed", THREAD, row, SecureMessageThread.class);
        return row;
    }

    @Transactional
    public ObjectNode reopen(UUID id, String reason, Caller caller) {
        ObjectNode thread = rows.lock(THREAD, id);
        caller.requireOwner("secure message thread", id, text(thread, "customer_id"));
        ThreadRules.Check c = ThreadRules.canReopen(text(thread, "status"), Json.ts(thread, "closed_at"), rows.now(),
                reopenWindowDays);
        if (!c.allowed()) {
            throw ApiException.conflict(c.error(), c.detail());
        }
        ObjectNode v = rows.object();
        v.put("status", "OPEN");
        v.putNull("closed_at");
        v.putNull("closed_by");
        v.put("reopened_count", thread.path("reopened_count").asInt() + 1);
        ObjectNode row = rows.update(THREAD, id, v);
        events.publish("engagement.secure_message_thread.reopened", THREAD, row, SecureMessageThread.class);
        return row;
    }

    public JsonNode messages(UUID threadId, boolean includeInternal, Integer limit, String cursor, Caller caller) {
        ObjectNode thread = rows.get(THREAD, threadId);
        caller.requireOwner("secure message thread", threadId, text(thread, "customer_id"));
        if (includeInternal) {
            caller.requireStaff(); // internal notes are staff-only
        }
        List<Object> args = new ArrayList<>();
        args.add(threadId);
        return rows.page("select row_to_json(t)::text from secure_message t where t.secure_message_thread_id = ?"
                + (includeInternal ? "" : " and not t.is_internal_note"), args, limit, cursor);
    }

    /** A thread as seen by the caller: another customer's thread is 404 for a customer. */
    public ObjectNode get(UUID id, Caller caller) {
        ObjectNode thread = rows.get(THREAD, id);
        caller.requireOwner("secure message thread", id, text(thread, "customer_id"));
        return thread;
    }

    public JsonNode receipts(UUID messageId, Integer limit, String cursor) {
        rows.get(MESSAGE, messageId);
        return rows.page("select row_to_json(t)::text from secure_message_receipt t where t.secure_message_id = ?",
                new ArrayList<>(List.of(messageId)), limit, cursor);
    }

    static List<UUID> uuids(JsonNode n) {
        LinkedHashSet<UUID> out = new LinkedHashSet<>();
        Json.strings(n).forEach(s -> out.add(UUID.fromString(s)));
        return new ArrayList<>(out);
    }
}
