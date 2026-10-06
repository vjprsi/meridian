package io.meridian.loanorigination.application;

import static io.meridian.loanorigination.infrastructure.Rows.str;
import static io.meridian.loanorigination.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.loanorigination.config.OriginationProperties;
import io.meridian.loanorigination.domain.Lifecycles;
import io.meridian.loanorigination.infrastructure.Rows;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Credit bureau pulls through pluggable adapters, report reuse, and asynchronous delivery by callback. */
@Service
public class BureauService {
    private static final Logger log = LoggerFactory.getLogger(BureauService.class);
    static final Set<String> ASSESSABLE = Set.of("SUBMITTED", "IN_REVIEW", "BUREAU_PENDING", "SCORED", "REFERRED");
    private static final String TABLE = "bureau_report";

    private final Support support;
    private final Rows rows;
    private final Events events;
    private final Map<String, BureauAdapter> adapters;
    private final OriginationProperties props;
    private final CallbackSecurity callbacks;

    public BureauService(Support support, List<BureauAdapter> adapters, OriginationProperties props,
                         CallbackSecurity callbacks) {
        this.support = support;
        this.rows = support.rows();
        this.events = support.events();
        this.adapters = adapters.stream().collect(Collectors.toMap(a -> a.provider().toUpperCase(Locale.ROOT),
                Function.identity()));
        this.props = props;
        this.callbacks = callbacks;
    }

    @Transactional
    public ObjectNode pull(UUID appId, UUID applicantId, String providerName, String pullType, boolean force) {
        ObjectNode app = support.lockApp(appId);
        if (!ASSESSABLE.contains(str(app, "status"))) {
            throw ApiException.conflict("invalid_transition", "Application " + appId + " is " + str(app, "status")
                    + "; bureau reports are pulled for submitted applications under assessment");
        }
        return pull(app, applicantId, providerName, pullType, force);
    }

    /** Pull for a locked application (also used by automatic decisioning). */
    ObjectNode pull(ObjectNode app, UUID applicantId, String providerName, String pullType, boolean force) {
        UUID appId = uuid(app, "id");
        if (!Rows.bool(app, "consent_bureau")) {
            throw ApiException.unprocessable("consent_required", "The applicant has not consented to a bureau check");
        }
        ObjectNode applicant = rows.queryOne("""
                select row_to_json(a)::text from loan_applicant a where id = ? and loan_application_id = ?""",
                applicantId, appId).orElseThrow(() -> ApiException.unprocessable("applicant_not_found",
                "loan_applicant_id " + applicantId + " is not an applicant of application " + appId));
        String provider = (providerName == null || providerName.isBlank() ? props.defaultBureauProvider() : providerName)
                .toUpperCase(Locale.ROOT);
        BureauAdapter adapter = adapters.get(provider);
        if (adapter == null) {
            throw ApiException.unprocessable("unsupported_bureau_provider", "No bureau adapter for provider " + provider
                    + " (available: " + String.join(", ", adapters.keySet()) + ")");
        }
        String type = pullType == null || pullType.isBlank() ? "HARD" : pullType.toUpperCase(Locale.ROOT);
        if (!Set.of("HARD", "SOFT").contains(type)) {
            throw ApiException.unprocessable("invalid_pull_type", "pull_type must be HARD or SOFT");
        }
        if (!force) {
            Optional<ObjectNode> reusable = rows.queryOne("""
                    select row_to_json(b)::text from bureau_report b
                    where loan_applicant_id = ? and (status = 'REQUESTED'
                       or (status in ('RECEIVED', 'NO_HIT') and valid_until >= (now() at time zone 'UTC')::date))
                    order by requested_at desc limit 1""", applicantId);
            if (reusable.isPresent()) {
                return reusable.get();
            }
        }
        ObjectNode v = rows.object();
        v.put("loan_application_id", appId.toString());
        v.put("loan_applicant_id", applicantId.toString());
        v.put("customer_id", str(applicant, "customer_id"));
        v.put("provider", provider);
        v.put("pull_type", type);
        v.put("status", "REQUESTED");
        v.put("consent_reference", "consent:" + appId + ":" + (str(app, "consent_at") == null ? "application" : str(app, "consent_at")));
        ObjectNode report = rows.insert(TABLE, v);
        events.publish("lending.bureau_report.requested", TABLE, report);
        BureauAdapter.Result result;
        try {
            result = adapter.pull(new BureauAdapter.Request(uuid(report, "id"), uuid(applicant, "customer_id"), type,
                    str(report, "consent_reference")));
        } catch (RuntimeException e) {
            log.warn("bureau pull via {} failed", provider, e);
            result = new BureauAdapter.Result("FAILED", null, null, null, null, null, null, null, null, null, List.of(),
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        ObjectNode updated = apply(report, result);
        if ("REQUESTED".equals(result.status())) {
            callbacks.register("BUREAU", provider, result.reference(), uuid(report, "id"));
            if (!"BUREAU_PENDING".equals(str(app, "status"))) {
                support.transition(app, "BUREAU_PENDING");
            }
        }
        return updated;
    }

    /** Asynchronous report delivery, run as the owning tenant after the signature was verified. */
    @Transactional
    public Map<String, Object> callback(String provider, UUID reportId, ObjectNode body) {
        ObjectNode report = rows.lock(TABLE, reportId);
        if (!str(report, "provider").equalsIgnoreCase(provider)) {
            throw ApiException.notFound("bureau report", Json.text(body, "bureau_reference"));
        }
        String status = Json.text(body, "status") == null ? "" : Json.text(body, "status").toUpperCase(Locale.ROOT);
        if (!Set.of("RECEIVED", "NO_HIT", "FAILED").contains(status)) {
            throw ApiException.unprocessable("invalid_status", "status must be RECEIVED, NO_HIT or FAILED");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bureau_report_id", reportId.toString());
        if (!"REQUESTED".equals(str(report, "status"))) {
            if (status.equals(str(report, "status"))) {
                out.put("status", status);
                out.put("outcome", "DUPLICATE");
                return out;
            }
            throw ApiException.conflict("invalid_transition", "Bureau report " + reportId + " is already " + str(report, "status"));
        }
        List<Map<String, Object>> tradelines = new java.util.ArrayList<>();
        if (body.get("tradelines") instanceof ArrayNode a) {
            a.forEach(t -> {
                if (Json.plain(t) instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked") Map<String, Object> line = (Map<String, Object>) m;
                    tradelines.add(line);
                }
            });
        }
        BureauAdapter.Result result = new BureauAdapter.Result(status, str(report, "bureau_reference"),
                Json.integer(body, "bureau_score"), Json.text(body, "score_band"), Json.decimal(body, "total_debt"),
                Json.decimal(body, "monthly_debt_service"), Json.integer(body, "open_accounts"),
                Json.integer(body, "delinquent_accounts"), Json.integer(body, "worst_dpd_24m"),
                Json.integer(body, "inquiries_6m"), tradelines, Json.text(body, "reason"));
        ObjectNode updated = apply(report, result);
        UUID appId = uuid(report, "loan_application_id");
        if (appId != null) {
            ObjectNode app = support.lockApp(appId);
            int pending = rows.scalar("select count(*) from bureau_report where loan_application_id = ? and status = 'REQUESTED'",
                    Integer.class, appId).orElse(0);
            if ("BUREAU_PENDING".equals(str(app, "status")) && pending == 0) {
                support.transition(app, app.hasNonNull("assigned_to") ? "IN_REVIEW" : "SUBMITTED");
            }
        }
        out.put("status", str(updated, "status"));
        out.put("outcome", "APPLIED");
        return out;
    }

    /** Latest usable report of an applicant (RECEIVED or NO_HIT, still valid), if any. */
    Optional<ObjectNode> latest(UUID applicantId) {
        return rows.queryOne("""
                select row_to_json(b)::text from bureau_report b
                where loan_applicant_id = ? and status in ('RECEIVED', 'NO_HIT')
                  and valid_until >= (now() at time zone 'UTC')::date
                order by received_at desc limit 1""", applicantId);
    }

    boolean pending(UUID appId) {
        return rows.scalar("select count(*) from bureau_report where loan_application_id = ? and status = 'REQUESTED'",
                Integer.class, appId).orElse(0) > 0;
    }

    private ObjectNode apply(ObjectNode report, BureauAdapter.Result r) {
        ObjectNode v = rows.object();
        v.put("status", r.status());
        if (r.reference() != null) {
            v.put("bureau_reference", r.reference());
        }
        if (!"REQUESTED".equals(r.status())) {
            v.put("received_at", support.dbNow().toString());
            if (r.score() != null) {
                v.put("bureau_score", r.score());
            }
            putText(v, "score_band", r.band());
            putDec(v, "total_debt", r.totalDebt());
            putDec(v, "monthly_debt_service", r.monthlyDebtService());
            putInt(v, "open_accounts", r.openAccounts());
            putInt(v, "delinquent_accounts", r.delinquentAccounts());
            putInt(v, "worst_dpd_24m", r.worstDpd24m());
            putInt(v, "inquiries_6m", r.inquiries6m());
            v.set("tradelines", rows.json().valueToTree(r.tradelines() == null ? List.of() : r.tradelines()));
            ObjectNode summary = v.putObject("summary");
            summary.put("status", r.status());
            if (r.score() != null) {
                summary.put("score", r.score());
            }
            putText(summary, "band", r.band());
            putInt(summary, "open_accounts", r.openAccounts());
            putInt(summary, "delinquent_accounts", r.delinquentAccounts());
            putInt(summary, "worst_dpd_24m", r.worstDpd24m());
            summary.put("tradeline_count", r.tradelines() == null ? 0 : r.tradelines().size());
            putText(v, "failure_reason", r.failureReason());
            if (!"FAILED".equals(r.status())) {
                v.put("valid_until", support.dbToday().plusDays(props.bureauValidityDays()).toString());
            }
        }
        ObjectNode updated = rows.update(TABLE, uuid(report, "id"), v);
        if (Set.of("RECEIVED", "NO_HIT").contains(r.status())) {
            events.publish("lending.bureau_report.received", TABLE, updated);
        }
        return updated;
    }

    private static void putText(ObjectNode n, String f, String v) {
        if (v != null) {
            n.put(f, v);
        }
    }

    private static void putInt(ObjectNode n, String f, Integer v) {
        if (v != null) {
            n.put(f, v);
        }
    }

    private static void putDec(ObjectNode n, String f, BigDecimal v) {
        if (v != null) {
            n.put(f, v.toPlainString());
        }
    }

    static boolean decided(String status) {
        return !Lifecycles.APPLICATION_EDITABLE.contains(status);
    }
}
