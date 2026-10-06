package io.meridian.kyc.application;

import static io.meridian.kyc.application.KycSupport.bool;
import static io.meridian.kyc.application.KycSupport.date;
import static io.meridian.kyc.application.KycSupport.text;
import static io.meridian.kyc.application.KycSupport.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.kyc.api.generated.model.KycProfile;
import io.meridian.kyc.api.generated.model.KycReview;
import io.meridian.kyc.api.generated.model.KycRiskAssessment;
import io.meridian.kyc.domain.KycRules;
import io.meridian.kyc.domain.ProfileEvaluator;
import io.meridian.kyc.infrastructure.Rows;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

/** KYC profile lifecycle: provisioning, evaluation of evidence into tier/risk/status, enhanced due diligence. */
@Service
public class ProfileService {
    static final String TABLE = "kyc_profile";

    private final Rows rows;
    private final KycSupport kyc;

    public ProfileService(Rows rows, KycSupport kyc) {
        this.rows = rows;
        this.kyc = kyc;
    }

    /** Opens the KYC profile of a customer (one per customer); status starts NOT_STARTED. */
    @org.springframework.transaction.annotation.Transactional
    public ObjectNode create(Object body) {
        ObjectNode v = kyc.columns(TABLE, body);
        UUID customerId = uuid(v, "customer_id");
        Optional<ObjectNode> existing = rows.query(
                "select row_to_json(t)::text from kyc_profile t where t.customer_id = ?", customerId).stream().findFirst();
        if (existing.isPresent()) {
            throw ApiException.conflict("profile_exists", "Customer already has KYC profile " + text(existing.get(), "id"));
        }
        v.put("status", "NOT_STARTED");
        ObjectNode row = rows.insert(TABLE, v);
        kyc.publish("party.kyc_profile.created", TABLE, row, KycProfile.class);
        return row;
    }

    /**
     * The (locked) profile that evidence is recorded against; it must exist (POST /v1/kyc/profiles) and belong to the
     * customer named in the request.
     */
    public ObjectNode forCustomer(UUID profileId, UUID customerId) {
        if (profileId == null || customerId == null) {
            throw ApiException.unprocessable("profile_required", "kyc_profile_id and customer_id are required");
        }
        ObjectNode profile = rows.query(
                "select row_to_json(t)::text from kyc_profile t where t.id = ? for update", profileId).stream().findFirst()
                .orElseThrow(() -> ApiException.unprocessable("profile_not_found",
                        "KYC profile " + profileId + " does not exist; open it with POST /v1/kyc/profiles"));
        if (!customerId.equals(uuid(profile, "customer_id"))) {
            throw ApiException.unprocessable("profile_customer_mismatch",
                    "KYC profile " + profileId + " belongs to another customer");
        }
        return profile;
    }

    /** Profile that must already exist (references from request bodies → 422). */
    public ObjectNode existing(UUID profileId) {
        return rows.find(TABLE, profileId).orElseThrow(() ->
                ApiException.unprocessable("profile_not_found", "KYC profile " + profileId + " does not exist"));
    }

    @Transactional
    public ObjectNode evaluate(UUID id) {
        return evaluate(id, "MANUAL");
    }

    @Transactional
    public ObjectNode evaluate(UUID id, String trigger) {
        ObjectNode p = rows.lock(TABLE, id);
        ProfileEvaluator.Evidence evidence = new ProfileEvaluator.Evidence(
                rows.query("select row_to_json(t)::text from kyc_document t where t.kyc_profile_id = ?", id).stream()
                        .map(d -> new ProfileEvaluator.Document(text(d, "document_type"), text(d, "status"),
                                date(d, "expiry_date"), text(d, "issuing_country")))
                        .toList(),
                rows.query("select row_to_json(t)::text from kyc_check t where t.kyc_profile_id = ?", id).stream()
                        .map(c -> new ProfileEvaluator.Check(text(c, "check_type"), text(c, "status")))
                        .toList(),
                rows.query("select row_to_json(t)::text from kyc_screening t where t.kyc_profile_id = ?", id).stream()
                        .map(s -> new ProfileEvaluator.Screening(text(s, "screening_type"), text(s, "status")))
                        .toList(),
                bool(p, "edd_required"), p.hasNonNull("edd_completed_at"));
        LocalDate today = KycSupport.today();
        ProfileEvaluator.Result r = ProfileEvaluator.evaluate(evidence, today);

        ObjectNode assessment = rows.object();
        assessment.put("kyc_profile_id", id.toString());
        assessment.put("customer_id", text(p, "customer_id"));
        assessment.put("model_version", ProfileEvaluator.MODEL_VERSION);
        assessment.put("score", r.score());
        assessment.put("risk_level", r.riskLevel());
        assessment.set("factors", kyc.tree(r.factors()));
        assessment.put("trigger", trigger);
        ObjectNode assessed = rows.insert("kyc_risk_assessment", assessment);

        String oldStatus = text(p, "status");
        String oldRisk = text(p, "risk_level");
        ObjectNode v = rows.object();
        v.put("identity_verified", r.identityVerified());
        v.put("address_verified", r.addressVerified());
        v.put("screening_clear", r.screeningClear());
        v.put("achieved_tier", r.achievedTier());
        v.put("risk_score", r.score());
        v.put("risk_level", r.riskLevel());
        v.put("status", r.status());
        v.put("edd_required", r.eddRequired());
        if (r.earliestExpiry() != null) {
            v.put("expires_at", r.earliestExpiry() + "T00:00:00Z");
        }
        boolean becameVerified = "VERIFIED".equals(r.status()) && !"VERIFIED".equals(oldStatus);
        LocalDate nextReview = null;
        if ("VERIFIED".equals(r.status())) {
            if (becameVerified) {
                v.put("verified_at", KycSupport.now());
            }
            nextReview = KycRules.nextReviewDate(r.riskLevel(), today);
            if (nextReview != null && (becameVerified || !p.hasNonNull("next_review_date")
                    || !Objects.equals(oldRisk, r.riskLevel()))) {
                v.put("next_review_date", nextReview.toString());
            } else {
                nextReview = date(p, "next_review_date");
            }
        }
        ObjectNode updated = rows.update(TABLE, id, v);
        if (!Objects.equals(oldStatus, r.status())) {
            kyc.publish("party.kyc_profile.status_changed", TABLE, updated, KycProfile.class);
        }
        if (becameVerified) {
            kyc.publish("party.kyc_profile.verified", TABLE, updated, KycProfile.class);
        }
        if (!Objects.equals(oldRisk, r.riskLevel())) {
            kyc.publish("party.kyc_profile.risk_changed", "kyc_risk_assessment", assessed, KycRiskAssessment.class);
        }
        if (nextReview != null) {
            schedulePeriodicReview(updated, nextReview);
        }
        return updated;
    }

    /** Creates a PERIODIC review for the profile unless an open review already exists. */
    public void schedulePeriodicReview(ObjectNode profile, LocalDate dueDate) {
        UUID id = uuid(profile, "id");
        List<ObjectNode> open = rows.query("""
                select row_to_json(t)::text from kyc_review t
                where t.kyc_profile_id = ? and t.status not in ('COMPLETED', 'CANCELLED')""", id);
        if (!open.isEmpty() || dueDate == null) {
            return;
        }
        ObjectNode r = rows.object();
        r.put("kyc_profile_id", id.toString());
        r.put("customer_id", text(profile, "customer_id"));
        r.put("trigger", "PERIODIC");
        r.put("due_date", dueDate.toString());
        r.put("status", KycRules.initialReviewStatus(dueDate, KycSupport.today()));
        ObjectNode review = rows.insert("kyc_review", r);
        if ("DUE".equals(text(review, "status"))) {
            kyc.publish("party.kyc_review.due", "kyc_review", review, KycReview.class);
        }
    }

    @Transactional
    public ObjectNode requireEdd(UUID id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.unprocessable("reason_required", "A reason is required");
        }
        ObjectNode p = rows.lock(TABLE, id);
        if (bool(p, "edd_required") && !p.hasNonNull("edd_completed_at")) {
            throw ApiException.conflict("edd_already_required", "Enhanced due diligence is already pending");
        }
        String oldStatus = text(p, "status");
        ObjectNode v = rows.object();
        v.put("edd_required", true);
        v.putNull("edd_completed_at");
        v.put("notes", appendNote(p, "EDD required by " + KycSupport.principal() + ": " + reason));
        if ("VERIFIED".equals(oldStatus)) {
            v.put("status", "REFER");
        }
        ObjectNode updated = rows.update(TABLE, id, v);
        if (!Objects.equals(oldStatus, text(updated, "status"))) {
            kyc.publish("party.kyc_profile.status_changed", TABLE, updated, KycProfile.class);
        }
        return updated;
    }

    @Transactional
    public ObjectNode completeEdd(UUID id, String notes) {
        if (notes == null || notes.isBlank()) {
            throw ApiException.unprocessable("notes_required", "EDD notes are required");
        }
        ObjectNode p = rows.lock(TABLE, id);
        if (!bool(p, "edd_required") || p.hasNonNull("edd_completed_at")) {
            throw ApiException.conflict("edd_not_required", "No enhanced due diligence is pending for this profile");
        }
        ObjectNode v = rows.object();
        v.put("edd_completed_at", KycSupport.now());
        v.put("notes", appendNote(p, "EDD completed by " + KycSupport.principal() + ": " + notes));
        rows.update(TABLE, id, v);
        return evaluate(id, "MANUAL");
    }

    private static String appendNote(ObjectNode p, String note) {
        String existing = text(p, "notes");
        return existing == null ? note : existing + "\n" + note;
    }
}
