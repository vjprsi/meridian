package io.meridian.workflow.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.events.OutboxWriter;
import io.meridian.workflow.domain.ApprovalRoute;
import io.meridian.workflow.domain.Caller;
import io.meridian.workflow.domain.StateMachine;
import io.meridian.workflow.infrastructure.WorkflowStore;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Maker-checker approvals. A request is routed through the applicable levels of its policy (amount thresholds);
 * each level needs its number of approvals from distinct checkers holding one of the level's roles. The maker can
 * never approve (unless the policy allows self-approval and no amount is involved), and nobody decides twice.
 * The final approval sets APPROVED, publishes {@code platform.approval_request.approved} and, when the operation is
 * owned by this service and the policy auto-executes, executes it (EXECUTED / EXECUTION_FAILED).
 */
@Service
public class ApprovalService {
    static final String TABLE = "approval_request";
    private static final int DEFAULT_EXPIRY_HOURS = 72;

    private final WorkflowStore store;
    private final OutboxWriter outbox;
    private final JsonMapper json;
    private final Map<String, ApprovalExecutor> executors;
    private final TransactionTemplate nested;

    public ApprovalService(WorkflowStore store, OutboxWriter outbox, JsonMapper json, List<ApprovalExecutor> executors,
                           PlatformTransactionManager txManager) {
        this.store = store;
        this.outbox = outbox;
        this.json = json;
        this.executors = executors.stream().collect(Collectors.toMap(e -> key(e.resourceType(), e.action()), Function.identity()));
        this.nested = new TransactionTemplate(txManager);
        this.nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    // ------------------------------------------------------------------ policies

    /** Validates a policy's level configuration (create and update). */
    public void validatePolicy(JsonNode approvalLevels, Integer requiredApprovals, Integer expiresAfterHours) {
        ApprovalRoute.parsePolicyLevels(approvalLevels, requiredApprovals == null ? 1 : requiredApprovals);
        if (expiresAfterHours != null && (expiresAfterHours < 1 || expiresAfterHours > 24 * 90)) {
            throw ApiException.unprocessable("invalid_expiry", "expires_after_hours must be between 1 and 2160");
        }
    }

    // ------------------------------------------------------------------ create

    @Transactional
    public ObjectNode create(ObjectNode values, Caller caller) {
        for (String ro : List.of("status", "maker", "required_approvals", "approvals_received", "current_level",
                "expires_at", "decided_at", "executed_at", "execution_result")) {
            values.remove(ro);
        }
        String resourceType = values.path("resource_type").asString("");
        String action = values.path("action").asString("");
        if (resourceType.isBlank() || action.isBlank()) {
            throw ApiException.unprocessable("validation_failed", "resource_type and action are required");
        }
        if (!values.path("payload").isObject()) {
            throw ApiException.unprocessable("validation_failed", "payload must be an object describing the operation");
        }
        BigDecimal amount = null;
        if (values.hasNonNull("amount")) {
            amount = new BigDecimal(values.get("amount").asString());
            if (amount.signum() < 0) {
                throw ApiException.unprocessable("invalid_amount", "amount must not be negative");
            }
            String ccy = values.path("currency").asString("");
            if (!ccy.matches("[A-Z]{3}")) {
                throw ApiException.unprocessable("currency_required", "currency (ISO 4217) is required with an amount");
            }
        }

        Optional<ObjectNode> policy;
        if (values.hasNonNull("approval_policy_id")) {
            UUID policyId = UUID.fromString(values.get("approval_policy_id").asString());
            ObjectNode p = store.find("approval_policy", policyId)
                    .orElseThrow(() -> ApiException.unprocessable("unknown_policy", "Approval policy " + policyId + " does not exist"));
            if (!p.path("is_active").asBoolean()) {
                throw ApiException.unprocessable("policy_inactive", "Approval policy " + p.path("code").asString() + " is inactive");
            }
            if (!resourceType.equals(p.path("resource_type").asString()) || !action.equals(p.path("action").asString())) {
                throw ApiException.unprocessable("policy_mismatch",
                        "Policy applies to " + p.path("resource_type").asString() + "/" + p.path("action").asString());
            }
            policy = Optional.of(p);
        } else {
            policy = store.activePolicy(resourceType, action);
        }

        ApprovalRoute route;
        int expiryHours = DEFAULT_EXPIRY_HOURS;
        if (policy.isPresent()) {
            ObjectNode p = policy.get();
            route = ApprovalRoute.forAmount(
                    ApprovalRoute.parsePolicyLevels(p.get("approval_levels"), p.path("required_approvals").asInt(1)), amount);
            expiryHours = p.path("expires_after_hours").asInt(DEFAULT_EXPIRY_HOURS);
            values.put("approval_policy_id", p.get("id").asString());
        } else {
            route = ApprovalRoute.single(1);
        }

        UUID id = Ids.newId();
        values.put("id", id.toString());
        values.put("status", "PENDING");
        values.put("maker", caller.principal());
        values.put("required_approvals", route.totalRequiredApprovals());
        values.put("approvals_received", 0);
        values.put("current_level", 1);
        values.put("expires_at", now().plusHours(expiryHours).toString());
        ObjectNode result = json.createObjectNode();
        result.set("approval_route", route.toJson());
        values.set("execution_result", result);
        ObjectNode row = store.insert(TABLE, values);
        outbox.publish("platform.approval_request.created", TABLE, id, row);
        return row;
    }

    /** Opens a request for an operation of this service (e.g. publishing a workflow definition). */
    @Transactional
    public ObjectNode request(String resourceType, UUID resourceId, String action, String title, String summary,
                              ObjectNode payload, Caller caller) {
        if (store.hasPendingRequest(resourceType, resourceId, action)) {
            throw ApiException.conflict("approval_already_pending",
                    "An approval request for " + action + " of " + resourceType + " " + resourceId + " is already pending");
        }
        ObjectNode values = json.createObjectNode();
        values.put("resource_type", resourceType);
        values.put("resource_id", resourceId.toString());
        values.put("action", action);
        values.put("title", title);
        if (summary != null) {
            values.put("summary", summary);
        }
        values.set("payload", payload);
        return create(values, caller);
    }

    // ------------------------------------------------------------------ decisions

    @Transactional
    public ObjectNode approve(UUID id, String comment, Caller caller) {
        ObjectNode r = lockPending(id);
        ObjectNode policy = policyOf(r);
        checkChecker(r, policy, caller, true);
        ApprovalRoute route = ApprovalRoute.fromJson(r.path("execution_result").get("approval_route"));
        int position = r.path("current_level").asInt(1);
        ApprovalRoute.Level level = route.at(position);
        if (!level.allows(caller.roles())) {
            throw new ApiException(403, "not_authorized_approver",
                    "Level " + position + " must be approved by one of " + level.approverRoles());
        }
        if (!caller.mfaVerified()) {
            throw new ApiException(403, "mfa_required", "Approving requires a step-up MFA verified session");
        }
        decide(id, caller, position, "APPROVE", comment);
        int atLevel = store.approvalsAtLevel(id, position);

        ObjectNode values = json.createObjectNode();
        values.put("approvals_received", r.path("approvals_received").asInt() + 1);
        boolean finalApproval = false;
        if (atLevel >= level.requiredApprovals()) {
            if (route.isLast(position)) {
                StateMachine.APPROVAL_REQUEST.require("PENDING", "APPROVED");
                values.put("status", "APPROVED");
                values.put("decided_at", now().toString());
                finalApproval = true;
            } else {
                values.put("current_level", position + 1);
            }
        }
        ObjectNode row = store.update(TABLE, id, values);
        if (!finalApproval) {
            return row;
        }
        outbox.publish("platform.approval_request.approved", TABLE, id, row);
        boolean autoExecute = policy == null || policy.path("auto_execute").asBoolean(true);
        ApprovalExecutor executor = executors.get(key(row.path("resource_type").asString(), row.path("action").asString()));
        if (autoExecute && executor != null) {
            return execute(row, executor);
        }
        return row;
    }

    @Transactional
    public ObjectNode reject(UUID id, String comment, Caller caller) {
        return close(id, comment, caller, "REJECT");
    }

    /**
     * Sends the request back to the maker. The status model has no "changes requested" state, so the request is
     * closed as REJECTED with a REQUEST_CHANGES decision; the maker raises a new request with the corrections.
     */
    @Transactional
    public ObjectNode requestChanges(UUID id, String comment, Caller caller) {
        return close(id, comment, caller, "REQUEST_CHANGES");
    }

    @Transactional
    public ObjectNode cancel(UUID id, String reason, Caller caller) {
        ObjectNode r = store.lock(TABLE, id);
        StateMachine.APPROVAL_REQUEST.require(r.path("status").asString(), "CANCELLED");
        if (!caller.principal().equals(r.path("maker").asString())) {
            throw new ApiException(403, "not_maker", "Only the maker " + r.path("maker").asString() + " can cancel this request");
        }
        ObjectNode values = json.createObjectNode();
        values.put("status", "CANCELLED");
        values.put("decided_at", now().toString());
        ObjectNode result = ((ObjectNode) r.path("execution_result")).deepCopy();
        if (reason != null) {
            result.put("cancel_reason", reason);
        }
        values.set("execution_result", result);
        ObjectNode row = store.update(TABLE, id, values);
        abandoned(row);
        return row;
    }

    /** Expires a pending request whose deadline passed (called by the sweeper). */
    @Transactional
    public Optional<ObjectNode> expire(UUID id) {
        ObjectNode r = store.lock(TABLE, id);
        if (!"PENDING".equals(r.path("status").asString()) || !isExpired(r)) {
            return Optional.empty();
        }
        ObjectNode values = json.createObjectNode();
        values.put("status", "EXPIRED");
        values.put("decided_at", now().toString());
        ObjectNode row = store.update(TABLE, id, values);
        outbox.publish("platform.approval_request.expired", TABLE, id, row);
        abandoned(row);
        return Optional.of(row);
    }

    public List<JsonNode> decisions(UUID id) {
        store.get(TABLE, id);
        return store.decisions(id);
    }

    /** Page JSON of the requests the caller can decide now. */
    public String inbox(Caller caller, Integer limit, String cursor) {
        return store.inbox(caller.principal(), caller.roles(), limit, cursor);
    }

    public void requireExists(UUID id) {
        store.get(TABLE, id);
    }

    // ------------------------------------------------------------------ internals

    private ObjectNode close(UUID id, String comment, Caller caller, String decision) {
        if (comment == null || comment.isBlank()) {
            throw ApiException.unprocessable("comment_required", "A comment is required to " + decision.toLowerCase().replace('_', ' '));
        }
        ObjectNode r = lockPending(id);
        ObjectNode policy = policyOf(r);
        checkChecker(r, policy, caller, false);
        ApprovalRoute route = ApprovalRoute.fromJson(r.path("execution_result").get("approval_route"));
        int position = r.path("current_level").asInt(1);
        if (!route.at(position).allows(caller.roles())) {
            throw new ApiException(403, "not_authorized_approver",
                    "Level " + position + " can only be decided by one of " + route.at(position).approverRoles());
        }
        decide(id, caller, position, decision, comment);
        StateMachine.APPROVAL_REQUEST.require("PENDING", "REJECTED");
        ObjectNode values = json.createObjectNode();
        values.put("status", "REJECTED");
        values.put("decided_at", now().toString());
        ObjectNode row = store.update(TABLE, id, values);
        outbox.publish("platform.approval_request.rejected", TABLE, id, row);
        abandoned(row);
        return row;
    }

    private ObjectNode lockPending(UUID id) {
        ObjectNode r = store.lock(TABLE, id);
        String status = r.path("status").asString();
        if (!"PENDING".equals(status)) {
            throw ApiException.conflict("invalid_transition", "Approval request " + id + " is " + status + ", not PENDING");
        }
        if (isExpired(r)) {
            throw ApiException.conflict("approval_request_expired", "Approval request " + id + " has expired");
        }
        return r;
    }

    /** Four-eyes: the maker never decides (unless self-approval is allowed and no money is involved); one decision per user. */
    private void checkChecker(ObjectNode r, ObjectNode policy, Caller caller, boolean approving) {
        boolean selfAllowed = approving && policy != null && policy.path("allow_self_approval").asBoolean(false)
                && r.path("amount").isNull();
        if (caller.principal().equals(r.path("maker").asString()) && !selfAllowed) {
            throw ApiException.unprocessable("four_eyes_violation",
                    "The maker of a request cannot " + (approving ? "approve" : "decide on") + " it");
        }
        if (store.hasDecided(UUID.fromString(r.get("id").asString()), caller.principal())) {
            throw ApiException.unprocessable("four_eyes_violation",
                    caller.principal() + " has already decided on this request; another checker is required");
        }
    }

    private void decide(UUID id, Caller caller, int level, String decision, String comment) {
        ObjectNode d = json.createObjectNode();
        d.put("approval_request_id", id.toString());
        d.put("approver", caller.principal());
        d.put("level", level);
        d.put("decision", decision);
        if (comment != null) {
            d.put("comment", comment);
        }
        d.put("mfa_verified", caller.mfaVerified());
        store.insert("approval_decision", d);
    }

    private ObjectNode execute(ObjectNode approved, ApprovalExecutor executor) {
        UUID id = UUID.fromString(approved.get("id").asString());
        ObjectNode result = ((ObjectNode) approved.path("execution_result")).deepCopy();
        String status;
        try {
            JsonNode out = nested.execute(s -> executor.execute(approved));
            result.set("result", out);
            status = "EXECUTED";
        } catch (RuntimeException e) {
            ObjectNode err = result.putObject("error");
            err.put("code", e instanceof ApiException a ? a.code() : "execution_error");
            err.put("message", String.valueOf(e.getMessage()));
            status = "EXECUTION_FAILED";
        }
        StateMachine.APPROVAL_REQUEST.require("APPROVED", status);
        ObjectNode values = json.createObjectNode();
        values.put("status", status);
        values.set("execution_result", result);
        if ("EXECUTED".equals(status)) {
            values.put("executed_at", now().toString());
        }
        ObjectNode row = store.update(TABLE, id, values);
        if ("EXECUTED".equals(status)) {
            outbox.publish("platform.approval_request.executed", TABLE, id, row);
        }
        return row;
    }

    private void abandoned(ObjectNode row) {
        ApprovalExecutor executor = executors.get(key(row.path("resource_type").asString(), row.path("action").asString()));
        if (executor != null) {
            executor.abandoned(row);
        }
    }

    private ObjectNode policyOf(ObjectNode r) {
        JsonNode pid = r.get("approval_policy_id");
        if (pid == null || pid.isNull()) {
            return null;
        }
        return store.find("approval_policy", UUID.fromString(pid.asString())).orElse(null);
    }

    private static boolean isExpired(JsonNode r) {
        JsonNode exp = r.get("expires_at");
        return exp != null && !exp.isNull() && OffsetDateTime.parse(exp.asString()).isBefore(now());
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private static String key(String resourceType, String action) {
        return resourceType + "/" + action;
    }
}
