package io.meridian.crm.application;

import static io.meridian.crm.application.Json.text;

import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.common.error.ApiException;
import io.meridian.common.error.ApiException.FieldError;
import io.meridian.common.page.Cursor;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.crm.api.generated.model.KnowledgeArticle;
import io.meridian.crm.domain.Lifecycles;
import io.meridian.crm.infrastructure.Rows;
import io.meridian.web.crud.CrudEngine;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Knowledge base: drafting, review with four-eyes publication, archive, new versions, helpfulness feedback and
 * ranked search over published articles (title, tags and body token matches, boosted by helpfulness).
 */
@Service
public class KnowledgeService {
    static final String T = "knowledge_article";
    /** Approval resource type for article reviews in workflow-service (G11). */
    public static final String APPROVAL_RESOURCE = "knowledge_article";
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    private static final Pattern LANGUAGE = Pattern.compile("^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$");
    private static final Set<String> CASE_TYPES = Set.of("SERVICE_REQUEST", "INQUIRY", "CARD_ISSUE", "PAYMENT_ISSUE",
            "ACCOUNT_MAINTENANCE", "DOCUMENT_REQUEST", "FRAUD_REPORT", "TECHNICAL_ISSUE", "LOAN_SERVICING", "KYC_UPDATE",
            "CLOSURE_REQUEST", "OTHER");

    private final Rows rows;
    private final CrudEngine crud;
    private final Events events;
    private final Approvals approvals;

    public KnowledgeService(Rows rows, CrudEngine crud, Events events, Approvals approvals) {
        this.approvals = approvals;
        this.rows = rows;
        this.crud = crud;
        this.events = events;
    }

    @Transactional
    public ObjectNode create(Object body) {
        ObjectNode v = crud.toColumns(T, body);
        validate(v);
        v.put("status", "DRAFT");
        v.put("version", 1);
        return rows.insert(T, v);
    }

    @Transactional
    public ObjectNode update(UUID id, String ifMatch, Object body) {
        int expected = Json.parseVersion(ifMatch);
        ObjectNode current = rows.lock(T, id);
        if (!Lifecycles.ARTICLE.allows("edit", text(current, "status"))) {
            throw ApiException.conflict("article_not_editable",
                    "Only DRAFT articles can be edited; create a new version of a " + text(current, "status") + " article");
        }
        ObjectNode v = crud.toColumns(T, body);
        ObjectNode merged = current.deepCopy();
        merged.setAll(v);
        validate(merged);
        for (String f : List.of("tags", "case_types")) {
            if (v.has(f)) {
                v.set(f, merged.get(f));
            }
        }
        return rows.update(T, id, v, expected).orElseThrow(() -> Json.versionConflict(expected));
    }

    private static void validate(ObjectNode v) {
        List<FieldError> errors = new ArrayList<>();
        if (text(v, "slug") == null || !SLUG.matcher(text(v, "slug")).matches()) {
            errors.add(new FieldError("slug", "invalid_slug", "slug must be lower-case words joined by '-'"));
        }
        if (text(v, "title") == null || text(v, "title").isBlank()) {
            errors.add(new FieldError("title", "required", "title must not be blank"));
        }
        if (text(v, "body") == null || text(v, "body").isBlank()) {
            errors.add(new FieldError("body", "required", "body must not be blank"));
        }
        if (text(v, "language") != null && !LANGUAGE.matcher(text(v, "language")).matches()) {
            errors.add(new FieldError("language", "invalid_language", "language must be a BCP 47 tag, e.g. en or en-GB"));
        }
        Set<String> caseTypes = new LinkedHashSet<>();
        for (String ct : Json.strings(v.get("case_types"))) {
            String u = ct.trim().toUpperCase(Locale.ROOT);
            if (!CASE_TYPES.contains(u)) {
                errors.add(new FieldError("case_types", "invalid_case_type", ct + " is not a ServiceCaseType"));
            }
            caseTypes.add(u);
        }
        if (!errors.isEmpty()) {
            throw new ApiException(422, "invalid_article", "Knowledge article is invalid", errors);
        }
        if (v.has("case_types") && !v.get("case_types").isNull()) {
            v.set("case_types", array(caseTypes));
        }
        if (v.has("tags") && !v.get("tags").isNull()) {
            Set<String> tags = new LinkedHashSet<>();
            Json.strings(v.get("tags")).forEach(t -> {
                if (!t.isBlank()) {
                    tags.add(t.trim().toLowerCase(Locale.ROOT));
                }
            });
            v.set("tags", array(tags));
        }
    }

    // ------------------------------------------------------------------ workflow

    @Transactional
    public ObjectNode submitReview(UUID id) {
        ObjectNode a = rows.lock(T, id);
        ObjectNode v = rows.object();
        v.put("status", Lifecycles.ARTICLE.apply("submit-review", text(a, "status")));
        v.put("submitted_by", Json.truncate(TenantContext.principal(), 64));
        ObjectNode row = rows.update(T, id, v);
        // Central maker-checker: the reviewer decides in the workflow inbox; the version identifies the content.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("slug", text(row, "slug"));
        payload.put("title", text(row, "title"));
        payload.put("language", text(row, "language"));
        payload.put("audience", text(row, "audience"));
        payload.put("version", row.path("version").asInt());
        approvals.request(ApprovalSpec.of(APPROVAL_RESOURCE, id, "publish",
                "Knowledge article " + text(row, "slug") + " v" + row.path("version").asInt(), payload),
                "review:" + id + ":" + row.path("version").asInt());
        return row;
    }

    /** True while the article is IN_REVIEW with the submitted version. */
    @Transactional(readOnly = true)
    public boolean awaitsReview(UUID id, Integer version) {
        return rows.find(T, id)
                .filter(a -> "IN_REVIEW".equals(text(a, "status")))
                .filter(a -> version == null || version == a.path("version").asInt())
                .isPresent();
    }

    @Transactional
    public ObjectNode publish(UUID id) {
        ObjectNode a = rows.lock(T, id);
        String to = Lifecycles.ARTICLE.apply("publish", text(a, "status"));
        String reviewer = TenantContext.principal();
        if (reviewer.equals(text(a, "submitted_by"))) {
            throw ApiException.unprocessable("four_eyes_violation", "An article must be published by a reviewer other than its author");
        }
        ObjectNode v = rows.object();
        v.put("status", to);
        v.put("reviewed_by", Json.truncate(reviewer, 64));
        v.put("published_at", rows.now().toString());
        v.putNull("archived_at");
        if (text(a, "review_due") == null) {
            v.put("review_due", rows.now().toLocalDate().plusMonths(12).toString());
        }
        ObjectNode row = rows.update(T, id, v);
        events.publish("servicing.knowledge_article.published", T, row, KnowledgeArticle.class);
        approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "published directly");
        return row;
    }

    @Transactional
    public ObjectNode archive(UUID id) {
        ObjectNode a = rows.lock(T, id);
        ObjectNode v = rows.object();
        v.put("status", Lifecycles.ARTICLE.apply("archive", text(a, "status")));
        v.put("archived_at", rows.now().toString());
        ObjectNode row = rows.update(T, id, v);
        events.publish("servicing.knowledge_article.archived", T, row, KnowledgeArticle.class);
        if ("IN_REVIEW".equals(text(a, "status"))) {
            approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "archived");
        }
        return row;
    }

    /** The article returns to DRAFT as version n+1 (the slug stays stable for links); review starts again. */
    @Transactional
    public ObjectNode newVersion(UUID id) {
        ObjectNode a = rows.lock(T, id);
        ObjectNode v = rows.object();
        v.put("status", Lifecycles.ARTICLE.apply("new-version", text(a, "status")));
        v.put("version", a.path("version").asInt() + 1);
        v.putNull("submitted_by");
        v.putNull("reviewed_by");
        v.putNull("archived_at");
        return rows.update(T, id, v);
    }

    @Transactional
    public ObjectNode feedback(UUID id, boolean helpful) {
        ObjectNode a = rows.lock(T, id);
        Lifecycles.ARTICLE.apply("feedback", text(a, "status"));
        ObjectNode v = rows.object();
        String f = helpful ? "helpful_count" : "not_helpful_count";
        v.put(f, a.path(f).asInt() + 1);
        return rows.update(T, id, v);
    }

    // ------------------------------------------------------------------ search

    /** Ranked search over PUBLISHED articles; one page of the best matches (has_more when more matched). */
    @Transactional(readOnly = true)
    public ObjectNode search(String q, String language, String audience, String caseType, Integer limit) {
        List<String> tokens = tokens(q);
        if (tokens.isEmpty()) {
            throw ApiException.badRequest("query_too_short", "q needs at least one word of 2+ characters");
        }
        List<ObjectNode> candidates = rows.query("""
                select row_to_json(t)::text from knowledge_article t where t.status = 'PUBLISHED'
                  and (cast(? as text) is null or t.language = cast(? as text) or t.language like cast(? as text) || '-%')
                  and (cast(? as text) is null or t.audience = cast(? as text))
                  and (cast(? as text) is null or coalesce(t.case_types, '[]'::jsonb) @> jsonb_build_array(cast(? as text)))
                order by t.id desc limit 2000""", language, language, language, audience, audience, caseType, caseType);
        record Hit(ObjectNode row, double score) {}
        List<Hit> hits = new ArrayList<>();
        for (ObjectNode a : candidates) {
            String title = text(a, "title").toLowerCase(Locale.ROOT);
            String body = text(a, "body").toLowerCase(Locale.ROOT);
            Set<String> tags = new LinkedHashSet<>(Json.strings(a.get("tags")));
            double score = 0;
            int matched = 0;
            for (String t : tokens) {
                boolean any = false;
                if (title.contains(t)) {
                    score += 5;
                    any = true;
                }
                if (tags.contains(t)) {
                    score += 3;
                    any = true;
                }
                int n = occurrences(body, t);
                if (n > 0) {
                    score += Math.min(n, 5);
                    any = true;
                }
                if (any) {
                    matched++;
                }
            }
            if (matched == 0) {
                continue;
            }
            score *= (double) matched / tokens.size();
            int helpful = a.path("helpful_count").asInt();
            int unhelpful = a.path("not_helpful_count").asInt();
            score *= 1 + 0.5 * (helpful + 1.0) / (helpful + unhelpful + 2.0);
            hits.add(new Hit(a, score));
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed().thenComparing(h -> text(h.row(), "slug")));
        int n = Cursor.limit(limit);
        ObjectNode page = rows.object();
        ArrayNode data = page.putArray("data");
        hits.stream().limit(n).forEach(h -> data.add(h.row()));
        page.put("has_more", hits.size() > n);
        page.putNull("next_cursor");
        return page;
    }

    static List<String> tokens(String q) {
        if (q == null) {
            return List.of();
        }
        return Arrays.stream(q.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(t -> t.length() >= 2).distinct().toList();
    }

    private static int occurrences(String text, String token) {
        int n = 0;
        for (int i = text.indexOf(token); i >= 0; i = text.indexOf(token, i + token.length())) {
            n++;
        }
        return n;
    }

    private static ArrayNode array(Set<String> values) {
        ArrayNode a = JsonNodeFactory.instance.arrayNode();
        values.forEach(a::add);
        return a;
    }

    /** Best published articles for a case type and text (copilot grounding). */
    @Transactional(readOnly = true)
    public List<ObjectNode> grounding(String text, String caseType, int max) {
        List<String> t = tokens(text);
        if (t.isEmpty() && caseType == null) {
            return List.of();
        }
        ObjectNode page = t.isEmpty()
                ? (ObjectNode) rows.page("select row_to_json(t)::text from knowledge_article t where t.status = 'PUBLISHED'"
                        + " and coalesce(t.case_types, '[]'::jsonb) @> jsonb_build_array(cast(? as text))",
                        List.of(caseType), max, null)
                : search(String.join(" ", t), null, null, null, 50);
        List<ObjectNode> out = new ArrayList<>();
        for (JsonNode a : page.get("data")) {
            if (out.size() < max) {
                out.add((ObjectNode) a);
            }
        }
        return out;
    }
}
