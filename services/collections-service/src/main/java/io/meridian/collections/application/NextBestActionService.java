package io.meridian.collections.application;

import io.meridian.collections.config.CollectionsProperties;
import io.meridian.collections.domain.Lifecycles;
import io.meridian.collections.domain.NextBestActionModel;
import io.meridian.collections.infrastructure.Rows;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import java.time.Duration;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Next best action: gathers the case signals, runs {@link NextBestActionModel}, records the explained suggestion. */
@Service
public class NextBestActionService {
    static final String TABLE = "next_best_action";

    private final Rows rows;
    private final Events events;
    private final CaseService cases;
    private final CollectionsProperties props;

    public NextBestActionService(Rows rows, Events events, CaseService cases, CollectionsProperties props) {
        this.rows = rows;
        this.events = events;
        this.cases = cases;
        this.props = props;
    }

    @Transactional
    public ObjectNode suggest(UUID caseId) {
        ObjectNode c = cases.lockOpen(caseId);
        OffsetDateTime now = rows.dbNowTs();
        ObjectNode stats = rows.queryOne("""
                select json_build_object(
                  'rpc', count(*) filter (where outcome in ('RIGHT_PARTY_CONTACT', 'PROMISE_MADE', 'PAID')),
                  'no_answer', count(*) filter (where outcome in ('NO_ANSWER', 'FAILED')),
                  'refused', count(*) filter (where outcome = 'REFUSED'),
                  'recent', count(*) filter (where action_type in ('SMS','PUSH','EMAIL','WHATSAPP','CALL','FIELD_VISIT',
                                                                   'LETTER','LEGAL_NOTICE')
                                             and outcome <> 'SUPPRESSED' and performed_at > ?::timestamptz - interval '7 days'),
                  'oldest_recent', min(performed_at) filter (where action_type in ('SMS','PUSH','EMAIL','WHATSAPP','CALL',
                                                                   'FIELD_VISIT','LETTER','LEGAL_NOTICE')
                                             and outcome <> 'SUPPRESSED' and performed_at > ?::timestamptz - interval '7 days')
                )::text from collection_action where collection_case_id = ?""", now.toString(), now.toString(), caseId)
                .orElseThrow();
        int cap = props.defaultMaxContactsPerWeek();
        LocalTime[] quiet = {LocalTime.parse(props.defaultQuietStart()), LocalTime.parse(props.defaultQuietEnd())};
        UUID strategyId = Rows.uuid(c, "collection_strategy_id");
        if (strategyId != null) {
            ObjectNode s = rows.get("collection_strategy", strategyId);
            cap = Rows.integer(s, "max_contacts_per_week");
            LocalTime[] q = StrategyAdmin.quietHours(s.get("quiet_hours"));
            if (q != null) {
                quiet = q;
            }
        }
        OffsetDateTime oldest = Rows.ts(stats, "oldest_recent");
        OffsetDateTime lastPayment = Rows.ts(c, "last_payment_at");
        String status = Rows.str(c, "status");
        NextBestActionModel.Signals signals = new NextBestActionModel.Signals(Rows.integer(c, "days_past_due"),
                Rows.dec(c, "overdue_amount"), Rows.dec(c, "total_outstanding"), Rows.str(c, "currency"),
                Rows.integer(c, "broken_promises"),
                lastPayment == null ? null : (int) Math.max(0, Duration.between(lastPayment, now).toDays()),
                stats.path("rpc").asInt(), stats.path("no_answer").asInt(), stats.path("refused").asInt(),
                stats.path("recent").asInt(), cap, oldest == null ? null : oldest.plusDays(7),
                Rows.bool(c, "is_disputed"), Rows.bool(c, "is_vulnerable"), "PROMISE_ACTIVE".equals(status),
                "PLAN_ACTIVE".equals(status), quiet[0], quiet[1], now);
        NextBestActionModel.Suggestion s = NextBestActionModel.suggest(signals);

        rows.execute("update next_best_action set status = 'EXPIRED' where collection_case_id = ? and status = 'SUGGESTED'",
                caseId);
        ObjectNode row = rows.object();
        row.put("collection_case_id", caseId.toString());
        row.put("suggested_action", s.action());
        row.put("suggested_at_time", s.at().toString());
        if (s.offer() != null) {
            row.set("suggested_offer", rows.json().valueToTree(s.offer()));
        }
        row.put("propensity_to_pay", s.propensity().toPlainString());
        ArrayNode reasons = row.putArray("reason_codes");
        for (NextBestActionModel.ReasonCode r : s.reasons()) {
            reasons.addObject().put("code", r.code()).put("description", r.description())
                    .put("contribution", r.contribution().toPlainString());
        }
        ArrayNode alts = row.putArray("alternatives");
        for (NextBestActionModel.Alternative a : s.alternatives()) {
            ObjectNode o = alts.addObject().put("action", a.action()).put("score", a.score().toPlainString());
            if (a.excludedBy() != null) {
                o.put("excluded_by", a.excludedBy());
            }
        }
        row.put("explanation", s.explanation());
        row.put("model_version", NextBestActionModel.MODEL_VERSION);
        row.put("status", "SUGGESTED");
        row.put("expires_at", now.plusHours(props.nbaValidityHours()).toString());
        ObjectNode out = rows.insert(TABLE, row);
        events.publish("lending.next_best_action.suggested", TABLE, out);
        return out;
    }

    @Transactional
    public ObjectNode accept(UUID id) {
        ObjectNode n = lockLive(id, "ACCEPTED");
        ObjectNode v = rows.object();
        v.put("status", "ACCEPTED");
        v.put("decided_by", TenantContext.principal());
        ObjectNode out = rows.update(TABLE, id, v);
        UUID caseId = Rows.uuid(n, "collection_case_id");
        cases.lockOpen(caseId);
        ObjectNode cv = rows.object();
        cv.put("next_action_at", Rows.str(n, "suggested_at_time"));
        rows.update(CaseService.TABLE, caseId, cv);
        return out;
    }

    @Transactional
    public ObjectNode reject(UUID id, String reason) {
        lockLive(id, "REJECTED");
        ObjectNode v = rows.object();
        v.put("status", "REJECTED");
        v.put("decided_by", TenantContext.principal());
        v.put("rejection_reason", reason);
        return rows.update(TABLE, id, v);
    }

    private ObjectNode lockLive(UUID id, String to) {
        ObjectNode n = rows.lock(TABLE, id);
        Lifecycles.require(Lifecycles.NBA, "Next best action", Rows.str(n, "status"), to);
        boolean expired = rows.scalar("select expires_at is not null and expires_at < now() from next_best_action where id = ?",
                Boolean.class, id).orElse(false);
        if (expired) {
            throw ApiException.conflict("suggestion_expired", "The suggestion expired; ask for a new next best action");
        }
        return n;
    }
}
