package io.meridian.crm.application;

import static io.meridian.crm.application.Json.text;

import io.meridian.common.error.ApiException;
import io.meridian.common.error.ApiException.FieldError;
import io.meridian.crm.api.generated.model.Complaint;
import io.meridian.crm.api.generated.model.ComplaintRedress;
import io.meridian.crm.domain.BusinessCalendar;
import io.meridian.crm.domain.Lifecycles;
import io.meridian.crm.domain.RegulatoryTimelines;
import io.meridian.crm.infrastructure.Rows;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Formal complaints under regulatory rules: deadlines from a regulatory SLA policy or the jurisdiction's defaults
 * ({@link RegulatoryTimelines}), acknowledgement, investigation, proposed resolution, final response, ombudsman
 * escalation, withdrawal and closure, deadline monitoring and the regulatory complaints return.
 */
@Service
public class ComplaintService {
    static final String T = "complaint";
    static final String SUBJECT = "COMPLAINT";

    private final Rows rows;
    private final CrudEngine crud;
    private final Events events;
    private final SlaService sla;
    private final WorkService work;
    private final TimelineService timeline;
    private final String defaultCountry;

    public ComplaintService(Rows rows, CrudEngine crud, Events events, SlaService sla, WorkService work,
                            TimelineService timeline, @Value("${meridian.crm.default-country:GB}") String defaultCountry) {
        this.rows = rows;
        this.crud = crud;
        this.events = events;
        this.sla = sla;
        this.work = work;
        this.timeline = timeline;
        this.defaultCountry = defaultCountry;
    }

    // ------------------------------------------------------------------ create / update

    @Transactional
    public ObjectNode create(Object body) {
        return create(crud.toColumns(T, body));
    }

    /** Registers a complaint from column values (API create or conversion of a case). */
    public ObjectNode create(ObjectNode v) {
        OffsetDateTime now = rows.now();
        UUID customerId = Json.uuid(v, "customer_id");
        UUID caseId = Json.uuid(v, "service_case_id");
        if (caseId != null) {
            ObjectNode c = rows.find("service_case", caseId).orElseThrow(() ->
                    ApiException.unprocessable("service_case_not_found", "Service case " + caseId + " does not exist"));
            if (!customerId.toString().equals(text(c, "customer_id"))) {
                throw ApiException.unprocessable("customer_mismatch", "The case belongs to another customer");
            }
        }
        if (text(v, "country") == null) {
            v.put("country", defaultCountry);
        }
        OffsetDateTime received = Json.ts(v, "received_at");
        if (received == null) {
            received = now;
        } else if (received.isAfter(now.plusMinutes(5))) {
            throw new ApiException(422, "invalid_complaint", "Complaint is invalid",
                    List.of(new FieldError("received_at", "in_future", "received_at cannot be in the future")));
        }
        v.put("received_at", received.toString());
        if (!v.has("is_vulnerable_customer") && rows.count("""
                select count(*) from customer_note where customer_id = ? and category = 'VULNERABILITY'
                and (expires_at is null or expires_at > now())""", customerId) > 0) {
            v.put("is_vulnerable_customer", true);
        }
        Deadlines d = deadlines(text(v, "country"), text(v, "category"), received);
        d.policyId().ifPresent(p -> v.put("sla_policy_id", p.toString()));
        v.put("acknowledgement_due_at", d.acknowledge().toString());
        v.put("final_response_due_at", d.finalResponse().toString());
        v.put("complaint_number", Numbers.next("CMP", now));
        v.put("status", "RECEIVED");
        ObjectNode row = rows.insert(T, v);
        UUID id = UUID.fromString(text(row, "id"));
        if (text(v, "work_queue_id") != null) {
            enqueue(row);
        }
        events.publish("servicing.complaint.received", T, row, Complaint.class);
        timeline.record(customerId, received, "servicing.complaint.received", SUBJECT, id,
                "Complaint " + text(row, "complaint_number") + " received: " + text(row, "summary"),
                Map.of("category", text(row, "category"), "final_response_due_at", text(row, "final_response_due_at")), 3);
        return row;
    }

    private void enqueue(ObjectNode complaint) {
        ObjectNode item = rows.object();
        item.put("work_queue_id", text(complaint, "work_queue_id"));
        item.put("subject_type", SUBJECT);
        item.put("subject_id", text(complaint, "id"));
        item.put("title", Json.truncate(text(complaint, "complaint_number") + ": " + text(complaint, "summary"), 255));
        item.put("priority", Json.bool(complaint, "is_vulnerable_customer") ? "URGENT" : "HIGH");
        item.put("due_at", text(complaint, "final_response_due_at"));
        work.enqueue(item);
    }

    record Deadlines(OffsetDateTime acknowledge, OffsetDateTime finalResponse, Optional<UUID> policyId) {}

    private Deadlines deadlines(String country, String category, OffsetDateTime received) {
        Optional<ObjectNode> policy = sla.match("COMPLAINT", null, category, null, country);
        if (policy.isPresent()) {
            SlaService.Targets t = sla.targets(policy.get(), received);
            return new Deadlines(t.firstResponseDue(), t.resolutionDue(), Optional.of(UUID.fromString(text(policy.get(), "id"))));
        }
        RegulatoryTimelines.Rule rule = RegulatoryTimelines.rule(country, category);
        BusinessCalendar weekdays = BusinessCalendar.weekdays(ZoneOffset.UTC, java.util.Set.of());
        OffsetDateTime ack = weekdays.addWorkingDays(received, rule.acknowledgeBusinessDays());
        OffsetDateTime fin = rule.finalInBusinessDays() ? weekdays.addWorkingDays(received, rule.finalResponseDays())
                : received.plusDays(rule.finalResponseDays());
        return new Deadlines(ack, fin, Optional.empty());
    }

    @Transactional
    public ObjectNode update(UUID id, String ifMatch, Object body) {
        int expected = Json.parseVersion(ifMatch);
        ObjectNode current = rows.lock(T, id);
        if ("CLOSED".equals(text(current, "status"))) {
            throw ApiException.conflict("complaint_closed", "A closed complaint cannot be changed");
        }
        ObjectNode v = crud.toColumns(T, body);
        for (String f : List.of("customer_id", "received_at", "country")) {
            if (v.has(f) && !text(v, f).equals(text(current, f))) {
                throw ApiException.unprocessable("immutable_field", f + " cannot be changed once the complaint is registered");
            }
        }
        boolean queueAdded = v.has("work_queue_id") && text(current, "work_queue_id") == null;
        if (v.has("work_queue_id") && !queueAdded && !text(v, "work_queue_id").equals(text(current, "work_queue_id"))) {
            throw ApiException.unprocessable("immutable_field", "work_queue_id is set; reassign the handler instead");
        }
        ObjectNode row = rows.update(T, id, v, expected).orElseThrow(() -> Json.versionConflict(expected));
        if (queueAdded) {
            work.activeQueue(Json.uuid(v, "work_queue_id"));
            enqueue(row);
        }
        return row;
    }

    // ------------------------------------------------------------------ lifecycle

    @Transactional
    public ObjectNode acknowledge(UUID id, String message) {
        ObjectNode c = rows.lock(T, id);
        ObjectNode v = rows.object();
        v.put("status", Lifecycles.COMPLAINT.apply("acknowledge", text(c, "status")));
        v.put("acknowledged_at", rows.now().toString());
        if (message != null) {
            v.put("status_reason", Json.truncate(message, 1000));
        }
        ObjectNode row = rows.update(T, id, v);
        events.publish("servicing.complaint.acknowledged", T, row, Complaint.class);
        record(row, "servicing.complaint.acknowledged", "Complaint acknowledged", 1);
        return row;
    }

    @Transactional
    public ObjectNode assign(UUID id, UUID handler) {
        ObjectNode c = rows.lock(T, id);
        Lifecycles.COMPLAINT.apply("assign", text(c, "status"));
        ObjectNode v = rows.object();
        v.put("assigned_to", handler.toString());
        ObjectNode row = rows.update(T, id, v);
        work.followAssignment(SUBJECT, id, handler);
        events.publish("servicing.complaint.assigned", T, row, Complaint.class);
        return row;
    }

    @Transactional
    public ObjectNode investigate(UUID id) {
        ObjectNode c = rows.lock(T, id);
        ObjectNode v = rows.object();
        v.put("status", Lifecycles.COMPLAINT.apply("investigate", text(c, "status")));
        return rows.update(T, id, v);
    }

    @Transactional
    public ObjectNode proposeResolution(UUID id, String outcome, String rootCause, String rootCauseCategory, String summary) {
        ObjectNode c = rows.lock(T, id);
        if ("WITHDRAWN".equals(outcome)) {
            throw ApiException.unprocessable("use_withdraw", "Record a withdrawal with POST /{id}/withdraw");
        }
        ObjectNode v = rows.object();
        v.put("status", Lifecycles.COMPLAINT.apply("propose-resolution", text(c, "status")));
        v.put("outcome", outcome);
        v.put("root_cause", Json.truncate(rootCause, 255));
        if (rootCauseCategory != null) {
            v.put("root_cause_category", Json.truncate(rootCauseCategory, 64));
        }
        if (summary != null) {
            v.put("resolution_summary", summary);
        }
        v.put("resolution_proposed_at", rows.now().toString());
        ObjectNode row = rows.update(T, id, v);
        events.publish("servicing.complaint.resolution_proposed", T, row, Complaint.class);
        return row;
    }

    @Transactional
    public ObjectNode finalResponse(UUID id, UUID documentId) {
        ObjectNode c = rows.lock(T, id);
        String to = Lifecycles.COMPLAINT.apply("final-response", text(c, "status"));
        if ("NOT_UPHELD".equals(text(c, "outcome"))) {
            to = "REJECTED";
        }
        OffsetDateTime now = rows.now();
        ObjectNode v = rows.object();
        v.put("status", to);
        v.put("final_response_document_id", documentId.toString());
        v.put("final_response_at", now.toString());
        ObjectNode row = rows.update(T, id, v);
        work.closeFor(SUBJECT, id, true, "Final response issued");
        events.publish("servicing.complaint.resolved", T, row, Complaint.class);
        boolean late = now.isAfter(Json.ts(c, "final_response_due_at"));
        record(row, "servicing.complaint.resolved", "Final response issued (" + text(c, "outcome") + ")"
                + (late ? " after the regulatory deadline" : ""), 3);
        return row;
    }

    @Transactional
    public ObjectNode escalate(UUID id, String escalatedTo, String reference) {
        ObjectNode c = rows.lock(T, id);
        String status = text(c, "status");
        String to = Lifecycles.COMPLAINT.apply("escalate", status);
        OffsetDateTime now = rows.now();
        OffsetDateTime due = Json.ts(c, "final_response_due_at");
        if (Lifecycles.COMPLAINT_AWAITING_RESPONSE.contains(status) && now.isBefore(due)) {
            throw ApiException.unprocessable("escalation_premature", "The complaint can be referred to "
                    + RegulatoryTimelines.ombudsman(text(c, "country")) + " after the final response or once the deadline "
                    + due.toLocalDate() + " has passed");
        }
        ObjectNode v = rows.object();
        v.put("status", to);
        v.put("escalated_to", Json.truncate(escalatedTo, 120));
        if (reference != null) {
            v.put("ombudsman_reference", Json.truncate(reference, 64));
        }
        v.put("escalated_at", now.toString());
        ObjectNode row = rows.update(T, id, v);
        events.publish("servicing.complaint.escalated", T, row, Complaint.class);
        record(row, "servicing.complaint.escalated", "Complaint referred to " + escalatedTo, 4);
        return row;
    }

    @Transactional
    public ObjectNode withdraw(UUID id, String reason) {
        ObjectNode c = rows.lock(T, id);
        ObjectNode v = rows.object();
        v.put("status", Lifecycles.COMPLAINT.apply("withdraw", text(c, "status")));
        v.put("outcome", "WITHDRAWN");
        v.put("status_reason", Json.truncate(reason, 1000));
        v.put("closed_at", rows.now().toString());
        ObjectNode row = rows.update(T, id, v);
        rows.query("""
                select row_to_json(t)::text from complaint_redress t where t.complaint_id = ?
                and t.status in ('PROPOSED', 'APPROVED', 'FAILED') for update""", id).forEach(r -> {
                    ObjectNode rv = rows.object();
                    rv.put("status", "REJECTED");
                    rv.put("rejection_reason", "Complaint withdrawn");
                    events.publish("servicing.complaint_redress.rejected", "complaint_redress",
                            rows.update("complaint_redress", UUID.fromString(text(r, "id")), rv), ComplaintRedress.class);
                });
        work.closeFor(SUBJECT, id, false, "Complaint withdrawn");
        events.publish("servicing.complaint.withdrawn", T, row, Complaint.class);
        record(row, "servicing.complaint.withdrawn", "Complaint withdrawn by the customer", 2);
        return row;
    }

    @Transactional
    public ObjectNode close(UUID id) {
        ObjectNode c = rows.lock(T, id);
        String to = Lifecycles.COMPLAINT.apply("close", text(c, "status"));
        int outstanding = rows.count("""
                select count(*) from complaint_redress where complaint_id = ? and status in ('PROPOSED', 'APPROVED', 'FAILED')""", id);
        if (outstanding > 0) {
            throw ApiException.unprocessable("redress_outstanding",
                    outstanding + " redress action(s) are not yet paid or rejected");
        }
        ObjectNode v = rows.object();
        v.put("status", to);
        v.put("closed_at", rows.now().toString());
        ObjectNode row = rows.update(T, id, v);
        work.closeFor(SUBJECT, id, true, "Complaint closed");
        events.publish("servicing.complaint.closed", T, row, Complaint.class);
        record(row, "servicing.complaint.closed", "Complaint closed", 1);
        return row;
    }

    private void record(ObjectNode row, String type, String title, int importance) {
        timeline.record(Json.uuid(row, "customer_id"), rows.now(), type, SUBJECT, Json.uuid(row, "id"),
                text(row, "complaint_number") + ": " + title, Map.of("status", text(row, "status")), importance);
    }

    // ------------------------------------------------------------------ monitoring

    /** Raises deadline-at-risk and deadline-breached alerts once per complaint (scheduler endpoint). */
    @Transactional
    public ObjectNode deadlineCheck() {
        OffsetDateTime now = rows.now();
        List<ObjectNode> open = rows.query("""
                select row_to_json(t)::text from complaint t
                where t.status in ('RECEIVED', 'ACKNOWLEDGED', 'INVESTIGATING', 'RESOLUTION_PROPOSED')
                  and t.final_response_at is null and (t.deadline_breached_at is null)
                order by t.final_response_due_at limit 1000 for update skip locked""");
        ArrayNode atRisk = tools.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        ArrayNode breached = tools.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        for (ObjectNode c : open) {
            UUID id = UUID.fromString(text(c, "id"));
            OffsetDateTime received = Json.ts(c, "received_at");
            OffsetDateTime due = Json.ts(c, "final_response_due_at");
            if (now.isAfter(due)) {
                ObjectNode v = rows.object();
                v.put("deadline_breached_at", now.toString());
                ObjectNode row = rows.update(T, id, v);
                events.publish("servicing.complaint.deadline_breached", T, row, Complaint.class);
                record(row, "servicing.complaint.deadline_breached", "Regulatory deadline missed", 5);
                breached.add(id.toString());
                continue;
            }
            if (text(c, "deadline_at_risk_at") != null) {
                continue;
            }
            int riskPercent = sla.policy(Json.uuid(c, "sla_policy_id")).map(p -> p.path("at_risk_percent").asInt(80)).orElse(80);
            long total = Duration.between(received, due).toSeconds();
            long elapsed = Duration.between(received, now).toSeconds();
            if (total > 0 && elapsed * 100 >= (long) riskPercent * total) {
                ObjectNode v = rows.object();
                v.put("deadline_at_risk_at", now.toString());
                ObjectNode row = rows.update(T, id, v);
                events.publish("servicing.complaint.deadline_at_risk", T, row, Complaint.class);
                atRisk.add(id.toString());
            }
        }
        ObjectNode out = rows.object();
        out.put("checked_at", now.toString());
        out.put("checked", open.size());
        out.set("at_risk", atRisk);
        out.set("breached", breached);
        out.put("acknowledgement_overdue", rows.count("""
                select count(*) from complaint where status = 'RECEIVED' and acknowledgement_due_at < ?""", now));
        return out;
    }

    /** Statistics for the regulatory complaints return over complaints received in [from, to]. */
    @Transactional(readOnly = true)
    public ObjectNode report(LocalDate from, LocalDate to, String country) {
        if (from == null || to == null || to.isBefore(from)) {
            throw ApiException.badRequest("invalid_period", "from and to are required and to must not be before from");
        }
        OffsetDateTime start = from.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime end = to.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        String scope = " from complaint t where t.reportable and t.received_at >= ? and t.received_at < ?"
                + " and (cast(? as text) is null or t.country = cast(? as text))";
        Object[] args = {start, end, country, country};
        ObjectNode r = rows.object();
        r.put("from", from.toString());
        r.put("to", to.toString());
        if (country == null) {
            r.putNull("country");
        } else {
            r.put("country", country);
        }
        r.put("received", rows.count("select count(*)" + scope, args));
        r.put("vulnerable_customers", rows.count("select count(*)" + scope + " and t.is_vulnerable_customer", args));
        r.set("by_category", grouped("t.category", scope, args));
        r.set("by_channel", grouped("t.channel", scope, args));
        r.set("by_status", grouped("t.status", scope, args));
        r.set("by_outcome", grouped("coalesce(t.outcome, 'OPEN')", scope, args));
        r.set("by_root_cause_category", grouped("coalesce(t.root_cause_category, 'UNCLASSIFIED')", scope, args));
        int responded = rows.count("select count(*)" + scope + " and t.final_response_at is not null", args);
        int inTime = rows.count("select count(*)" + scope + " and t.final_response_at <= t.final_response_due_at", args);
        int acked = rows.count("select count(*)" + scope + " and t.acknowledged_at is not null", args);
        int ackInTime = rows.count("select count(*)" + scope + " and t.acknowledged_at <= t.acknowledgement_due_at", args);
        int decided = rows.count("select count(*)" + scope + " and t.outcome in ('UPHELD', 'PARTIALLY_UPHELD', 'NOT_UPHELD')", args);
        int upheld = rows.count("select count(*)" + scope + " and t.outcome in ('UPHELD', 'PARTIALLY_UPHELD')", args);
        r.put("final_responses", responded);
        r.put("final_responses_within_deadline_percent", percent(inTime, responded));
        r.put("acknowledged_within_deadline_percent", percent(ackInTime, acked));
        r.put("upheld_rate_percent", percent(upheld, decided));
        Double avgDays = rows.jdbc().sql("select avg(extract(epoch from (t.final_response_at - t.received_at)) / 86400)"
                + scope + " and t.final_response_at is not null").params(args).query(Double.class).optional().orElse(null);
        r.put("average_days_to_final_response", avgDays == null ? null
                : BigDecimal.valueOf(avgDays).setScale(1, RoundingMode.HALF_EVEN));
        r.put("open_past_deadline", rows.count("select count(*)" + scope
                + " and t.final_response_at is null and t.status not in ('CLOSED') and t.final_response_due_at < now()", args));
        r.put("escalated_to_ombudsman", rows.count("select count(*)" + scope + " and t.escalated_at is not null", args));
        ObjectNode redress = r.putObject("redress_paid");
        rows.jdbc().sql("select x.currency, sum(x.amount)::text from complaint_redress x where x.status = 'PAID'"
                        + " and x.amount is not null and x.currency is not null and x.complaint_id in (select t.id" + scope + ") group by x.currency order by 1")
                .params(args).query((rs, i) -> redress.put(rs.getString(1),
                        new BigDecimal(rs.getString(2)).setScale(2, RoundingMode.HALF_EVEN).toPlainString())).list();
        return r;
    }

    private ObjectNode grouped(String expr, String scope, Object[] args) {
        ObjectNode o = rows.object();
        Map<String, Integer> m = new LinkedHashMap<>();
        rows.jdbc().sql("select " + expr + ", count(*)" + scope + " group by 1 order by 2 desc, 1").params(args)
                .query((rs, i) -> m.put(rs.getString(1), rs.getInt(2))).list();
        m.forEach(o::put);
        return o;
    }

    private static BigDecimal percent(int part, int whole) {
        return whole == 0 ? null : BigDecimal.valueOf(part * 100L).divide(BigDecimal.valueOf(whole), 1, RoundingMode.HALF_EVEN);
    }
}
