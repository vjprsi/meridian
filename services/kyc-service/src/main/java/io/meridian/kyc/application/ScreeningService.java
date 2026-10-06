package io.meridian.kyc.application;

import static io.meridian.kyc.application.KycSupport.text;
import static io.meridian.kyc.application.KycSupport.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.kyc.api.generated.model.KycScreening;
import io.meridian.kyc.domain.KycRules;
import io.meridian.kyc.infrastructure.Rows;
import io.meridian.web.crud.CrudEngine;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** PEP / sanctions / adverse-media screening results and their disposition (maker-checker). */
@Service
public class ScreeningService {
    static final String TABLE = "kyc_screening";
    private static final Set<String> CREATE_STATUSES = Set.of("CLEAR", "POTENTIAL_MATCH", "CONFIRMED_MATCH", "ERROR");
    private static final Set<String> TRIGGERS = Set.of("ONBOARDING", "DATA_CHANGE", "LIST_UPDATE", "PERIODIC", "MANUAL");

    private final Rows rows;
    private final CrudEngine crud;
    private final KycSupport kyc;
    private final ProfileService profiles;

    public ScreeningService(Rows rows, CrudEngine crud, KycSupport kyc, ProfileService profiles) {
        this.rows = rows;
        this.crud = crud;
        this.kyc = kyc;
        this.profiles = profiles;
    }

    @Transactional
    public ObjectNode create(Object body) {
        ObjectNode v = crud.toColumns(TABLE, body);
        String status = text(v, "status");
        if (!CREATE_STATUSES.contains(status)) {
            throw ApiException.unprocessable("invalid_status",
                    "A new screening must be CLEAR, POTENTIAL_MATCH, CONFIRMED_MATCH or ERROR; use disposition for FALSE_POSITIVE");
        }
        String trigger = text(v, "trigger").toUpperCase(java.util.Locale.ROOT);
        if (!TRIGGERS.contains(trigger)) {
            throw ApiException.unprocessable("invalid_trigger", "trigger must be one of " + TRIGGERS);
        }
        v.put("trigger", trigger);
        if (v.hasNonNull("kyc_provider_id") && rows.find(ProviderService.TABLE, uuid(v, "kyc_provider_id")).isEmpty()) {
            throw ApiException.unprocessable("provider_not_found", "KYC provider does not exist");
        }
        int matches = matchCount(v.get("matches"));
        if (!v.hasNonNull("match_count")) {
            v.put("match_count", matches);
        }
        if (v.get("match_count").asInt() < 0) {
            throw ApiException.unprocessable("invalid_match_count", "match_count cannot be negative");
        }
        if ("CLEAR".equals(status) && v.get("match_count").asInt() > 0) {
            throw ApiException.unprocessable("inconsistent_status", "A CLEAR screening cannot have matches");
        }
        if (KycRules.OPEN_MATCH.contains(status) && v.get("match_count").asInt() == 0) {
            v.put("match_count", 1);
        }
        for (String f : new String[] {"reviewed_by", "reviewed_at", "review_comment"}) {
            v.remove(f);
        }
        profiles.forCustomer(uuid(v, "kyc_profile_id"), uuid(v, "customer_id"));
        ObjectNode row = rows.insert(TABLE, v);
        if (KycRules.OPEN_MATCH.contains(status)) {
            kyc.publish("party.kyc_screening.match_found", TABLE, row, KycScreening.class);
        }
        return row;
    }

    private static int matchCount(JsonNode matches) {
        return matches != null && matches.isArray() ? matches.size() : 0;
    }

    @Transactional
    public ObjectNode disposition(UUID id, String target, String comment) {
        if (comment == null || comment.isBlank()) {
            throw ApiException.unprocessable("review_comment_required", "A disposition rationale is required");
        }
        ObjectNode s = rows.lock(TABLE, id);
        KycRules.requireDisposable(text(s, "status"), target);
        KycRules.requireFourEyes(text(s, "created_by"), KycSupport.principal(), "screening");
        ObjectNode v = rows.object();
        v.put("status", target);
        v.put("review_comment", comment);
        v.put("reviewed_at", KycSupport.now());
        UUID reviewer = KycSupport.principalId();
        if (reviewer != null) {
            v.put("reviewed_by", reviewer.toString());
        }
        ObjectNode updated = rows.update(TABLE, id, v);
        kyc.publish("party.kyc_screening.disposed", TABLE, updated, KycScreening.class);
        return updated;
    }
}
