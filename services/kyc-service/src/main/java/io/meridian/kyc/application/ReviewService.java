package io.meridian.kyc.application;

import static io.meridian.kyc.application.KycSupport.date;
import static io.meridian.kyc.application.KycSupport.text;
import static io.meridian.kyc.application.KycSupport.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.kyc.api.generated.model.KycReview;
import io.meridian.kyc.domain.KycRules;
import io.meridian.kyc.infrastructure.Rows;
import io.meridian.web.crud.CrudEngine;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

/** Periodic and event-driven KYC reviews: scheduling, assignment, customer outreach, completion. */
@Service
public class ReviewService {
    static final String TABLE = "kyc_review";

    private final Rows rows;
    private final CrudEngine crud;
    private final KycSupport kyc;
    private final ProfileService profiles;

    public ReviewService(Rows rows, CrudEngine crud, KycSupport kyc, ProfileService profiles) {
        this.rows = rows;
        this.crud = crud;
        this.kyc = kyc;
        this.profiles = profiles;
    }

    @Transactional
    public ObjectNode create(Object body) {
        ObjectNode v = crud.toColumns(TABLE, body);
        for (String f : new String[] {"outcome", "outcome_notes", "completed_at", "customer_outreach_at"}) {
            v.remove(f);
        }
        LocalDate due = date(v, "due_date");
        profiles.provision(uuid(v, "kyc_profile_id"), uuid(v, "customer_id"));
        v.put("status", KycRules.initialReviewStatus(due, KycSupport.today()));
        ObjectNode row = rows.insert(TABLE, v);
        if ("DUE".equals(text(row, "status"))) {
            kyc.publish("party.kyc_review.due", TABLE, row, KycReview.class);
        }
        return row;
    }

    /** PATCH guard: status moves only through actions, completed reviews are frozen. */
    public void validateUpdate(UUID id, Object body) {
        ObjectNode current = rows.require(TABLE, id);
        ObjectNode v = crud.toColumns(TABLE, body);
        KycRules.requireOpenReview(text(current, "status"));
        if (v.has("status") && !text(v, "status").equals(text(current, "status"))) {
            throw ApiException.unprocessable("status_change_not_allowed",
                    "Review status changes through assign, request-update and complete");
        }
        if (v.has("outcome") || v.has("completed_at")) {
            throw ApiException.unprocessable("status_change_not_allowed", "Use complete to record an outcome");
        }
    }

    @Transactional
    public ObjectNode assign(UUID id, UUID analyst) {
        ObjectNode r = rows.lock(TABLE, id);
        String status = text(r, "status");
        KycRules.requireOpenReview(status);
        ObjectNode v = rows.object();
        v.put("assigned_to", analyst.toString());
        if ("DUE".equals(status) || "OVERDUE".equals(status)) {
            v.put("status", "IN_PROGRESS");
        }
        return rows.update(TABLE, id, v);
    }

    @Transactional
    public ObjectNode requestUpdate(UUID id, String message) {
        ObjectNode r = rows.lock(TABLE, id);
        KycRules.requireOpenReview(text(r, "status"));
        ObjectNode v = rows.object();
        v.put("status", "IN_PROGRESS");
        v.put("customer_outreach_at", KycSupport.now());
        if (message != null && !message.isBlank()) {
            String notes = text(r, "outcome_notes");
            v.put("outcome_notes", (notes == null ? "" : notes + "\n") + "Customer asked to update: " + message);
        }
        return rows.update(TABLE, id, v);
    }

    @Transactional
    public ObjectNode complete(UUID id, String outcome, String notes) {
        if (outcome == null || !KycRules.REVIEW_OUTCOMES.contains(outcome)) {
            throw ApiException.unprocessable("invalid_outcome", "outcome must be one of " + KycRules.REVIEW_OUTCOMES);
        }
        ObjectNode r = rows.lock(TABLE, id);
        KycRules.requireOpenReview(text(r, "status"));
        ObjectNode v = rows.object();
        v.put("status", "COMPLETED");
        v.put("outcome", outcome);
        if (notes != null) {
            v.put("outcome_notes", notes);
        }
        v.put("completed_at", KycSupport.now());
        ObjectNode completed = rows.update(TABLE, id, v);

        UUID profileId = uuid(r, "kyc_profile_id");
        ObjectNode profile = rows.lock(ProfileService.TABLE, profileId);
        LocalDate next = "EXIT_RELATIONSHIP".equals(outcome)
                ? null : KycRules.nextReviewDate(text(profile, "risk_level"), KycSupport.today());
        ObjectNode p = rows.object();
        UUID reviewer = KycSupport.principalId();
        if (reviewer != null) {
            p.put("last_reviewed_by", reviewer.toString());
        }
        if (next != null) {
            p.put("next_review_date", next.toString());
        } else {
            p.putNull("next_review_date");
        }
        if ("EDD_REQUIRED".equals(outcome)) {
            p.put("edd_required", true);
            p.putNull("edd_completed_at");
        }
        ObjectNode updatedProfile = rows.update(ProfileService.TABLE, profileId, p);
        if (next != null) {
            profiles.schedulePeriodicReview(updatedProfile, next);
        }
        kyc.publish("party.kyc_review.completed", TABLE, completed, KycReview.class);
        return completed;
    }
}
