package io.meridian.card.application;

import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.card.domain.ControlRules;
import io.meridian.card.domain.ProgramRules;
import io.meridian.card.infrastructure.Rows;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Card programs and products: validation, certification, maker-checker go-live, suspension. */
@Service
public class ProgramService {
    /** Program fields that define issued PANs and funding; frozen once the program has been live. */
    static final Set<String> FROZEN_WHEN_LIVE = Set.of("bin", "bin_range_start", "bin_range_end", "pan_length",
            "scheme", "funding_model", "currency");

    /** Resource type of program go-live approvals in workflow-service (G11). */
    public static final String GO_LIVE_RESOURCE = "card_program";
    static final String GO_LIVE_ACTION = "go_live";

    private final Rows rows;
    private final Events events;
    private final Approvals approvals;

    public ProgramService(Rows rows, Events events, Approvals approvals) {
        this.rows = rows;
        this.events = events;
        this.approvals = approvals;
    }

    // ------------------------------------------------------------------ programs

    @Transactional
    public JsonNode createProgram(JsonNode req) {
        validateProgram(req);
        ObjectNode values = rows.columns("card_program", req);
        return rows.insert("card_program", values);
    }

    /** Validates a PATCH against the current row (BIN fields frozen once live) and returns the merged view. */
    @Transactional(readOnly = true)
    public void checkProgramUpdate(UUID id, JsonNode req) {
        ObjectNode current = rows.get("card_program", id);
        String status = Rows.str(current, "status");
        boolean live = !"DRAFT".equals(status) && !"CERTIFICATION".equals(status);
        for (String f : FROZEN_WHEN_LIVE) {
            if (live && req.hasNonNull(f) && !req.get(f).asString().equals(Rows.str(current, f))) {
                throw ApiException.unprocessable("field_not_updatable",
                        f + " cannot be changed once the program has gone live");
            }
        }
        ObjectNode merged = current.deepCopy();
        req.propertyNames().forEach(n -> {
            if (!req.get(n).isNull()) {
                merged.set(n, req.get(n));
            }
        });
        validateProgram(merged);
    }

    @Transactional
    public JsonNode certify(UUID id) {
        ObjectNode p = rows.lock("card_program", id);
        String to = ProgramRules.transition("certify", Rows.str(p, "status"));
        rows.execute("update card_program set status = ?, certified_at = now(), status_reason = null, updated_by = ? "
                + "where id = ?", to, TenantContext.principal(), id);
        ObjectNode row = rows.get("card_program", id);
        events.publish("cards.card_program.status_changed", "card_program", row);
        requestGoLive(row);
        return row;
    }

    /**
     * Central maker-checker (G11): certification submits the program for go-live; the checker decides in the
     * workflow inbox and {@link ProgramGoLiveApprovalHandler} applies it. row_version fingerprints the certified
     * content (any later PATCH makes the decision stale). BIN and range only, never a PAN.
     */
    private void requestGoLive(JsonNode p) {
        Integer rowVersion = Rows.integer(p, "row_version");
        Map<String, Object> payload = new LinkedHashMap<>();
        for (String f : new String[] {"code", "name", "scheme", "program_type", "funding_model", "currency", "country",
                "bin", "bin_range_start", "bin_range_end", "sponsor_bank", "legal_entity_id"}) {
            payload.put(f, Rows.str(p, f));
        }
        payload.put("row_version", rowVersion);
        UUID id = Rows.uuid(p, "id");
        approvals.request(ApprovalSpec.of(GO_LIVE_RESOURCE, id, GO_LIVE_ACTION,
                        "Card program " + Rows.str(p, "code") + " go-live", payload)
                        .withSummary(Rows.str(p, "name") + " (" + Rows.str(p, "scheme") + " " + Rows.str(p, "funding_model")
                                + ", BIN " + Rows.str(p, "bin") + ")"),
                "go-live:" + id + ":" + rowVersion);
    }

    /** True while the program is in CERTIFICATION with the submitted content (row_version) unchanged. */
    @Transactional(readOnly = true)
    public boolean awaitsGoLive(UUID id, Integer rowVersion) {
        return rows.find("card_program", id)
                .filter(p -> "CERTIFICATION".equals(Rows.str(p, "status")))
                .filter(p -> rowVersion == null || rowVersion.equals(Rows.integer(p, "row_version")))
                .isPresent();
    }

    /** Go-live declined by the checker: the program returns to DRAFT (to be reworked and re-certified). */
    @Transactional
    public JsonNode rejectGoLive(UUID id, String reason) {
        ObjectNode p = rows.lock("card_program", id);
        String to = ProgramRules.transition("reject_go_live", Rows.str(p, "status"));
        rows.execute("update card_program set status = ?, status_reason = ?, certified_at = null, updated_by = ? "
                + "where id = ?", to, reason, TenantContext.principal(), id);
        ObjectNode row = rows.get("card_program", id);
        events.publish("cards.card_program.status_changed", "card_program", row);
        approvals.cancelOpen(GO_LIVE_RESOURCE, id, null, "go-live rejected");
        return row;
    }

    @Transactional
    public JsonNode activate(UUID id, String reason) {
        ObjectNode p = rows.lock("card_program", id);
        String to = ProgramRules.transition("activate", Rows.str(p, "status"));
        String principal = TenantContext.principal();
        if (principal.equals(Rows.str(p, "created_by"))) {
            throw ApiException.unprocessable("four_eyes_violation",
                    "A program cannot be approved for go-live by the principal that created it");
        }
        rows.execute("update card_program set status = ?, status_reason = ?, launched_at = coalesce(launched_at, now()), "
                + "activated_by = ?, updated_by = ? where id = ?", to, reason, principal, principal, id);
        ObjectNode row = rows.get("card_program", id);
        events.publish("cards.card_program.activated", "card_program", row);
        if ("CERTIFICATION".equals(Rows.str(p, "status"))) {
            approvals.cancelOpen(GO_LIVE_RESOURCE, id, null, "activated directly");
        }
        return row;
    }

    @Transactional
    public JsonNode suspend(UUID id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("missing_parameter", "reason is required");
        }
        ObjectNode p = rows.lock("card_program", id);
        String to = ProgramRules.transition("suspend", Rows.str(p, "status"));
        rows.execute("update card_program set status = ?, status_reason = ?, updated_by = ? where id = ?",
                to, reason, TenantContext.principal(), id);
        ObjectNode row = rows.get("card_program", id);
        events.publish("cards.card_program.status_changed", "card_program", row);
        return row;
    }

    static void validateProgram(JsonNode p) {
        Integer panLength = Rows.integer(p, "pan_length");
        ProgramRules.validateBin(Rows.str(p, "bin"), Rows.str(p, "bin_range_start"), Rows.str(p, "bin_range_end"),
                panLength == null ? 16 : panLength);
        String funding = Rows.str(p, "funding_model");
        if ("JIT".equals(funding) && (Rows.str(p, "jit_webhook_url") == null || Rows.str(p, "jit_webhook_url").isBlank())) {
            throw ApiException.unprocessable("jit_webhook_required", "A JIT program requires jit_webhook_url");
        }
        String url = Rows.str(p, "jit_webhook_url");
        if (url != null && !url.startsWith("https://") && !url.startsWith("http://")) {
            throw ApiException.unprocessable("invalid_jit_webhook_url", "jit_webhook_url must be an http(s) URL");
        }
        positive(p, "jit_timeout_ms", 1, 10_000);
        positive(p, "fraud_timeout_ms", 1, 5_000);
        positive(p, "fraud_decline_score", 1, 1000);
        positive(p, "auth_hold_days", 1, 31);
        positive(p, "renewal_window_days", 1, 365);
        if (Rows.dec(p, "stand_in_max_amount") != null && Rows.dec(p, "stand_in_max_amount").signum() < 0) {
            throw ApiException.unprocessable("invalid_amount", "stand_in_max_amount must not be negative");
        }
        if (Rows.dec(p, "fx_markup_rate") != null && (Rows.dec(p, "fx_markup_rate").signum() < 0
                || Rows.dec(p, "fx_markup_rate").compareTo(new java.math.BigDecimal("0.2")) > 0)) {
            throw ApiException.unprocessable("invalid_rate", "fx_markup_rate must be between 0 and 0.2");
        }
        JsonNode dc = p.get("default_controls");
        if (dc != null && !dc.isNull()) {
            for (JsonNode c : dc) {
                ControlRules.validateControl(ControlSpecs.from(c));
            }
        }
    }

    private static void positive(JsonNode p, String f, int min, int max) {
        Integer v = Rows.integer(p, f);
        if (v != null && (v < min || v > max)) {
            throw ApiException.unprocessable("invalid_" + f, f + " must be between " + min + " and " + max);
        }
    }

    // ------------------------------------------------------------------ products

    @Transactional
    public JsonNode createProduct(JsonNode req) {
        validateProduct(req);
        return rows.insert("card_product", rows.columns("card_product", req));
    }

    @Transactional(readOnly = true)
    public void checkProductUpdate(UUID id, JsonNode req) {
        ObjectNode merged = rows.get("card_product", id).deepCopy();
        req.propertyNames().forEach(n -> {
            if (!req.get(n).isNull()) {
                merged.set(n, req.get(n));
            }
        });
        validateProduct(merged);
    }

    private void validateProduct(JsonNode req) {
        UUID programId = Rows.uuid(req, "card_program_id");
        ObjectNode program = rows.find("card_program", programId)
                .orElseThrow(() -> ApiException.unprocessable("program_not_found", "card program " + programId + " not found"));
        if ("RETIRED".equals(Rows.str(program, "status"))) {
            throw ApiException.unprocessable("program_retired", "card program is retired");
        }
        JsonNode ff = req.get("form_factors");
        if (ff == null || !ff.isArray() || ff.isEmpty()) {
            throw ApiException.unprocessable("form_factors_required", "form_factors must list VIRTUAL and/or PHYSICAL");
        }
        Integer years = Rows.integer(req, "expiry_years");
        if (years != null && (years < 1 || years > 10)) {
            throw ApiException.unprocessable("invalid_expiry_years", "expiry_years must be between 1 and 10");
        }
        Integer max = Rows.integer(req, "max_cards_per_customer");
        if (max != null && max < 1) {
            throw ApiException.unprocessable("invalid_max_cards", "max_cards_per_customer must be at least 1");
        }
        UUID bureau = Rows.uuid(req, "card_bureau_id");
        if (bureau != null && rows.find("card_bureau", bureau).isEmpty()) {
            throw ApiException.unprocessable("bureau_not_found", "card bureau " + bureau + " not found");
        }
    }

    @Transactional
    public JsonNode productTransition(UUID id, String action, String reason) {
        ObjectNode p = rows.lock("card_product", id);
        String to = ProgramRules.productTransition(action, Rows.str(p, "status"));
        if ("activate".equals(action)) {
            ObjectNode program = rows.get("card_program", Rows.uuid(p, "card_program_id"));
            if ("RETIRED".equals(Rows.str(program, "status"))) {
                throw ApiException.unprocessable("program_retired", "card program is retired");
            }
        }
        rows.execute("update card_product set status = ?, updated_by = ? where id = ?", to, TenantContext.principal(), id);
        ObjectNode row = rows.get("card_product", id);
        events.publish("cards.card_product.status_changed", "card_product", row);
        return row;
    }

    // ------------------------------------------------------------------ bureaus

    public static void validateBureau(JsonNode req) {
        String cutoff = Rows.str(req, "cutoff_time");
        if (cutoff != null && !ControlRules.isTime(cutoff)) {
            throw ApiException.unprocessable("invalid_cutoff_time", "cutoff_time must be HH:MM");
        }
    }
}
