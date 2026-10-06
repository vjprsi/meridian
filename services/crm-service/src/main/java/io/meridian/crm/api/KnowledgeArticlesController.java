package io.meridian.crm.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.crm.api.generated.KnowledgeArticlesApi;
import io.meridian.crm.api.generated.model.KnowledgeArticle;
import io.meridian.crm.api.generated.model.KnowledgeArticleCreate;
import io.meridian.crm.api.generated.model.KnowledgeArticlePage;
import io.meridian.crm.api.generated.model.KnowledgeArticleUpdate;
import io.meridian.crm.api.generated.model.KnowledgeArticlesFeedbackRequest;
import io.meridian.crm.application.Json;
import io.meridian.crm.application.KnowledgeService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Knowledge base: authoring workflow with four-eyes publication, ranked search and feedback. */
@RestController
public class KnowledgeArticlesController implements KnowledgeArticlesApi {
    private final KnowledgeService knowledge;
    private final Http http;

    public KnowledgeArticlesController(KnowledgeService knowledge, Http http) {
        this.knowledge = knowledge;
        this.http = http;
    }

    @Override
    public ResponseEntity<KnowledgeArticle> knowledgeArticlesCreate(String idempotencyKey, KnowledgeArticleCreate body) {
        return http.created(idempotencyKey, "articles.create", body, KnowledgeArticle.class, () -> knowledge.create(body));
    }

    @Override
    public ResponseEntity<KnowledgeArticle> knowledgeArticlesUpdate(UUID id, String ifMatch, KnowledgeArticleUpdate body) {
        TenantContext.require();
        return http.withEtag(knowledge.update(id, ifMatch, body), KnowledgeArticle.class);
    }

    @Override
    public ResponseEntity<KnowledgeArticlePage> knowledgeArticlesSearch(String q, String language, String audience,
                                                                       String caseType, Integer limit, String cursor) {
        TenantContext.require();
        return ResponseEntity.ok(http.read(knowledge.search(q, language, audience, caseType, limit), KnowledgeArticlePage.class));
    }

    @Override
    public ResponseEntity<KnowledgeArticle> knowledgeArticlesSubmitReview(UUID id, String idempotencyKey) {
        return http.ok(idempotencyKey, "articles.submit-review", id, null, KnowledgeArticle.class, () -> knowledge.submitReview(id));
    }

    @Override
    public ResponseEntity<KnowledgeArticle> knowledgeArticlesPublish(UUID id, String idempotencyKey) {
        return http.ok(idempotencyKey, "articles.publish", id, null, KnowledgeArticle.class, () -> knowledge.publish(id));
    }

    @Override
    public ResponseEntity<KnowledgeArticle> knowledgeArticlesArchive(UUID id, String idempotencyKey) {
        return http.ok(idempotencyKey, "articles.archive", id, null, KnowledgeArticle.class, () -> knowledge.archive(id));
    }

    @Override
    public ResponseEntity<KnowledgeArticle> knowledgeArticlesNewVersion(UUID id, String idempotencyKey) {
        return http.ok(idempotencyKey, "articles.new-version", id, null, KnowledgeArticle.class, () -> knowledge.newVersion(id));
    }

    @Override
    public ResponseEntity<KnowledgeArticle> knowledgeArticlesFeedback(UUID id, String idempotencyKey,
                                                                     KnowledgeArticlesFeedbackRequest body) {
        boolean helpful = Json.bool(http.tree(body), "helpful");
        return http.ok(idempotencyKey, "articles.feedback", id, body, KnowledgeArticle.class, () -> knowledge.feedback(id, helpful));
    }
}
