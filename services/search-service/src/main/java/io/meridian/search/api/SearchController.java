package io.meridian.search.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.search.api.generated.SearchApi;
import io.meridian.search.api.generated.model.SearchQueryRequest;
import io.meridian.search.application.SearchService;
import io.meridian.search.infrastructure.JsonValues;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Federated query and type-ahead. Results: {@code {data:[{entity_type, entity_id, score, title, document}],
 * has_more, next_cursor, entity_types, masked, took_ms}}. Queries are read-only, so no Idempotency-Key replay
 * is applied (a replayed search must reflect the current index and the caller's PII permission).
 */
@RestController
public class SearchController implements SearchApi {
    private final SearchService search;
    private final Permissions permissions;
    private final JsonMapper json;

    public SearchController(SearchService search, Permissions permissions, JsonMapper json) {
        this.search = search;
        this.permissions = permissions;
        this.json = json;
    }

    @Override
    public ResponseEntity<Void> searchQuery(String idempotencyKey, SearchQueryRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        return Http.ok(search.query(new SearchService.Query(Http.text(req, "query_text"),
                JsonValues.strings(req.get("entity_types")), req.get("filters"),
                req.hasNonNull("limit") ? req.get("limit").asInt() : null, Http.text(req, "cursor")), permissions.piiAllowed()));
    }

    /** {@code ?q=} (2+ chars), optional {@code ?entity_type=} and {@code ?limit=} (max 25). */
    @Override
    public ResponseEntity<Void> searchSuggest() {
        TenantContext.require();
        String limit = Http.query("limit");
        Integer n;
        try {
            n = limit == null ? null : Integer.valueOf(limit);
        } catch (NumberFormatException e) {
            throw io.meridian.common.error.ApiException.badRequest("invalid_limit", "limit must be a number");
        }
        return Http.ok(search.suggest(Http.query("q"), Http.query("entity_type"), n, permissions.piiAllowed()));
    }
}
