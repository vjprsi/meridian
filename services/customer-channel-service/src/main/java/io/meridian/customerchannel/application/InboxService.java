package io.meridian.customerchannel.application;

import static io.meridian.customerchannel.application.AccountService.uuid;
import static io.meridian.customerchannel.application.Ownership.me;
import static io.meridian.customerchannel.application.Ownership.mine;
import static io.meridian.customerchannel.application.ProfileService.required;
import static io.meridian.customerchannel.application.ProfileService.text;

import io.meridian.customerchannel.application.Downstream.Query;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Secure messaging. inbox-service derives the caller from {@code X-On-Behalf-Of-Customer} (sent by ServiceClient for
 * customer requests) and sets sender, opener and reader type itself; the values sent here (CUSTOMER) always agree
 * with it, so older inbox-service versions that still read them from the body behave the same. The customer never
 * sees staff-only notes, may only attach their own documents and may only relate a thread to their own objects.
 */
@Service
public class InboxService {
    private static final String INBOX = "inbox-service";

    private final Downstream down;
    private final Ownership own;
    private final ActionAudit audit;

    public InboxService(Downstream down, Ownership own, ActionAudit audit) {
        this.down = down;
        this.own = own;
        this.audit = audit;
    }

    public JsonNode threads(Integer limit, String cursor) {
        return down.page(down.get(INBOX, "/v1/inbox/threads", Query.of("customer_id", me()).paging(limit, cursor)),
                r -> mine(r, "customer_id"));
    }

    public JsonNode thread(UUID id) {
        return own.thread(id, "myThreadsGet");
    }

    public JsonNode open(JsonNode req, String key) {
        String action = "myThreadsCreate";
        ObjectNode body = down.json().createObjectNode()
                .put("customer_id", me().toString())
                .put("subject", required(req, "subject"))
                .put("topic", text(req, "topic"))
                .put("opened_by", "CUSTOMER")
                .put("initial_message", required(req, "initial_message"));
        UUID related = uuid(req, "related_id", false);
        if (related != null) {
            String type = text(req, "related_type");
            own.related(type, related, action);
            body.put("related_type", type).put("related_id", related.toString());
        }
        body.set("initial_attachment_document_ids", attachments(req, action));
        JsonNode created = down.post(INBOX, "/v1/inbox/threads", body, key, "create");
        audit.succeeded(action, "THREAD", null, created.path("id").asString(null));
        return created;
    }

    public JsonNode messages(UUID id, Integer limit, String cursor) {
        own.thread(id, "myThreadsMessages");
        // include_internal is staff-only in inbox-service; internal notes are filtered here as well
        return down.page(down.get(INBOX, "/v1/inbox/threads/" + id + "/messages",
                        Query.none().paging(limit, cursor)),
                r -> !r.path("is_internal_note").asBoolean(false)
                        && id.toString().equals(r.path("secure_message_thread_id").asString(id.toString())));
    }

    public JsonNode send(UUID id, JsonNode req, String key) {
        String action = "myThreadsAddMessages";
        own.thread(id, action);
        ObjectNode body = down.json().createObjectNode()
                .put("body", required(req, "body"))
                .put("sender_type", "CUSTOMER")
                .put("is_internal_note", false)
                .put("client_message_id", text(req, "client_message_id"));
        body.set("attachment_document_ids", attachments(req, action));
        JsonNode sent = down.post(INBOX, "/v1/inbox/threads/" + id + "/messages", body, key, "message");
        audit.succeeded(action, "THREAD", id, sent.path("id").asString(null));
        return sent;
    }

    public JsonNode read(UUID id, String key) {
        own.thread(id, "myThreadsRead");
        return down.post(INBOX, "/v1/inbox/threads/" + id + "/read",
                down.json().createObjectNode().put("reader_type", "CUSTOMER"), key, "read");
    }

    /** Attachment ids, each one a document owned by the customer. */
    private ArrayNode attachments(JsonNode req, String action) {
        ArrayNode ids = down.json().createArrayNode();
        for (JsonNode n : req.path("attachment_document_ids")) {
            UUID doc = UUID.fromString(n.asString());
            own.document(doc, action);
            ids.add(doc.toString());
        }
        return ids;
    }
}
