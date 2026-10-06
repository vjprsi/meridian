package io.meridian.carddispute.application;

import static io.meridian.carddispute.infrastructure.Rows.bool;
import static io.meridian.carddispute.infrastructure.Rows.date;
import static io.meridian.carddispute.infrastructure.Rows.dec;
import static io.meridian.carddispute.infrastructure.Rows.str;
import static io.meridian.carddispute.infrastructure.Rows.uuid;

import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.carddispute.domain.DisputeDeadlines;
import io.meridian.carddispute.domain.DisputeStateMachine;
import io.meridian.carddispute.domain.DisputeStateMachine.Action;
import io.meridian.carddispute.domain.DisputeStateMachine.Outcome;
import io.meridian.carddispute.domain.DisputeStateMachine.Stage;
import io.meridian.carddispute.domain.DisputeStateMachine.State;
import io.meridian.carddispute.domain.DisputeStateMachine.Status;
import io.meridian.carddispute.domain.DisputeSummary;
import io.meridian.carddispute.domain.ReasonCodes;
import io.meridian.carddispute.domain.Settlement;
import io.meridian.carddispute.infrastructure.Downstream;
import io.meridian.carddispute.infrastructure.Downstream.Leg;
import io.meridian.carddispute.infrastructure.Rows;
import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Card disputes: intake, provisional credit, scheme chargeback stages with deadlines, outcome postings.
 *
 * <p>Money: provisional credit is funded from the dispute suspense account (DEBIT suspense / CREDIT cardholder).
 * The outcome clears suspense in one entry ({@link Settlement}): recoveries are debited to the scheme receivable,
 * losses the issuer absorbs to loss expense, and the cardholder is credited or debited for the difference between
 * what they keep and the provisional credit already given. A plain clawback is posted as a ledger reversal of the
 * provisional credit entry.
 */
@Service
public class DisputeService {
    static final String T = "card_dispute";
    static final String EVIDENCE = "card_dispute_evidence";
    static final String TIMELINE = "card_dispute_event";
    static final Set<String> UPDATABLE = Set.of("customer_description", "scheme_case_id", "reason_code",
            "transaction_date", "custom_field_1", "custom_field_2", "custom_field_3", "custom_field_4", "custom_field_5");

    /** Resource type / action of issuer write-off approvals in workflow-service (G11). */
    public static final String WRITE_OFF_RESOURCE = "card_dispute";
    static final String WRITE_OFF_ACTION = "write_off";
    static final String CLOSE = "close";
    static final String ACCEPT_REPRESENTMENT = "accept_representment";

    private final Rows rows;
    private final Downstream downstream;
    private final Events events;
    private final TransactionTemplate tx;
    private final Approvals approvals;

    public DisputeService(Rows rows, Downstream downstream, Events events, TransactionTemplate tx, Approvals approvals) {
        this.rows = rows;
        this.downstream = downstream;
        this.events = events;
        this.tx = tx;
        this.approvals = approvals;
    }

    // ------------------------------------------------------------------ intake

    @Transactional
    public ObjectNode open(ObjectNode v) {
        UUID cardId = uuid(v, "card_id");
        BigDecimal amount = dec(v, "disputed_amount");
        if (amount == null || amount.signum() <= 0) {
            throw ApiException.unprocessable("invalid_amount", "disputed_amount must be positive");
        }
        String scheme = str(v, "scheme");
        String category = str(v, "category");
        String code = str(v, "reason_code");
        if (code != null && !ReasonCodes.fits(scheme, code)) {
            throw ApiException.unprocessable("invalid_reason_code", code + " is not a " + scheme + " reason code");
        }
        LocalDate today = rows.today();
        LocalDate txDate = date(v, "transaction_date");
        if (txDate != null && txDate.isAfter(today)) {
            throw ApiException.unprocessable("invalid_transaction_date", "transaction_date is in the future");
        }
        for (String ref : List.of("clearing_record_id", "card_authorization_id")) {
            UUID r = uuid(v, ref);
            if (r != null && rows.scalar("select count(*) from card_dispute where " + ref + " = ? and stage <> 'CLOSED'",
                    Long.class, r) > 0) {
                throw ApiException.conflict("duplicate_dispute", "an open dispute already exists for this " + ref);
            }
        }
        JsonNode card = downstream.card(cardId);
        v.put("customer_id", card.get("customer_id").asString());
        v.put("ledger_account_id", card.get("ledger_account_id").asString());
        v.put("stage", Stage.INQUIRY.name());
        v.put("status", Status.OPEN.name());
        v.put("stage_deadline", DisputeDeadlines.chargebackFiling(scheme, txDate, today).toString());
        v.put("regulatory_deadline", DisputeDeadlines.regulatory(today).toString());
        v.put("suggested_reason_code", ReasonCodes.suggest(category, scheme));
        ObjectNode d = rows.insert(T, v);
        timeline(d, "CREATED", null, Stage.INQUIRY, amount, null,
                "Provisional credit decision due " + DisputeDeadlines.provisionalCreditDecision(today));
        events.publish("cards.card_dispute.opened", d);
        return d;
    }

    @Transactional
    public ObjectNode update(UUID id, String ifMatch, ObjectNode values) {
        int version = version(ifMatch);
        ObjectNode d = rows.lock(T, id);
        for (String f : values.propertyNames()) {
            if (!UPDATABLE.contains(f)) {
                throw ApiException.unprocessable("field_not_updatable",
                        f + " cannot be changed with PATCH; use the dispute actions");
            }
        }
        Stage stage = stage(d);
        boolean submitted = DisputeStateMachine.submitted(stage);
        if ((values.has("reason_code") || values.has("transaction_date")) && submitted) {
            throw ApiException.conflict("dispute_submitted",
                    "reason_code and transaction_date cannot change after the chargeback was submitted");
        }
        if (values.has("reason_code") && !ReasonCodes.fits(str(d, "scheme"), str(values, "reason_code"))) {
            throw ApiException.unprocessable("invalid_reason_code", "not a " + str(d, "scheme") + " reason code");
        }
        if (values.has("transaction_date")) {
            LocalDate txDate = date(values, "transaction_date");
            LocalDate today = rows.today();
            if (txDate.isAfter(today)) {
                throw ApiException.unprocessable("invalid_transaction_date", "transaction_date is in the future");
            }
            if (stage == Stage.INQUIRY) {
                values.put("stage_deadline", DisputeDeadlines.chargebackFiling(str(d, "scheme"), txDate,
                        date(d, "created_at")).toString());
            }
        }
        if (values.isEmpty()) {
            return d;
        }
        if (d.get("row_version").asInt() != version) {
            throw ApiException.conflict("version_conflict",
                    "If-Match " + version + " does not match the current version; reload and retry");
        }
        return rows.update(T, id, values);
    }

    // ------------------------------------------------------------------ provisional credit

    @Transactional
    public ObjectNode provisionalCredit(UUID id, BigDecimal amount) {
        ObjectNode d = rows.lock(T, id);
        requireOpen(d, "give provisional credit on");
        if (dec(d, "provisional_credit_amount").signum() > 0) {
            throw ApiException.conflict("already_credited", "provisional credit was already given on this dispute");
        }
        if (amount == null || amount.signum() <= 0) {
            throw ApiException.unprocessable("invalid_amount", "amount must be positive");
        }
        if (amount.compareTo(dec(d, "disputed_amount")) > 0) {
            throw ApiException.unprocessable("amount_exceeds_dispute", "provisional credit exceeds the disputed amount");
        }
        UUID entry = downstream.post("dispute-pc:" + id, "Provisional credit for dispute " + id, str(d, "currency"), id,
                List.of(new Leg(downstream.suspense(), "DEBIT", amount, "Dispute suspense"),
                        new Leg(cardholder(d), "CREDIT", amount, "Provisional credit")));
        ObjectNode v = rows.object();
        v.put("provisional_credit_amount", amount.toPlainString());
        v.put("provisional_credit_entry_id", entry.toString());
        ObjectNode out = rows.update(T, id, v);
        timeline(out, "PROVISIONAL_CREDIT", null, null, amount, entry, null);
        events.publish("cards.card_dispute.provisional_credit_granted", out);
        return out;
    }

    @Transactional
    public ObjectNode reverseProvisionalCredit(UUID id, String reason) {
        ObjectNode d = rows.lock(T, id);
        if (dec(d, "provisional_credit_amount").signum() == 0 || str(d, "provisional_credit_entry_id") == null) {
            throw ApiException.conflict("no_provisional_credit", "no provisional credit was given on this dispute");
        }
        if (bool(d, "provisional_credit_reversed")) {
            throw ApiException.conflict("already_reversed", "the provisional credit was already reversed");
        }
        if (stage(d) == Stage.CLOSED) {
            throw ApiException.conflict("invalid_transition", "the dispute is closed; its outcome already settled the credit");
        }
        UUID entry = downstream.reverse(uuid(d, "provisional_credit_entry_id"), reason, "dispute-pc-rev:" + id);
        ObjectNode v = rows.object();
        v.put("provisional_credit_reversed", true);
        ObjectNode out = rows.update(T, id, v);
        timeline(out, "PROVISIONAL_CREDIT_REVERSED", null, null, dec(d, "provisional_credit_amount"), entry, reason);
        events.publish("cards.card_dispute.provisional_credit_reversed", out);
        return out;
    }

    // ------------------------------------------------------------------ scheme stages

    @Transactional
    public ObjectNode requestRetrieval(UUID id, String note) {
        ObjectNode d = rows.lock(T, id);
        ObjectNode v = rows.object();
        v.put("stage_deadline", DisputeDeadlines.retrieval(rows.today()).toString());
        return transition(d, Action.REQUEST_RETRIEVAL, v, "RETRIEVAL_REQUESTED", null, null, note);
    }

    @Transactional
    public ObjectNode submitChargeback(UUID id, String reasonCode, BigDecimal amount) {
        ObjectNode d = rows.lock(T, id);
        DisputeStateMachine.next(state(d), Action.SUBMIT_CHARGEBACK);
        String scheme = str(d, "scheme");
        if (!ReasonCodes.fits(scheme, reasonCode)) {
            throw ApiException.unprocessable("invalid_reason_code", reasonCode + " is not a " + scheme + " reason code");
        }
        if (amount == null || amount.signum() <= 0 || amount.compareTo(dec(d, "disputed_amount")) > 0) {
            throw ApiException.unprocessable("invalid_amount", "chargeback amount must be positive and at most the disputed amount");
        }
        LocalDate today = rows.today();
        LocalDate filing = DisputeDeadlines.chargebackFiling(scheme, date(d, "transaction_date"), date(d, "created_at"));
        if (DisputeDeadlines.passed(filing, today)) {
            throw ApiException.unprocessable("deadline_passed", "the chargeback filing deadline " + filing + " has passed");
        }
        String now = rows.now();
        rows.execute("""
                update card_dispute_evidence set submitted_to_scheme = true, submitted_at = now(), updated_by = ?
                where card_dispute_id = ? and not submitted_to_scheme""", TenantContext.principal(), id);
        ObjectNode v = rows.object();
        v.put("reason_code", reasonCode);
        v.put("chargeback_amount", amount.toPlainString());
        v.put("submitted_at", now);
        v.put("stage_deadline", DisputeDeadlines.representment(scheme, today).toString());
        return transition(d, Action.SUBMIT_CHARGEBACK, v, "CHARGEBACK_SENT", amount, null,
                reasonCode + " " + ReasonCodes.describe(reasonCode));
    }

    @Transactional
    public ObjectNode recordRepresentment(UUID id, BigDecimal amount, String note, String schemeCaseId) {
        ObjectNode d = rows.lock(T, id);
        DisputeStateMachine.next(state(d), Action.RECORD_REPRESENTMENT);
        if (amount == null || amount.signum() <= 0 || amount.compareTo(dec(d, "chargeback_amount")) > 0) {
            throw ApiException.unprocessable("invalid_amount", "representment amount must be positive and at most the chargeback");
        }
        ObjectNode v = rows.object();
        v.put("representment_amount", amount.toPlainString());
        if (schemeCaseId != null) {
            v.put("scheme_case_id", schemeCaseId);
        }
        v.put("stage_deadline", DisputeDeadlines.preArbitration(rows.today()).toString());
        return transition(d, Action.RECORD_REPRESENTMENT, v, "REPRESENTMENT_RECEIVED", amount, null, note);
    }

    @Transactional
    public ObjectNode escalate(UUID id, Stage target, String note) {
        Action action = DisputeStateMachine.escalation(target);
        ObjectNode d = rows.lock(T, id);
        ObjectNode v = rows.object();
        LocalDate today = rows.today();
        v.put("stage_deadline", (target == Stage.ARBITRATION ? DisputeDeadlines.arbitration(today)
                : DisputeDeadlines.preArbitration(today)).toString());
        return transition(d, action, v, target == Stage.ARBITRATION ? "ARBITRATION_FILED" : "PRE_ARBITRATION_FILED",
                null, null, note);
    }

    @Transactional
    public ObjectNode acceptRepresentment(UUID id, String note, Boolean customerLiable) {
        return acceptRepresentment(id, note, customerLiable, Origin.USER);
    }

    /** {@link #acceptRepresentment} for a write-off approved in workflow-service (the approver is the principal). */
    @Transactional
    public ObjectNode acceptRepresentmentApproved(UUID id, String note, Boolean customerLiable) {
        return acceptRepresentment(id, note, customerLiable, Origin.WORKFLOW);
    }

    private ObjectNode acceptRepresentment(UUID id, String note, Boolean customerLiable, Origin origin) {
        ObjectNode d = rows.lock(T, id);
        DisputeStateMachine.next(state(d), Action.ACCEPT_REPRESENTMENT);
        timeline(d, "REPRESENTMENT_ACCEPTED", stage(d), stage(d), dec(d, "representment_amount"), null, note);
        return finish(d, Action.ACCEPT_REPRESENTMENT, Outcome.LOST, BigDecimal.ZERO,
                customerLiable == null || customerLiable, note, origin);
    }

    @Transactional
    public ObjectNode close(UUID id, Outcome outcome, BigDecimal recovered, Boolean customerLiable, String note) {
        return close(id, outcome, recovered, customerLiable, note, Origin.USER);
    }

    /** {@link #close} for a write-off approved in workflow-service (the approver is the principal). */
    @Transactional
    public ObjectNode closeApproved(UUID id, Outcome outcome, BigDecimal recovered, Boolean customerLiable, String note) {
        return close(id, outcome, recovered, customerLiable, note, Origin.WORKFLOW);
    }

    private ObjectNode close(UUID id, Outcome outcome, BigDecimal recovered, Boolean customerLiable, String note,
                             Origin origin) {
        Action action = DisputeStateMachine.closeAction(outcome);
        ObjectNode d = rows.lock(T, id);
        DisputeStateMachine.next(state(d), action);
        BigDecimal chargeback = dec(d, "chargeback_amount");
        BigDecimal r = switch (outcome) {
            case WON -> {
                BigDecimal x = recovered == null ? chargeback : recovered;
                if (x.signum() <= 0 || x.compareTo(chargeback) > 0) {
                    throw ApiException.unprocessable("invalid_recovered_amount",
                            "recovered_amount must be positive and at most the chargeback amount");
                }
                yield x;
            }
            case SPLIT -> {
                if (recovered == null || recovered.signum() <= 0 || recovered.compareTo(chargeback) >= 0) {
                    throw ApiException.unprocessable("invalid_recovered_amount",
                            "SPLIT needs a recovered_amount between 0 and the chargeback amount");
                }
                yield recovered;
            }
            default -> {
                if (recovered != null && recovered.signum() != 0) {
                    throw ApiException.unprocessable("invalid_recovered_amount", outcome + " recovers nothing");
                }
                yield BigDecimal.ZERO;
            }
        };
        boolean liable = outcome == Outcome.WITHDRAWN || customerLiable == null || customerLiable;
        return finish(d, action, outcome, r, liable, note, origin);
    }

    @Transactional
    public ObjectNode assign(UUID id, String assignee) {
        ObjectNode d = rows.lock(T, id);
        requireOpen(d, "assign");
        if (assignee == null || assignee.isBlank()) {
            throw ApiException.unprocessable("assignee_required", "assigned_to is required");
        }
        ObjectNode v = rows.object();
        v.put("assigned_to", assignee);
        ObjectNode out = rows.update(T, id, v);
        timeline(out, "ASSIGNED", null, null, null, null, "Assigned to " + assignee);
        return out;
    }

    @Transactional
    public ObjectNode aiSummary(UUID id) {
        ObjectNode d = rows.lock(T, id);
        int evidence = rows.scalar("select count(*) from card_dispute_evidence where card_dispute_id = ?", Long.class, id)
                .intValue();
        int submitted = rows.scalar("""
                select count(*) from card_dispute_evidence where card_dispute_id = ? and submitted_to_scheme""",
                Long.class, id).intValue();
        DisputeSummary.Facts f = new DisputeSummary.Facts(str(d, "category"), str(d, "scheme"), str(d, "reason_code"),
                str(d, "stage"), str(d, "status"), dec(d, "disputed_amount"), str(d, "currency"),
                dec(d, "provisional_credit_amount"), bool(d, "provisional_credit_reversed"), date(d, "transaction_date"),
                date(d, "stage_deadline"), date(d, "regulatory_deadline"), evidence, submitted,
                str(d, "customer_description"));
        ObjectNode v = rows.object();
        v.put("ai_summary", DisputeSummary.render(f));
        v.put("suggested_reason_code", DisputeSummary.suggestedCode(f));
        ObjectNode out = rows.update(T, id, v);
        timeline(out, "AI_SUMMARY", null, null, null, null, "Suggested reason code " + DisputeSummary.suggestedCode(f));
        return out;
    }

    // ------------------------------------------------------------------ evidence

    @Transactional
    public ObjectNode addEvidence(ObjectNode v) {
        UUID disputeId = uuid(v, "card_dispute_id");
        ObjectNode d = rows.queryOne("select row_to_json(t)::text from card_dispute t where id = ? for update", disputeId)
                .orElseThrow(() -> ApiException.unprocessable("dispute_not_found", "dispute " + disputeId + " was not found"));
        if (stage(d) == Stage.CLOSED) {
            throw ApiException.conflict("dispute_closed", "evidence cannot be added to a closed dispute");
        }
        v.remove("submitted_to_scheme");
        v.remove("submitted_at");
        ObjectNode e = rows.insert(EVIDENCE, v);
        timeline(d, "EVIDENCE_ADDED", null, null, null, null, str(e, "evidence_type") + " " + str(e, "id"));
        return e;
    }

    @Transactional
    public void deleteEvidence(UUID id) {
        ObjectNode e = rows.lock(EVIDENCE, id);
        if (bool(e, "submitted_to_scheme")) {
            throw ApiException.conflict("evidence_submitted", "evidence already submitted to the scheme cannot be deleted");
        }
        rows.execute("delete from card_dispute_evidence where id = ?", id);
    }

    // ------------------------------------------------------------------ deadline scan

    /**
     * Notifies deadlines within 3 days (once per dispute and stage) and expires disputes whose stage deadline passed
     * while the issuer still had to act (OPEN / AWAITING_*). An expiry is the issuer's miss: the cardholder keeps
     * any provisional credit and the issuer writes it off. Each dispute is processed in its own transaction.
     */
    public ObjectNode deadlineScan(LocalDate asOf) {
        LocalDate day = asOf != null ? asOf : tx.execute(s -> rows.today());
        List<UUID> due = tx.execute(s -> rows.query("""
                select json_build_object('id', id)::text from card_dispute
                where stage <> 'CLOSED' and stage_deadline is not null and stage_deadline <= ?::date
                order by stage_deadline, id""", day.plusDays(DisputeDeadlines.APPROACHING_DAYS).toString())
                .stream().map(n -> uuid(n, "id")).toList());
        int approaching = 0;
        int expired = 0;
        List<String> expiredIds = new ArrayList<>();
        for (UUID id : due) {
            String r = tx.execute(s -> scanOne(id, day));
            if ("approaching".equals(r)) {
                approaching++;
            } else if ("expired".equals(r)) {
                expired++;
                expiredIds.add(id.toString());
            }
        }
        ObjectNode out = rows.object();
        out.put("as_of", day.toString());
        out.put("scanned", due.size());
        out.put("approaching", approaching);
        out.put("expired", expired);
        expiredIds.forEach(out.putArray("expired_ids")::add);
        return out;
    }

    private String scanOne(UUID id, LocalDate day) {
        ObjectNode d = rows.lock(T, id);
        if (stage(d) == Stage.CLOSED) {
            return "none";
        }
        LocalDate deadline = date(d, "stage_deadline");
        Status status = Status.valueOf(str(d, "status"));
        boolean issuerToAct = status == Status.OPEN || status == Status.AWAITING_CUSTOMER
                || status == Status.AWAITING_MERCHANT;
        if (DisputeDeadlines.passed(deadline, day) && issuerToAct) {
            finish(d, Action.EXPIRE, Outcome.EXPIRED, BigDecimal.ZERO, false,
                    "Expired: stage deadline " + deadline + " passed", Origin.SYSTEM);
            return "expired";
        }
        if (DisputeDeadlines.approaching(deadline, day)) {
            long seen = rows.scalar("""
                    select count(*) from card_dispute_event
                    where card_dispute_id = ? and event_type = 'DEADLINE_APPROACHING' and to_stage = ?""",
                    Long.class, id, str(d, "stage"));
            if (seen == 0) {
                timeline(d, "DEADLINE_APPROACHING", null, stage(d), null, null, "Stage deadline " + deadline);
                events.publish("cards.card_dispute.deadline_approaching", d);
                return "approaching";
            }
        }
        return "none";
    }

    // ------------------------------------------------------------------ internals

    /** Who closes: a user call, the deadline scan (no four-eyes), or a write-off approved in workflow-service. */
    private enum Origin { USER, SYSTEM, WORKFLOW }

    /** Closes the dispute with an outcome and posts the money (see {@link Settlement}). */
    private ObjectNode finish(ObjectNode d, Action action, Outcome outcome, BigDecimal recovered, boolean customerLiable,
                              String note, Origin origin) {
        UUID id = uuid(d, "id");
        State to = DisputeStateMachine.next(state(d), action);
        BigDecimal provisional = bool(d, "provisional_credit_reversed") ? BigDecimal.ZERO : dec(d, "provisional_credit_amount");
        BigDecimal customerTotal = customerLiable ? recovered : recovered.max(provisional);
        Settlement s = new Settlement(provisional, recovered, customerTotal);
        if (origin != Origin.SYSTEM && s.writeOff().signum() > 0
                && TenantContext.principal().equals(str(d, "created_by"))) {
            // the creator's own close is submitted for approval; a workflow approval by the creator is just refused
            UUID request = origin == Origin.USER ? requestWriteOff(d, action, outcome, s, customerLiable, note) : null;
            throw ApiException.unprocessable("four_eyes_violation",
                    "an issuer write-off must be approved by someone other than the dispute creator"
                            + (request == null ? "" : "; sent for approval as workflow request " + request));
        }
        ObjectNode v = rows.object();
        UUID entry = null;
        if (s.isPureReversal()) {
            entry = downstream.reverse(uuid(d, "provisional_credit_entry_id"), "Dispute " + outcome + ": " + (note == null ? "" : note),
                    "dispute-outcome:" + id);
            v.put("provisional_credit_reversed", true);
        } else if (!s.isEmpty()) {
            List<Leg> legs = new ArrayList<>();
            if (s.recovered().signum() > 0) {
                legs.add(new Leg(downstream.schemeReceivable(), "DEBIT", s.recovered(), "Chargeback recovery"));
            }
            if (s.writeOff().signum() > 0) {
                legs.add(new Leg(downstream.lossExpense(), "DEBIT", s.writeOff(), "Dispute loss written off"));
            }
            if (s.provisional().signum() > 0) {
                legs.add(new Leg(downstream.suspense(), "CREDIT", s.provisional(), "Clear dispute suspense"));
            }
            BigDecimal delta = s.cardholderDelta();
            if (delta.signum() != 0) {
                legs.add(new Leg(cardholder(d), delta.signum() > 0 ? "CREDIT" : "DEBIT", delta.abs(),
                        delta.signum() > 0 ? "Dispute credit" : "Provisional credit clawback"));
            }
            entry = downstream.post("dispute-outcome:" + id, "Dispute " + outcome + " " + id, str(d, "currency"), id, legs);
        }
        v.put("recovered_amount", s.recovered().toPlainString());
        v.put("written_off_amount", s.writeOff().toPlainString());
        v.put("outcome", outcome.name());
        v.put("closed_at", rows.now());
        v.putNull("stage_deadline");
        if (entry != null) {
            v.put("outcome_entry_id", entry.toString());
        }
        ObjectNode out = apply(d, to, v);
        timeline(out, outcome.name(), stage(d), Stage.CLOSED, s.recovered(), entry, note);
        events.publish("cards.card_dispute.stage_changed", out);
        events.publish("cards.card_dispute.closed", out);
        // any write-off proposal still waiting in the workflow inbox is moot once the dispute is closed
        approvals.cancelOpen(WRITE_OFF_RESOURCE, id, null, "dispute closed");
        return out;
    }

    /**
     * Central maker-checker (G11): the creator's close that would write off a loss is submitted to workflow-service
     * (the direct call is still refused); the checker decides in the inbox and {@link WriteOffApprovalHandler}
     * replays the same close with the approver as principal. row_version fingerprints the dispute as proposed.
     * Returns the approval request id, or null in local mode.
     */
    private UUID requestWriteOff(ObjectNode d, Action action, Outcome outcome, Settlement s, boolean customerLiable,
                                 String note) {
        if (!approvals.centralised()) {
            return null;
        }
        UUID id = uuid(d, "id");
        int rowVersion = d.get("row_version").asInt();
        String closeAction = action == Action.ACCEPT_REPRESENTMENT ? ACCEPT_REPRESENTMENT : CLOSE;
        String currency = str(d, "currency");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("row_version", rowVersion);
        payload.put("close_action", closeAction);
        payload.put("outcome", outcome.name());
        payload.put("recovered_amount", s.recovered().toPlainString());
        payload.put("customer_liable", customerLiable);
        payload.put("write_off_amount", s.writeOff().toPlainString());
        payload.put("provisional_credit_amount", s.provisional().toPlainString());
        payload.put("disputed_amount", str(d, "disputed_amount"));
        payload.put("chargeback_amount", str(d, "chargeback_amount"));
        payload.put("currency", currency);
        payload.put("scheme", str(d, "scheme"));
        payload.put("category", str(d, "category"));
        payload.put("reason_code", str(d, "reason_code"));
        payload.put("stage", str(d, "stage"));
        payload.put("note", note);
        String key = "write-off:" + id + ":" + rowVersion + ":" + closeAction + ":" + outcome + ":"
                + s.recovered().toPlainString() + ":" + customerLiable + ":" + Objects.hashCode(note);
        ApprovalSpec spec = ApprovalSpec.of(WRITE_OFF_RESOURCE, id, WRITE_OFF_ACTION,
                        "Dispute " + id + " issuer write-off " + s.writeOff().toPlainString() + " " + currency, payload)
                .withAmount(s.writeOff(), currency)
                .withSummary(outcome + " " + str(d, "scheme") + " " + str(d, "category") + " dispute: recovered "
                        + s.recovered().toPlainString() + ", issuer loss " + s.writeOff().toPlainString());
        return Approvals.idOf(approvals.request(spec, key));
    }

    /** True while the dispute is open and unchanged (row_version) since the write-off was proposed. */
    @Transactional(readOnly = true)
    public boolean awaitsWriteOff(UUID id, Integer rowVersion) {
        return rows.find(T, id)
                .filter(d -> stage(d) != Stage.CLOSED)
                .filter(d -> rowVersion != null && rowVersion == d.get("row_version").asInt())
                .isPresent();
    }

    /** The checker declined the creator's write-off: the dispute stays open; noted once on its timeline. */
    @Transactional
    public void writeOffRejected(UUID id, UUID approvalRequestId) {
        ObjectNode d = rows.lock(T, id);
        String note = "Issuer write-off rejected in workflow request " + approvalRequestId;
        if (rows.scalar("select count(*) from card_dispute_event where card_dispute_id = ? and event_type = 'NOTE' "
                + "and note = ?", Long.class, id, note) == 0) {
            timeline(d, "NOTE", null, null, null, null, note);
        }
    }

    private ObjectNode transition(ObjectNode d, Action action, ObjectNode extra, String eventType, BigDecimal amount,
                                  UUID journal, String note) {
        State to = DisputeStateMachine.next(state(d), action);
        ObjectNode out = apply(d, to, extra);
        timeline(out, eventType, stage(d), to.stage(), amount, journal, note);
        if (to.stage() != stage(d)) {
            events.publish("cards.card_dispute.stage_changed", out);
        }
        return out;
    }

    private ObjectNode apply(ObjectNode d, State to, ObjectNode extra) {
        ObjectNode v = extra == null ? rows.object() : extra;
        v.put("stage", to.stage().name());
        v.put("status", to.status().name());
        return rows.update(T, uuid(d, "id"), v);
    }

    private void timeline(JsonNode d, String type, Stage from, Stage to, BigDecimal amount, UUID journal, String note) {
        ObjectNode e = rows.object();
        e.put("id", Ids.newId().toString());
        e.put("card_dispute_id", d.get("id").asString());
        e.put("event_type", type);
        if (from != null) {
            e.put("from_stage", from.name());
        }
        if (to != null) {
            e.put("to_stage", to.name());
        }
        if (amount != null) {
            e.put("amount", amount.toPlainString());
        }
        if (journal != null) {
            e.put("journal_entry_id", journal.toString());
        }
        if (note != null) {
            e.put("note", note);
        }
        e.put("actor_id", TenantContext.principal());
        rows.insert(TIMELINE, e);
    }

    private UUID cardholder(JsonNode d) {
        UUID a = uuid(d, "ledger_account_id");
        if (a == null) {
            throw ApiException.unprocessable("no_cardholder_account", "the dispute has no cardholder ledger account");
        }
        return a;
    }

    private static void requireOpen(JsonNode d, String what) {
        if (Stage.CLOSED.name().equals(str(d, "stage"))) {
            throw ApiException.conflict("invalid_transition", "cannot " + what + " a closed dispute");
        }
    }

    private static Stage stage(JsonNode d) {
        return Stage.valueOf(str(d, "stage"));
    }

    private static State state(JsonNode d) {
        return new State(stage(d), Status.valueOf(str(d, "status")));
    }

    private static int version(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw ApiException.badRequest("if_match_required", "If-Match header with the current row_version is required");
        }
        try {
            return Integer.parseInt(ifMatch.replace("W/", "").replace("\"", "").trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("invalid_if_match", "If-Match must be the resource's row_version");
        }
    }
}
