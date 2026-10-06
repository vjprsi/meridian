package io.meridian.aiplatform.application;

import io.meridian.aiplatform.domain.Lifecycles;
import io.meridian.aiplatform.domain.PiiRedactor;
import io.meridian.aiplatform.domain.Retriever;
import io.meridian.aiplatform.infrastructure.Store;
import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Copilots: definitions with allowed tools and data scopes, activation gated by the LLM gateway policy, and the
 * audited ask flow. Every turn passes the gateway rules (opt-in, allowed model, PII redaction before anything leaves
 * the bank, per-request token cap, monthly budget), is answered by the deterministic retrieval stub with citations,
 * and is recorded append-only with SHA-256 hashes of the original prompt and response. Write tools are only ever
 * proposed; a human approves or rejects them in a separate audit record.
 */
@Service
public class CopilotService {
    static final String MODEL_VERSION = "retrieval-stub-1";
    static final int MAX_CONTEXT = 50;
    /** Approval resource type for copilot-proposed actions in workflow-service (G11). */
    public static final String APPROVAL_RESOURCE = "copilot_action";

    private final Store store;
    private final CrudEngine crud;
    private final OutboxWriter outbox;
    private final JsonMapper json;
    private final PolicyService policies;
    private final Approvals approvals;

    public CopilotService(Store store, CrudEngine crud, OutboxWriter outbox, JsonMapper json, PolicyService policies,
                          Approvals approvals) {
        this.approvals = approvals;
        this.store = store;
        this.crud = crud;
        this.outbox = outbox;
        this.json = json;
        this.policies = policies;
    }

    /** Definition checks (create / update merged with the existing row). */
    public void prepare(ObjectNode values, JsonNode existing) {
        JsonNode merged = existing == null ? values : ((ObjectNode) existing.deepCopy()).setAll(values);
        tools(merged.get("allowed_tools"));
        UUID policy = Fields.uuid(merged, "llm_gateway_policy_id");
        if (policy != null && store.find("llm_gateway_policy", policy).isEmpty()) {
            throw ApiException.unprocessable("policy_not_found", "LLM gateway policy " + policy + " does not exist");
        }
    }

    List<Retriever.Tool> tools(JsonNode raw) {
        if (raw == null || !raw.isArray()) {
            throw Fields.invalid("allowed_tools", "required", "allowed_tools must be a list of {name, mode, keywords}");
        }
        List<Retriever.Tool> out = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (JsonNode t : raw) {
            String name = t.path("name").asString("");
            String mode = t.path("mode").asString("READ");
            if (!name.matches("[a-z][a-z0-9_]{1,63}") || !names.add(name)) {
                throw Fields.invalid("allowed_tools", "invalid_tool", "tool names must be unique snake_case identifiers: '" + name + "'");
            }
            if (!Set.of("READ", "WRITE").contains(mode)) {
                throw Fields.invalid("allowed_tools", "invalid_tool", "tool " + name + " mode must be READ or WRITE");
            }
            List<String> keywords = new ArrayList<>();
            t.path("keywords").forEach(k -> keywords.add(k.asString()));
            out.add(new Retriever.Tool(name, mode, keywords));
        }
        return out;
    }

    @Transactional
    public String activate(UUID id, String comment) {
        JsonNode c = store.requireLocked("copilot_definition", id);
        Lifecycles.copilot(c.get("status").asString(), "ACTIVE");
        JsonNode policy = requireUsablePolicy(c);
        JsonNode row = store.updateReturning("update copilot_definition t set status = 'ACTIVE', updated_by = ? where t.id = ? "
                + "returning row_to_json(t)::text", TenantContext.principal(), id).orElseThrow();
        ObjectNode payload = ((ObjectNode) row).deepCopy();
        payload.put("llm_gateway_policy_name", policy.get("name").asString());
        if (comment != null) {
            payload.put("comment", comment);
        }
        outbox.publish("reporting.copilot.activated", "copilot_definition", id, payload);
        return row.toString();
    }

    @Transactional
    public String disable(UUID id, String reason) {
        JsonNode c = store.requireLocked("copilot_definition", id);
        Lifecycles.copilot(c.get("status").asString(), "DISABLED");
        JsonNode row = store.updateReturning("update copilot_definition t set status = 'DISABLED', updated_by = ? where t.id = ? "
                + "returning row_to_json(t)::text", TenantContext.principal(), id).orElseThrow();
        ObjectNode payload = ((ObjectNode) row).deepCopy();
        payload.put("reason", reason);
        outbox.publish("reporting.copilot.disabled", "copilot_definition", id, payload);
        return row.toString();
    }

    private JsonNode requireUsablePolicy(JsonNode copilot) {
        UUID policyId = Fields.uuid(copilot, "llm_gateway_policy_id");
        if (policyId == null) {
            throw ApiException.unprocessable("policy_required", "An LLM gateway policy must be assigned before activation");
        }
        JsonNode p = store.require("llm_gateway_policy", policyId);
        if (!p.path("is_active").asBoolean(false)) {
            throw ApiException.unprocessable("policy_inactive", "The LLM gateway policy is not active");
        }
        if (!p.path("tenant_opt_in").asBoolean(false)) {
            throw ApiException.unprocessable("tenant_not_opted_in", "The tenant has not opted in to LLM features");
        }
        String alias = copilot.get("model_alias").asString();
        boolean allowed = false;
        for (JsonNode m : p.path("allowed_models")) {
            allowed |= alias.equals(m.asString());
        }
        if (!allowed) {
            throw ApiException.unprocessable("model_not_allowed", "Model " + alias + " is not allowed by the gateway policy");
        }
        return p;
    }

    /** One copilot turn. */
    @Transactional
    public String ask(UUID copilotId, JsonNode req) {
        long started = System.nanoTime();
        JsonNode c = store.require("copilot_definition", copilotId);
        if (!"ACTIVE".equals(c.get("status").asString())) {
            throw ApiException.conflict("copilot_not_active", "Copilot " + c.get("code").asString() + " is " + c.get("status").asString());
        }
        JsonNode policy = requireUsablePolicy(c);
        String prompt = Fields.requireText(req, "prompt");
        if (prompt.length() > 20_000) {
            throw Fields.invalid("prompt", "too_long", "prompt is limited to 20000 characters");
        }
        // Context passages, restricted to the copilot's data scopes; everything is redacted before "sending".
        Set<String> scopes = new HashSet<>();
        c.path("data_scopes").forEach(s -> scopes.add(s.asString()));
        List<Retriever.Passage> passages = new ArrayList<>();
        int outOfScope = 0;
        Map<String, Integer> redactions = new LinkedHashMap<>();
        StringBuilder sent = new StringBuilder();
        JsonNode context = req.get("context");
        if (context != null && context.isArray()) {
            if (context.size() > MAX_CONTEXT) {
                throw Fields.invalid("context", "too_many_passages", "at most " + MAX_CONTEXT + " context passages");
            }
            int i = 0;
            for (JsonNode p : context) {
                i++;
                String type = p.path("type").asString("DOCUMENT");
                if (!scopes.isEmpty() && !scopes.contains(type)) {
                    outOfScope++;
                    continue;
                }
                PiiRedactor.Result text = policies.redact(policy, p.path("text").asString(""));
                PiiRedactor.Result title = policies.redact(policy, p.path("title").asString(""));
                text.counts().forEach((k, v) -> redactions.merge(k, v, Integer::sum));
                title.counts().forEach((k, v) -> redactions.merge(k, v, Integer::sum));
                passages.add(new Retriever.Passage(p.path("id").asString("ctx-" + i), type, title.text(), text.text()));
                sent.append(title.text()).append(' ').append(text.text()).append('\n');
            }
        }
        PiiRedactor.Result redactedPrompt = policies.redact(policy, prompt);
        redactedPrompt.counts().forEach((k, v) -> redactions.merge(k, v, Integer::sum));
        sent.append(redactedPrompt.text());
        int inputTokens = Retriever.estimateTokens(sent.toString());
        int cap = policy.path("max_tokens_per_request").asInt(8000);
        if (inputTokens > cap) {
            throw ApiException.unprocessable("token_limit_exceeded", "The request needs about " + inputTokens
                    + " tokens; the policy allows " + cap + " per request");
        }
        String outcome;
        String response;
        Retriever.Answer answer = null;
        boolean overBudget = false;
        if (policy.hasNonNull("monthly_token_budget")) {
            long used = policies.usedThisMonth(UUID.fromString(policy.get("id").asString()));
            overBudget = used + inputTokens > policy.get("monthly_token_budget").asLong();
        }
        if (overBudget) {
            outcome = "REFUSED";
            response = "The monthly LLM token budget of this tenant is exhausted; the request was not sent to a model.";
        } else {
            answer = Retriever.answer(redactedPrompt.text(), passages, tools(c.get("allowed_tools")),
                    Fields.text(req, "subject_type"), Fields.text(req, "subject_id"));
            response = answer.text();
            outcome = answer.action() != null ? "ACTION_PROPOSED" : answer.grounded() ? "ANSWERED" : "REFUSED";
        }
        int outputTokens = overBudget ? 0 : Retriever.estimateTokens(response);
        PiiRedactor.Result redactedResponse = policies.redact(policy, response);

        ObjectNode v = json.createObjectNode();
        UUID id = Ids.newId();
        v.put("id", id.toString());
        v.put("copilot_definition_id", copilotId.toString());
        try {
            v.put("user_id", UUID.fromString(TenantContext.principal()).toString());
        } catch (IllegalArgumentException ignored) {
            // staff principals that are not UUIDs are recorded in created_by only
        }
        putIfPresent(v, req, "customer_id");
        v.put("session_id", req.hasNonNull("session_id") ? req.get("session_id").asString() : Ids.newId().toString());
        putIfPresent(v, req, "subject_type");
        putIfPresent(v, req, "subject_id");
        boolean log = policy.path("log_prompts").asBoolean(true);
        v.put("prompt_redacted", log ? redactedPrompt.text() : "[prompt not logged by policy]");
        v.put("response_redacted", log ? redactedResponse.text() : "[response not logged by policy]");
        v.put("prompt_sha256", sha256(prompt));
        v.put("response_sha256", sha256(response));
        v.put("model_name", c.get("model_alias").asString());
        v.put("model_version", overBudget ? null : MODEL_VERSION);
        v.put("input_tokens", overBudget ? 0 : inputTokens); // a refused request never reaches a model
        v.put("output_tokens", outputTokens);
        ArrayNode toolCalls = v.putArray("tool_calls");
        ArrayNode citations = v.putArray("citations");
        if (answer != null) {
            answer.readTools().forEach(t -> toolCalls.addObject().put("tool", t).put("mode", "READ")
                    .put("status", "ANSWERED_FROM_CONTEXT"));
            if (answer.action() != null) {
                ObjectNode a = toolCalls.addObject();
                a.put("tool", answer.action().tool());
                a.put("mode", "WRITE");
                a.set("arguments", json.valueToTree(answer.action().arguments()));
                a.put("status", "PROPOSED");
                a.put("requires_human_approval", true);
            }
            answer.citations().forEach(ci -> citations.addObject().put("index", ci.index()).put("source_id", ci.sourceId())
                    .put("source_type", ci.sourceType()).put("title", ci.title()).put("score", ci.score().toPlainString()));
        }
        ObjectNode red = v.putObject("redactions");
        redactions.forEach(red::put);
        if (outOfScope > 0) {
            red.put("OUT_OF_SCOPE_CONTEXT", outOfScope);
        }
        v.put("outcome", outcome);
        if (policy.hasNonNull("cost_per_1k_tokens")) {
            BigDecimal cost = new BigDecimal(policy.get("cost_per_1k_tokens").asString())
                    .multiply(BigDecimal.valueOf((overBudget ? 0 : inputTokens) + outputTokens)).divide(BigDecimal.valueOf(1000), 8, RoundingMode.HALF_EVEN);
            v.put("cost", cost.toPlainString());
            v.put("cost_currency", policy.get("cost_currency").asString().trim());
        }
        v.put("latency_ms", (int) Math.max(1, (System.nanoTime() - started) / 1_000_000));
        String row = crud.insert("copilot_interaction", v);
        JsonNode rowNode = json.readTree(row);
        outbox.publish("reporting.copilot_interaction.recorded", "copilot_interaction", id, rowNode);
        if (overBudget) {
            ObjectNode payload = ((ObjectNode) policy).deepCopy();
            payload.put("copilot_interaction_id", id.toString());
            payload.put("used_tokens", policies.usedThisMonth(UUID.fromString(policy.get("id").asString())));
            outbox.publish("reporting.llm_budget.exceeded", "llm_gateway_policy", UUID.fromString(policy.get("id").asString()), payload);
        }
        if (answer != null && answer.action() != null) {
            // Central maker-checker: the proposed write action waits for a human decision in the workflow inbox.
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("copilot_definition_id", copilotId.toString());
            payload.put("copilot_code", c.path("code").asString(null));
            payload.put("tool", answer.action().tool());
            payload.put("arguments", answer.action().arguments());
            payload.put("subject_type", Fields.text(req, "subject_type"));
            payload.put("subject_id", Fields.text(req, "subject_id"));
            payload.put("response_redacted", rowNode.path("response_redacted").asString(null));
            payload.put("response_sha256", rowNode.path("response_sha256").asString(null));
            approvals.request(ApprovalSpec.of(APPROVAL_RESOURCE, id, "approve",
                    "Copilot " + c.path("code").asString("") + " proposes " + answer.action().tool(), payload),
                    "propose:" + id);
        }
        return row;
    }

    /** Human decision on a proposed action, recorded as its own append-only audit record. */
    @Transactional
    public String decide(UUID interactionId, boolean approve, String comment) {
        JsonNode parent = store.require("copilot_interaction", interactionId);
        store.scalar("select 1 from (select pg_advisory_xact_lock(hashtextextended(?, 0))) x", Integer.class,
                "copilot-decision:" + interactionId);
        if (!"ACTION_PROPOSED".equals(parent.get("outcome").asString())) {
            throw ApiException.conflict("no_proposed_action", "This interaction did not propose an action");
        }
        if (store.count("select count(*) from copilot_interaction where parent_interaction_id = ?", interactionId) > 0) {
            throw ApiException.conflict("action_already_decided", "The proposed action was already approved or rejected");
        }
        if (TenantContext.principal().equals(Fields.text(parent, "created_by"))) {
            throw ApiException.unprocessable("four_eyes_violation", "The requester cannot approve or reject the copilot's proposed action");
        }
        JsonNode action = null;
        for (JsonNode t : parent.path("tool_calls")) {
            if ("PROPOSED".equals(t.path("status").asString())) {
                action = t;
            }
        }
        String tool = action == null ? "unknown" : action.path("tool").asString();
        String decision = approve ? "APPROVED" : "REJECTED";
        String prompt = "[decision] " + decision.toLowerCase(java.util.Locale.ROOT) + " proposed action " + tool;
        String response = "Action " + tool + " " + decision.toLowerCase(java.util.Locale.ROOT) + " by " + TenantContext.principal()
                + (comment == null ? "" : ": " + comment);
        UUID id = Ids.newId();
        ObjectNode v = json.createObjectNode();
        v.put("id", id.toString());
        v.put("copilot_definition_id", parent.get("copilot_definition_id").asString());
        v.put("parent_interaction_id", interactionId.toString());
        v.put("session_id", parent.get("session_id").asString());
        for (String f : List.of("customer_id", "subject_type", "subject_id")) {
            if (parent.hasNonNull(f)) {
                v.set(f, parent.get(f));
            }
        }
        v.put("prompt_redacted", prompt);
        v.put("response_redacted", Fields.clip(response, 4000));
        v.put("prompt_sha256", sha256(prompt));
        v.put("response_sha256", sha256(response));
        v.put("model_name", "human");
        v.put("input_tokens", 0);
        v.put("output_tokens", 0);
        ArrayNode calls = v.putArray("tool_calls");
        if (action != null) {
            ObjectNode a = ((ObjectNode) action).deepCopy();
            a.put("status", decision);
            calls.add(a);
        }
        v.putArray("citations");
        v.put("outcome", approve ? "ACTION_APPROVED" : "ACTION_REJECTED");
        v.put("approved_by", TenantContext.principal());
        if (comment != null) {
            v.put("decision_comment", comment);
        }
        String row = crud.insert("copilot_interaction", v);
        outbox.publish(approve ? "reporting.copilot_action.approved" : "reporting.copilot_action.rejected",
                "copilot_interaction", id, json.readTree(row));
        approvals.cancelOpen(APPROVAL_RESOURCE, interactionId, null, approve ? "approved directly" : "rejected directly");
        return row;
    }

    /** True while the interaction's proposed action (with the given response hash) has no human decision yet. */
    @Transactional(readOnly = true)
    public boolean awaitsDecision(UUID interactionId, String responseSha256) {
        return store.find("copilot_interaction", interactionId)
                .filter(p -> "ACTION_PROPOSED".equals(p.path("outcome").asString()))
                .filter(p -> responseSha256 == null || responseSha256.equals(p.path("response_sha256").asString(null)))
                .filter(p -> store.count("select count(*) from copilot_interaction where parent_interaction_id = ?",
                        interactionId) == 0)
                .isPresent();
    }

    @Transactional
    public String feedback(UUID interactionId, int rating, String comment) {
        store.require("copilot_interaction", interactionId);
        if (rating < 1 || rating > 5) {
            throw Fields.invalid("rating", "out_of_range", "rating must be 1-5");
        }
        if (store.count("select count(*) from ai_copilot_feedback where copilot_interaction_id = ? and given_by = ?",
                interactionId, TenantContext.principal()) > 0) {
            throw ApiException.conflict("feedback_exists", "You already rated this answer");
        }
        ObjectNode v = json.createObjectNode();
        v.put("copilot_interaction_id", interactionId.toString());
        v.put("rating", rating);
        if (comment != null) {
            v.put("comment", comment);
        }
        v.put("given_by", TenantContext.principal());
        return crud.insert("ai_copilot_feedback", v);
    }

    private static void putIfPresent(ObjectNode v, JsonNode req, String f) {
        if (req.hasNonNull(f)) {
            v.set(f, req.get(f));
        }
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
