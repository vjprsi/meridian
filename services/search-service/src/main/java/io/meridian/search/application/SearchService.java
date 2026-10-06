package io.meridian.search.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.search.domain.IndexRules;
import io.meridian.search.domain.SearchText;
import io.meridian.search.infrastructure.DocumentStore;
import io.meridian.search.infrastructure.JsonValues;
import io.meridian.search.infrastructure.Rows;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Federated search across entity indexes (PostgreSQL full text): synonym expansion, weighted ranking, JSONB
 * filters, offset cursors, type-ahead suggestions, saved searches, and masking of PII fields for callers
 * without the PII permission.
 */
@Service
public class SearchService {
    private static final int MAX_LIMIT = 100;

    private final Rows rows;
    private final DocumentStore docs;
    private final JsonMapper json;

    public SearchService(Rows rows, DocumentStore docs, JsonMapper json) {
        this.rows = rows;
        this.docs = docs;
        this.json = json;
    }

    public record Query(String text, List<String> entityTypes, JsonNode filters, Integer limit, String cursor) {}

    public JsonNode query(Query q, boolean piiAllowed) {
        long start = System.nanoTime();
        int limit = q.limit() == null ? 20 : q.limit();
        if (limit < 1 || limit > MAX_LIMIT) {
            throw ApiException.badRequest("invalid_limit", "limit must be between 1 and " + MAX_LIMIT);
        }
        int offset = decodeCursor(q.cursor());
        Map<String, JsonNode> byPhysical = indexes(q.entityTypes());
        String tsquery = q.text() == null || q.text().isBlank() ? null : SearchText.tsquery(q.text(), synonyms(byPhysical.values()));
        if (tsquery == null && (q.filters() == null || q.filters().isEmpty())) {
            throw ApiException.badRequest("query_required", "Provide query_text or filters");
        }
        ObjectNode out = json.createObjectNode();
        ArrayNode data = out.putArray("data");
        boolean masked = false;
        if (!byPhysical.isEmpty()) {
            List<DocumentStore.Hit> hits = docs.search(new ArrayList<>(byPhysical.keySet()), tsquery, q.filters(), offset, limit + 1);
            boolean more = hits.size() > limit;
            Map<String, JsonNode> indexByType = new HashMap<>();
            byPhysical.values().forEach(i -> indexByType.put(i.get("entity_type").asString(), i));
            for (DocumentStore.Hit h : more ? hits.subList(0, limit) : hits) {
                JsonNode index = indexByType.get(h.entityType());
                ObjectNode item = data.addObject();
                item.put("entity_type", h.entityType());
                item.put("entity_id", h.entityId());
                item.put("score", Math.round(h.score() * 10_000d) / 10_000d);
                List<String> maskedFields = JsonValues.strings(index.get("masked_fields"));
                boolean maskTitle = !piiAllowed && isTitleMasked(index, maskedFields);
                item.put("title", maskTitle ? SearchText.mask(h.title()) : h.title());
                item.set("document", piiAllowed ? h.body() : mask(h.body(), maskedFields));
                masked |= !piiAllowed && !maskedFields.isEmpty();
            }
            out.put("has_more", more);
            out.put("next_cursor", more ? encodeCursor(offset + limit) : null);
        } else {
            out.put("has_more", false);
            out.putNull("next_cursor");
        }
        ArrayNode searched = out.putArray("entity_types");
        byPhysical.values().forEach(i -> searched.add(i.get("entity_type").asString()));
        out.put("masked", masked);
        out.put("took_ms", (System.nanoTime() - start) / 1_000_000);
        return out;
    }

    public JsonNode suggest(String prefix, String entityType, Integer limit, boolean piiAllowed) {
        if (prefix == null || prefix.trim().length() < 2) {
            throw ApiException.badRequest("prefix_too_short", "q must have at least 2 characters");
        }
        int n = limit == null ? 10 : Math.max(1, Math.min(25, limit));
        Map<String, JsonNode> byPhysical = indexes(entityType == null ? List.of() : List.of(entityType));
        ObjectNode out = json.createObjectNode();
        ArrayNode data = out.putArray("data");
        if (byPhysical.isEmpty()) {
            return out;
        }
        Map<String, JsonNode> indexByType = new HashMap<>();
        byPhysical.values().forEach(i -> indexByType.put(i.get("entity_type").asString(), i));
        for (DocumentStore.Hit h : docs.suggest(new ArrayList<>(byPhysical.keySet()), prefix.trim(), n)) {
            JsonNode index = indexByType.get(h.entityType());
            boolean maskTitle = !piiAllowed && isTitleMasked(index, JsonValues.strings(index.get("masked_fields")));
            data.addObject().put("entity_type", h.entityType()).put("entity_id", h.entityId())
                    .put("text", maskTitle ? SearchText.mask(h.title()) : h.title());
        }
        return out;
    }

    /** Runs a saved search; private searches can only be run by their owner (when the caller is a user id). */
    public JsonNode runSaved(UUID id, Integer limit, String cursor, boolean piiAllowed) {
        JsonNode saved = rows.require("saved_search", id, false);
        String principal = TenantContext.principal();
        if (isUuid(principal) && !principal.equals(saved.get("app_user_id").asString()) && !saved.hasNonNull("shared_with_role_id")) {
            throw new ApiException(403, "forbidden", "Saved search " + id + " is private to its owner");
        }
        JsonNode result = query(new Query(JsonValues.text(saved, "query_text"), JsonValues.strings(saved.get("entity_types")),
                saved.get("filters"), limit, cursor), piiAllowed);
        rows.update("update saved_search set last_used_at = now() where id = ?", id);
        ((ObjectNode) result).put("saved_search_id", id.toString());
        return result;
    }

    public void validateSaved(JsonNode v) {
        List<String> types = JsonValues.strings(v.get("entity_types"));
        if (types.isEmpty()) {
            throw ApiException.unprocessable("entity_types_required", "entity_types must list at least one entity type");
        }
        types.forEach(IndexRules::entityType);
        if (v.hasNonNull("filters") && !v.get("filters").isObject()) {
            throw ApiException.unprocessable("invalid_filters", "filters must be an object");
        }
        if (JsonValues.text(v, "query_text") == null && (!v.hasNonNull("filters") || v.get("filters").isEmpty())) {
            throw ApiException.unprocessable("query_required", "A saved search needs query_text or filters");
        }
    }

    /** Synonym sets: at least two distinct terms; normalised to lower case. Returns the normalised terms. */
    public ArrayNode validateSynonyms(JsonNode v) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        JsonValues.strings(v.get("terms")).forEach(t -> terms.add(t.trim().toLowerCase(Locale.ROOT)));
        terms.removeIf(String::isBlank);
        if (terms.size() < 2) {
            throw ApiException.unprocessable("invalid_synonyms", "A synonym set needs at least two distinct terms");
        }
        if (terms.stream().anyMatch(t -> SearchText.tokens(t).isEmpty() || t.length() > 100)) {
            throw ApiException.unprocessable("invalid_synonyms", "Terms must contain letters or digits (max 100 chars)");
        }
        if (v.hasNonNull("search_index_id")
                && rows.text("search_index", UUID.fromString(v.get("search_index_id").asString()), false).isEmpty()) {
            throw ApiException.unprocessable("unknown_index", "search_index_id does not exist");
        }
        ArrayNode a = json.createArrayNode();
        terms.forEach(a::add);
        return a;
    }

    /** Searchable indexes for the requested entity types (all when empty), keyed by physical index. */
    private Map<String, JsonNode> indexes(List<String> entityTypes) {
        Set<String> wanted = new LinkedHashSet<>();
        entityTypes.forEach(t -> wanted.add(IndexRules.entityType(t)));
        Map<String, JsonNode> out = new java.util.LinkedHashMap<>();
        for (JsonNode i : rows.query("select row_to_json(i)::text from search_index i order by entity_type")) {
            if (IndexRules.searchable(i.get("status").asString())
                    && (wanted.isEmpty() || wanted.contains(i.get("entity_type").asString()))) {
                out.put(i.get("physical_index").asString(), i);
            }
        }
        return out;
    }

    private List<Set<String>> synonyms(java.util.Collection<JsonNode> indexes) {
        Set<String> ids = new LinkedHashSet<>();
        indexes.forEach(i -> ids.add(i.get("id").asString()));
        List<Set<String>> sets = new ArrayList<>();
        for (JsonNode s : rows.query("select row_to_json(s)::text from search_synonym s where is_active")) {
            if (!s.hasNonNull("search_index_id") || ids.contains(s.get("search_index_id").asString())) {
                Set<String> set = new LinkedHashSet<>();
                JsonValues.strings(s.get("terms")).forEach(t -> set.add(t.toLowerCase(Locale.ROOT)));
                sets.add(set);
            }
        }
        return sets;
    }

    private boolean isTitleMasked(JsonNode index, List<String> maskedFields) {
        List<SearchText.Field> fields = SearchText.fields(index.get("searchable_fields"));
        return fields.stream().sorted((a, b) -> Double.compare(b.boost(), a.boost())).findFirst()
                .map(f -> maskedFields.contains(f.path())).orElse(false);
    }

    private JsonNode mask(JsonNode body, List<String> maskedFields) {
        if (maskedFields.isEmpty()) {
            return body;
        }
        ObjectNode copy = ((ObjectNode) body).deepCopy();
        for (String path : maskedFields) {
            String[] parts = path.split("\\.");
            JsonNode parent = copy;
            for (int i = 0; i < parts.length - 1 && parent != null; i++) {
                parent = parent.get(parts[i]);
            }
            if (parent instanceof ObjectNode o && o.hasNonNull(parts[parts.length - 1])) {
                JsonNode v = o.get(parts[parts.length - 1]);
                o.put(parts[parts.length - 1], v.isValueNode() ? SearchText.mask(v.asString()) : "****");
            }
        }
        return copy;
    }

    private static int decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            String s = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (!s.startsWith("o:")) {
                throw new IllegalArgumentException();
            }
            return Math.max(0, Integer.parseInt(s.substring(2)));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_cursor", "cursor is not valid");
        }
    }

    private static String encodeCursor(int offset) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(("o:" + offset).getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isUuid(String s) {
        try {
            UUID.fromString(s);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
