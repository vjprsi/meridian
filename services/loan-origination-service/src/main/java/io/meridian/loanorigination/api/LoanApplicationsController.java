package io.meridian.loanorigination.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.loanorigination.api.generated.LoanApplicationsApi;
import io.meridian.loanorigination.api.generated.model.AffordabilityAssessment;
import io.meridian.loanorigination.api.generated.model.BureauReport;
import io.meridian.loanorigination.api.generated.model.CreditDecision;
import io.meridian.loanorigination.api.generated.model.CreditDecisionPage;
import io.meridian.loanorigination.api.generated.model.CreditDecisionRequest;
import io.meridian.loanorigination.api.generated.model.CreditDecisionRequestPage;
import io.meridian.loanorigination.api.generated.model.CreditScore;
import io.meridian.loanorigination.api.generated.model.CreditScorePage;
import io.meridian.loanorigination.api.generated.model.LoanApplication;
import io.meridian.loanorigination.api.generated.model.LoanApplicationCreate;
import io.meridian.loanorigination.api.generated.model.LoanApplicationUpdate;
import io.meridian.loanorigination.api.generated.model.LoanApplicationsAssignRequest;
import io.meridian.loanorigination.api.generated.model.LoanApplicationsBureauPullRequest;
import io.meridian.loanorigination.api.generated.model.LoanApplicationsDecideRequest;
import io.meridian.loanorigination.api.generated.model.LoanApplicationsQuickQuoteRequest;
import io.meridian.loanorigination.api.generated.model.LoanApplicationsWithdrawRequest;
import io.meridian.loanorigination.api.generated.model.LoanOfferPage;
import io.meridian.loanorigination.application.ApplicationService;
import io.meridian.loanorigination.application.AssessmentService;
import io.meridian.loanorigination.application.BookingService;
import io.meridian.loanorigination.application.BureauService;
import io.meridian.loanorigination.application.DecisionService;
import io.meridian.loanorigination.application.ExpiryService;
import io.meridian.loanorigination.application.OfferService;
import io.meridian.loanorigination.application.Support;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Loan applications: intake, lifecycle, bureau, scoring, affordability, decisions, quotes and the expiry job. */
@RestController
public class LoanApplicationsController implements LoanApplicationsApi {
    private final ApplicationService applications;
    private final BureauService bureau;
    private final AssessmentService assessment;
    private final DecisionService decisions;
    private final OfferService offers;
    private final BookingService booking;
    private final ExpiryService expiry;
    private final CrudEngine crud;
    private final Actions actions;

    public LoanApplicationsController(ApplicationService applications, BureauService bureau,
                                      AssessmentService assessment, DecisionService decisions, OfferService offers,
                                      BookingService booking, ExpiryService expiry, CrudEngine crud, Actions actions) {
        this.applications = applications;
        this.bureau = bureau;
        this.assessment = assessment;
        this.decisions = decisions;
        this.offers = offers;
        this.booking = booking;
        this.expiry = expiry;
        this.crud = crud;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<LoanApplication> loanApplicationsCreate(String idempotencyKey, LoanApplicationCreate body) {
        return actions.create(idempotencyKey, body, LoanApplication.class, () -> applications.create(body));
    }

    @Override
    public ResponseEntity<LoanApplication> loanApplicationsUpdate(UUID id, String ifMatch, LoanApplicationUpdate body) {
        TenantContext.require();
        applications.checkUpdate(id, actions.tree(body));
        return crud.update(Support.APP, LoanApplication.class, body, ifMatch, Crud.params().with("id", id));
    }

    @Override
    public ResponseEntity<LoanApplication> loanApplicationsSubmit(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "submit", null, LoanApplication.class, () -> applications.submit(id));
    }

    @Override
    public ResponseEntity<LoanApplication> loanApplicationsWithdraw(UUID id, String idempotencyKey,
                                                                   LoanApplicationsWithdrawRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "withdraw", body, LoanApplication.class,
                () -> applications.withdraw(id, Http.text(req, "reason")));
    }

    @Override
    public ResponseEntity<LoanApplication> loanApplicationsAssign(UUID id, String idempotencyKey,
                                                                 LoanApplicationsAssignRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "assign", body, LoanApplication.class,
                () -> applications.assign(id, Http.text(req, "assigned_to")));
    }

    @Override
    public ResponseEntity<BureauReport> loanApplicationsBureauPull(UUID id, String idempotencyKey,
                                                                  LoanApplicationsBureauPullRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "bureau-pull", body, BureauReport.class,
                () -> bureau.pull(id, Http.uuid(req, "loan_applicant_id"), Http.text(req, "provider"),
                        Http.text(req, "pull_type"), req.path("force").asBoolean(false)));
    }

    @Override
    public ResponseEntity<CreditScore> loanApplicationsScore(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "score", null, CreditScore.class, () -> assessment.score(id));
    }

    @Override
    public ResponseEntity<AffordabilityAssessment> loanApplicationsAffordability(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "affordability", null, AffordabilityAssessment.class,
                () -> assessment.affordability(id));
    }

    @Override
    public ResponseEntity<CreditDecisionRequest> loanApplicationsDecide(UUID id, String idempotencyKey,
                                                                       LoanApplicationsDecideRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "decide", body, CreditDecisionRequest.class,
                () -> decisions.propose(id, req));
    }

    @Override
    public ResponseEntity<CreditDecision> loanApplicationsAutoDecide(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "auto-decide", null, CreditDecision.class, () -> decisions.autoDecide(id));
    }

    @Override
    public ResponseEntity<LoanApplication> loanApplicationsBook(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "book", null, LoanApplication.class, () -> booking.book(id, true));
    }

    @Override
    public ResponseEntity<CreditDecisionPage> loanApplicationsDecisions(UUID id, Integer limit, String cursor) {
        return child("credit_decision", CreditDecisionPage.class, id, limit, cursor);
    }

    @Override
    public ResponseEntity<CreditScorePage> loanApplicationsScores(UUID id, Integer limit, String cursor) {
        return child("credit_score", CreditScorePage.class, id, limit, cursor);
    }

    @Override
    public ResponseEntity<LoanOfferPage> loanApplicationsOffers(UUID id, Integer limit, String cursor) {
        return child("loan_offer", LoanOfferPage.class, id, limit, cursor);
    }

    @Override
    public ResponseEntity<CreditDecisionRequestPage> loanApplicationsDecisionRequests(UUID id, Integer limit, String cursor) {
        return child("credit_decision_request", CreditDecisionRequestPage.class, id, limit, cursor);
    }

    @Override
    public ResponseEntity<Map<String, Object>> loanApplicationsQuickQuote(String idempotencyKey,
                                                                         LoanApplicationsQuickQuoteRequest body) {
        JsonNode req = actions.tree(body);
        return actions.objectAction(idempotencyKey, "quick-quote", body, () -> offers.quickQuote(req));
    }

    @Override
    public ResponseEntity<Map<String, Object>> loanApplicationsEodExpire(LocalDate businessDate, String idempotencyKey) {
        return actions.objectAction(idempotencyKey, "eod-expire", Map.of("business_date", String.valueOf(businessDate)),
                () -> expiry.expire(businessDate));
    }

    private <T> ResponseEntity<T> child(String table, Class<T> page, UUID id, Integer limit, String cursor) {
        TenantContext.require();
        crud.findOne(Support.APP, Map.of("id", id))
                .orElseThrow(() -> io.meridian.common.error.ApiException.notFound("loan application", id));
        return crud.list(table, page, Crud.params().with("loan_application_id", id).with("limit", limit).with("cursor", cursor));
    }
}
