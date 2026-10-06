package io.meridian.loanorigination.application;

import static io.meridian.loanorigination.infrastructure.Rows.str;
import static io.meridian.loanorigination.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.loanorigination.domain.Lifecycles;
import io.meridian.loanorigination.domain.ScorecardEngine;
import io.meridian.loanorigination.infrastructure.Rows;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Scorecard versions: validated definitions, champion activation under model-risk four-eyes, challenger traffic
 * routing and retirement. Also selects the version that scores an application (champion, or a challenger for its
 * deterministic share of traffic).
 */
@Service
public class ScorecardService {
    static final String VERSION = "scorecard_version";
    private static final Set<String> EDITABLE = Set.of("DRAFT", "VALIDATING");

    private final Support support;
    private final Rows rows;
    private final Events events;

    public ScorecardService(Support support) {
        this.support = support;
        this.rows = support.rows();
        this.events = support.events();
    }

    /** Parsed model of a stored version row. */
    public static ScorecardEngine.Model model(JsonNode version) {
        return ScorecardEngine.model(version.path("base_points").asInt(0), Json.list(version.get("features")),
                Json.list(version.get("cutoffs")), Rows.integer(version, "target_score"), Rows.dec(version, "target_odds"),
                Rows.dec(version, "points_to_double_odds"));
    }

    @Transactional
    public ObjectNode createVersion(UUID scorecardId, Object body) {
        rows.get("scorecard", scorecardId);
        ObjectNode v = rows.columns(VERSION, body);
        ApplicationService.samePath(v, "scorecard_id", scorecardId);
        v.put("scorecard_id", scorecardId.toString());
        v.put("status", "DRAFT");
        validate(v);
        if (rows.scalar("select count(*) from scorecard_version where scorecard_id = ? and version = ?", Integer.class,
                scorecardId, str(v, "version")).orElse(0) > 0) {
            throw ApiException.conflict("duplicate_version", "Version " + str(v, "version") + " already exists");
        }
        return rows.insert(VERSION, v);
    }

    /** Pre-checks of a PATCH: only DRAFT/VALIDATING versions change; the merged definition must stay valid. */
    @Transactional(readOnly = true)
    public void checkUpdate(UUID scorecardId, UUID id, JsonNode req) {
        ObjectNode current = version(scorecardId, id, false);
        if (!EDITABLE.contains(str(current, "status"))) {
            throw ApiException.conflict("version_not_editable", "Scorecard version " + id + " is " + str(current, "status")
                    + "; create a new version instead");
        }
        ObjectNode merged = current.deepCopy();
        req.propertyNames().forEach(k -> {
            if (!req.get(k).isNull()) {
                merged.set(k, req.get(k));
            }
        });
        validate(merged);
    }

    @Transactional
    public ObjectNode activate(UUID scorecardId, UUID id, UUID approvalRequestId) {
        ObjectNode v = version(scorecardId, id, true);
        Support.require(Lifecycles.SCORECARD_VERSION, "Scorecard version", str(v, "status"), "CHAMPION");
        Support.fourEyes(str(v, "created_by"), "Scorecard activation (model risk approval)");
        validate(v);
        for (ObjectNode champion : rows.query("""
                select row_to_json(s)::text from scorecard_version s where scorecard_id = ? and status = 'CHAMPION'
                  and id <> ? for update""", scorecardId, id)) {
            retireRow(champion);
        }
        ObjectNode values = rows.object();
        values.put("status", "CHAMPION");
        values.putNull("challenger_traffic_pct");
        values.put("approved_by", TenantContext.principal());
        values.put("activated_at", support.dbNow().toString());
        if (approvalRequestId != null) {
            values.put("approval_request_id", approvalRequestId.toString());
        }
        ObjectNode updated = rows.update(VERSION, id, values);
        events.publish("lending.scorecard_version.activated", VERSION, updated);
        return updated;
    }

    @Transactional
    public ObjectNode setChallenger(UUID scorecardId, UUID id, BigDecimal pct) {
        if (pct == null || pct.signum() <= 0 || pct.compareTo(new BigDecimal("0.5")) > 0) {
            throw ApiException.unprocessable("invalid_traffic_share", "challenger_traffic_pct must be > 0 and <= 0.5");
        }
        ObjectNode v = version(scorecardId, id, true);
        Support.require(Lifecycles.SCORECARD_VERSION, "Scorecard version", str(v, "status"), "CHALLENGER");
        Support.fourEyes(str(v, "created_by"), "Routing traffic to a challenger");
        validate(v);
        if (rows.scalar("select count(*) from scorecard_version where scorecard_id = ? and status = 'CHAMPION'",
                Integer.class, scorecardId).orElse(0) == 0) {
            throw ApiException.unprocessable("no_champion", "A challenger needs an active champion to compete with");
        }
        BigDecimal others = rows.scalar("""
                select coalesce(sum(challenger_traffic_pct), 0) from scorecard_version
                where scorecard_id = ? and status = 'CHALLENGER' and id <> ?""", BigDecimal.class, scorecardId, id)
                .orElse(BigDecimal.ZERO);
        if (others.add(pct).compareTo(new BigDecimal("0.5")) > 0) {
            throw ApiException.unprocessable("invalid_traffic_share",
                    "Challengers may take at most 50% of the traffic in total (others already take " + others.toPlainString() + ")");
        }
        ObjectNode values = rows.object();
        values.put("status", "CHALLENGER");
        values.put("challenger_traffic_pct", pct.toPlainString());
        values.put("approved_by", TenantContext.principal());
        return rows.update(VERSION, id, values);
    }

    @Transactional
    public ObjectNode retire(UUID scorecardId, UUID id) {
        ObjectNode v = version(scorecardId, id, true);
        Support.require(Lifecycles.SCORECARD_VERSION, "Scorecard version", str(v, "status"), "RETIRED");
        return retireRow(v);
    }

    /** Scores sample inputs without persisting anything. */
    @Transactional(readOnly = true)
    public ObjectNode test(UUID scorecardId, UUID id, JsonNode inputs) {
        ObjectNode v = version(scorecardId, id, false);
        if (inputs == null || !inputs.isObject()) {
            throw ApiException.unprocessable("invalid_inputs", "inputs must be an object of feature values");
        }
        ScorecardEngine.Model model = model(v);
        Map<String, Object> in = new LinkedHashMap<>();
        inputs.propertyNames().forEach(k -> in.put(k, Json.plain(inputs.get(k))));
        ScorecardEngine.Result r = ScorecardEngine.score(model, in);
        ObjectNode out = AssessmentService.resultJson(rows, r);
        out.put("scorecard_version_id", id.toString());
        out.put("version", str(v, "version"));
        return out;
    }

    /** Version used for an application: a challenger for its traffic share (deterministic), else the champion. */
    @Transactional(readOnly = true)
    public Selected select(String scorecardType, UUID applicationId) {
        List<ObjectNode> candidates = rows.query("""
                select row_to_json(v)::text from scorecard_version v join scorecard s on s.id = v.scorecard_id
                where s.scorecard_type = ? and s.is_active and v.status in ('CHAMPION', 'CHALLENGER')
                order by s.created_at desc, v.status, v.id""", scorecardType);
        ObjectNode champion = candidates.stream().filter(c -> "CHAMPION".equals(str(c, "status"))).findFirst().orElse(null);
        if (champion == null) {
            return null;
        }
        double bucket = ScorecardEngine.bucket(applicationId.toString());
        double cumulative = 0;
        for (ObjectNode c : candidates) {
            if ("CHALLENGER".equals(str(c, "status")) && str(c, "scorecard_id").equals(str(champion, "scorecard_id"))) {
                BigDecimal pct = Rows.dec(c, "challenger_traffic_pct");
                cumulative += pct == null ? 0 : pct.doubleValue();
                if (bucket < cumulative) {
                    return new Selected(c, "CHALLENGER");
                }
            }
        }
        return new Selected(champion, "CHAMPION");
    }

    public record Selected(ObjectNode version, String routing) {}

    private ObjectNode retireRow(ObjectNode v) {
        ObjectNode values = rows.object();
        values.put("status", "RETIRED");
        values.putNull("challenger_traffic_pct");
        values.put("retired_at", support.dbNow().toString());
        ObjectNode updated = rows.update(VERSION, uuid(v, "id"), values);
        events.publish("lending.scorecard_version.retired", VERSION, updated);
        return updated;
    }

    private ObjectNode version(UUID scorecardId, UUID id, boolean lock) {
        return rows.queryOne("select row_to_json(v)::text from scorecard_version v where id = ? and scorecard_id = ?"
                + (lock ? " for update" : ""), id, scorecardId)
                .orElseThrow(() -> ApiException.notFound("scorecard version", id));
    }

    private static void validate(JsonNode v) {
        try {
            model(v);
        } catch (ScorecardEngine.InvalidScorecard e) {
            throw ApiException.unprocessable("invalid_scorecard", e.getMessage());
        }
    }
}
