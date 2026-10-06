package io.meridian.gl.application;

import io.meridian.common.error.ApiException;
import io.meridian.gl.domain.MappingResolver;
import io.meridian.gl.domain.MappingRule;
import io.meridian.gl.infrastructure.GlStore;
import io.meridian.web.crud.CrudEngine;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** GL mapping rules: validation on write, deterministic resolution for postings and the /simulate action. */
@Service
public class MappingService {
    private static final String T = "gl_mapping_rule";
    private static final Set<String> SIGNS = Set.of("POSITIVE", "NEGATIVE");

    /** Result of a resolution: the winning rule and its target GL account row. */
    public record Resolution(MappingRule rule, JsonNode glAccount) {}

    private final GlStore store;
    private final CrudEngine crud;
    private final JsonMapper json;

    public MappingService(GlStore store, CrudEngine crud, JsonMapper json) {
        this.store = store;
        this.crud = crud;
        this.json = json;
    }

    @Transactional
    public String create(Object body) {
        ObjectNode v = crud.toColumns(T, body);
        if (!v.hasNonNull("priority")) {
            v.put("priority", 100);
        }
        validate(v, null);
        return crud.insert(T, v);
    }

    @Transactional
    public String update(UUID id, String ifMatch, Object body) {
        int version = Fields.ifMatch(ifMatch);
        JsonNode cur = store.requireLocked(T, id);
        ObjectNode patch = crud.toColumns(T, body);
        ObjectNode merged = ((ObjectNode) cur).deepCopy();
        patch.propertyNames().forEach(n -> merged.set(n, patch.get(n)));
        validate(merged, id);
        return crud.updateRow(T, Map.of("id", id), patch, version)
                .orElseThrow(() -> ApiException.conflict("version_conflict",
                        "If-Match " + version + " does not match the current version; reload and retry"));
    }

    /** Resolves the GL account for a posting; empty when no rule matches. */
    @Transactional(readOnly = true)
    public Optional<Resolution> resolve(MappingRule.Criteria c) {
        List<MappingRule> candidates = MappingResolver.candidates(rules(c.legalEntityId()), c);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        if (c.legalEntityId() == null) {
            Set<UUID> entities = candidates.stream().map(MappingRule::legalEntityId).collect(Collectors.toSet());
            if (entities.size() > 1) {
                throw ApiException.unprocessable("legal_entity_required",
                        "Rules of several legal entities match; pass legal_entity_id");
            }
        }
        MappingRule winner = candidates.getFirst();
        return Optional.of(new Resolution(winner, store.require("gl_account", winner.glAccountId())));
    }

    /** /simulate: which GL account would a posting with these attributes map to ({gl_account, mapping_rule, as_of}). */
    @Transactional(readOnly = true)
    public JsonNode simulate(JsonNode req) {
        String sign = Fields.text(req, "balance_sign");
        if (sign != null && !SIGNS.contains(sign)) {
            throw Fields.invalid("balance_sign", "invalid_enum", "balance_sign must be POSITIVE or NEGATIVE");
        }
        LocalDate asOf = Optional.ofNullable(Fields.date(req, "as_of")).orElse(LocalDate.now());
        MappingRule.Criteria c = new MappingRule.Criteria(Fields.uuid(req, "legal_entity_id"),
                Fields.uuid(req, "product_id"), Fields.text(req, "ledger_account_type"), Fields.text(req, "owner_type"),
                Fields.text(req, "entry_type"), sign, asOf);
        Resolution r = resolve(c).orElseThrow(() -> ApiException.unprocessable("no_mapping_rule",
                "No GL mapping rule matches the posting attributes on " + asOf));
        ObjectNode out = json.createObjectNode();
        out.set("gl_account", r.glAccount());
        out.set("mapping_rule", store.require(T, r.rule().id()));
        out.put("as_of", asOf.toString());
        return out;
    }

    private void validate(JsonNode r, UUID self) {
        UUID le = Fields.requireUuid(r, "legal_entity_id");
        UUID glAccountId = Fields.requireUuid(r, "gl_account_id");
        LocalDate from = Fields.requireDate(r, "valid_from");
        LocalDate to = Fields.date(r, "valid_to");
        if (to != null && to.isBefore(from)) {
            throw ApiException.unprocessable("invalid_validity_range", "valid_to must not be before valid_from");
        }
        String sign = Fields.text(r, "balance_sign");
        if (sign != null && !SIGNS.contains(sign)) {
            throw Fields.invalid("balance_sign", "invalid_enum", "balance_sign must be POSITIVE or NEGATIVE");
        }
        if (r.get("priority").asInt() < 0) {
            throw Fields.invalid("priority", "min", "priority must not be negative");
        }
        JsonNode account = store.find("gl_account", glAccountId).orElseThrow(() ->
                ApiException.unprocessable("gl_account_not_found", "GL account " + glAccountId + " does not exist"));
        if (!le.toString().equals(account.get("legal_entity_id").asString())) {
            throw ApiException.unprocessable("legal_entity_mismatch", "The GL account belongs to another legal entity");
        }
        if (!account.get("is_postable").asBoolean()) {
            throw ApiException.unprocessable("gl_account_not_postable", "Rules must target a postable (leaf) GL account");
        }
        if (!"ACTIVE".equals(account.get("status").asString())) {
            throw ApiException.unprocessable("gl_account_inactive", "The target GL account is not ACTIVE");
        }
        MappingRule rule = toRule(r);
        store.advisoryLock("gl_mapping_rule:" + le);
        for (MappingRule other : rules(le)) {
            if (!other.id().equals(self) && other.priority() == rule.priority() && other.sameCriteria(rule)
                    && other.overlaps(rule)) {
                throw ApiException.conflict("conflicting_mapping_rule",
                        "Rule " + other.id() + " has the same criteria and priority in an overlapping validity period");
            }
        }
    }

    private List<MappingRule> rules(UUID legalEntityId) {
        List<JsonNode> rows = legalEntityId == null
                ? store.rows("select row_to_json(t)::text from gl_mapping_rule t")
                : store.rows("select row_to_json(t)::text from gl_mapping_rule t where t.legal_entity_id = ?", legalEntityId);
        return rows.stream().map(MappingService::toRule).toList();
    }

    private static MappingRule toRule(JsonNode r) {
        UUID id = r.hasNonNull("id") ? UUID.fromString(r.get("id").asString()) : new UUID(0, 0);
        return new MappingRule(id, Fields.uuid(r, "legal_entity_id"), r.path("priority").asInt(100),
                Fields.uuid(r, "product_id"), Fields.text(r, "ledger_account_type"), Fields.text(r, "owner_type"),
                Fields.text(r, "entry_type"), Fields.text(r, "balance_sign"), Fields.uuid(r, "gl_account_id"),
                Fields.date(r, "valid_from"), Fields.date(r, "valid_to"));
    }
}
