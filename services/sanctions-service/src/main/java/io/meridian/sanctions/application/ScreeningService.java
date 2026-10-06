package io.meridian.sanctions.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.sanctions.domain.NameMatcher;
import io.meridian.sanctions.infrastructure.Db;
import io.meridian.sanctions.infrastructure.Downstream;
import io.meridian.sanctions.infrastructure.ScreeningStore;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Screening of subjects and payments, compliance review of potential matches (four-eyes on confirmations and on
 * clearing a true match), good-guy whitelisting and rescreening.
 */
@Service
public class ScreeningService {
    static final Set<String> PERIODIC_SUBJECTS = Set.of("CUSTOMER", "RELATED_PARTY", "MERCHANT", "AGENT", "EMPLOYEE");

    private final ScreeningEngine engine;
    private final ScreeningStore store;
    private final ListCatalog catalog;
    private final Downstream downstream;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final Db db;
    private final JsonMapper json;

    public ScreeningService(ScreeningEngine engine, ScreeningStore store, ListCatalog catalog, Downstream downstream,
                            OutboxWriter outbox, TransactionTemplate tx, Db db, JsonMapper json) {
        this.engine = engine;
        this.store = store;
        this.catalog = catalog;
        this.downstream = downstream;
        this.outbox = outbox;
        this.tx = tx;
        this.db = db;
        this.json = json;
    }

    /** Screens a subject (customer, related party, payee, ...) and returns {request, result}. */
    public JsonNode screenSubject(JsonNode body) {
        long started = System.nanoTime();
        TenantContext.require();
        String name = Db.str(body, "full_name");
        if (name == null || name.isBlank()) {
            throw ApiException.unprocessable("full_name_required", "full_name is required");
        }
        double threshold = threshold(body);
        Set<String> codes = codes(body.path("list_codes"));
        NameMatcher.Subject subject = ScreeningEngine.subject(body);
        List<ScreeningEngine.Hit> hits = engine.screen(subject, codes, threshold);
        UUID subjectId = UUID.fromString(body.get("subject_id").asString());
        return tx.execute(s -> persist(body, subjectId, hits, Set.of(subjectId), started));
    }

    /** Synchronous payment screening (debtor, creditor, agents, free text). Returns the result row. */
    public JsonNode screenPayment(UUID paymentId, JsonNode payload, BigDecimal thresholdValue, String correlationId) {
        long started = System.nanoTime();
        TenantContext.require();
        if (payload == null || !payload.isObject() || payload.isEmpty()) {
            throw ApiException.unprocessable("payload_required", "payload must describe the payment parties");
        }
        double threshold = thresholdValue == null ? 0.85 : validThreshold(thresholdValue.doubleValue());
        List<ScreeningEngine.Hit> hits = engine.screenPayment(payload, null, threshold);
        ObjectNode req = json.createObjectNode();
        req.put("subject_type", "PAYMENT");
        req.put("subject_id", paymentId.toString());
        req.put("trigger", "PAYMENT");
        String debtor = payload.path("debtor").path("name").asString("");
        req.put("full_name", debtor.isBlank() ? "Payment " + paymentId : debtor);
        req.set("payload", payload);
        req.put("threshold", threshold);
        req.put("requested_by_service", "payment-service");
        if (correlationId != null) {
            req.put("correlation_id", correlationId);
        }
        Set<UUID> subjects = new LinkedHashSet<>();
        subjects.add(paymentId);
        for (String p : ScreeningEngine.PARTIES) {
            String cid = payload.path(p).path("customer_id").asString(null);
            if (cid != null) {
                subjects.add(UUID.fromString(cid));
            }
        }
        return tx.execute(s -> persist(req, paymentId, hits, subjects, started)).get("result");
    }

    /** Screens the subject of an earlier result again with the current lists. */
    public JsonNode rescreen(UUID resultId) {
        JsonNode result = db.require("screening_result", resultId, false);
        JsonNode request = db.require("screening_request", UUID.fromString(result.get("screening_request_id").asString()), false);
        if ("PAYMENT".equals(request.get("subject_type").asString())) {
            JsonNode payload = request.get("payload");
            return screenPayment(UUID.fromString(request.get("subject_id").asString()), payload,
                    Db.dec(request, "threshold"), Db.str(request, "correlation_id"));
        }
        ObjectNode body = (ObjectNode) request.deepCopy();
        for (String f : List.of("id", "tenant_id", "created_at", "created_by", "updated_at", "updated_by", "row_version",
                "status", "screening_result_id", "screening_batch_id")) {
            body.remove(f);
        }
        body.put("trigger", "MANUAL");
        return screenSubject(body).get("result");
    }

    JsonNode persist(JsonNode body, UUID subjectId, List<ScreeningEngine.Hit> hits, Set<UUID> subjects, long started) {
        Set<UUID> suppressedEntries = new HashSet<>(store.whitelisted(subjects));
        UUID requestId = Ids.newId();
        UUID resultId = Ids.newId();
        ArrayNode matches = json.createArrayNode();
        double highest = 0;
        boolean adverseMedia = false;
        int suppressed = 0;
        for (ScreeningEngine.Hit h : hits) {
            NameMatcher.Match m = h.match();
            if (suppressedEntries.contains(m.entry().id())) {
                suppressed++;
                continue;
            }
            ObjectNode mn = matches.addObject();
            mn.put("sanctions_list_entry_id", m.entry().id().toString());
            mn.put("matched_name", m.matchedName().length() > 500 ? m.matchedName().substring(0, 500) : m.matchedName());
            mn.put("match_score", m.score());
            mn.put("match_algorithm", m.algorithm());
            ObjectNode fields = json.valueToTree(m.fields());
            fields.put("list_code", m.entry().listCode());
            fields.put("list_source", m.entry().listSource());
            fields.put("entry_type", m.entry().entryType());
            if (h.party() != null) {
                fields.put("party", h.party());
            }
            mn.set("matched_fields", fields);
            highest = Math.max(highest, m.score());
            adverseMedia |= "ADVERSE_MEDIA".equals(m.entry().listSource());
        }
        String status = matches.isEmpty() ? "CLEAR" : "POTENTIAL_MATCH";
        ObjectNode req = json.createObjectNode();
        for (String f : List.of("subject_type", "trigger", "full_name", "date_of_birth", "country", "nationality",
                "identifiers", "payload", "list_codes", "threshold", "requested_by_service", "correlation_id",
                "screening_batch_id")) {
            if (body.hasNonNull(f)) {
                req.set(f, body.get(f));
            }
        }
        if (!req.hasNonNull("requested_by_service")) {
            req.put("requested_by_service", "api");
        }
        req.put("id", requestId.toString());
        req.put("subject_id", subjectId.toString());
        req.put("status", status);
        req.put("screening_result_id", resultId.toString());
        ObjectNode res = json.createObjectNode();
        res.put("id", resultId.toString());
        res.put("subject_type", req.get("subject_type").asString());
        res.put("subject_id", subjectId.toString());
        res.put("status", status);
        res.put("highest_score", highest);
        res.put("match_count", matches.size());
        res.put("has_adverse_media", adverseMedia);
        res.put("suppressed_count", suppressed);
        if ("CLEAR".equals(status) && PERIODIC_SUBJECTS.contains(req.get("subject_type").asString())) {
            res.put("rescreen_due_at", io.meridian.common.time.Clocks.now().plusYears(1).toString());
        }
        res.put("latency_ms", (System.nanoTime() - started) / 1_000_000);
        return store.persist(req, res, matches, TenantContext.principal());
    }

    // ------------------------------------------------------------------ review

    /**
     * CONFIRMED_MATCH needs at least one TRUE_MATCH, confirmed by someone other than the reviewer who dispositioned
     * it; a case is opened in case management. FALSE_POSITIVE needs no TRUE_MATCH; pending matches are cleared and
     * can be whitelisted for the subject.
     */
    public JsonNode review(UUID resultId, String status, String reason, boolean whitelist) {
        if (!"CONFIRMED_MATCH".equals(status) && !"FALSE_POSITIVE".equals(status)) {
            throw ApiException.unprocessable("invalid_review_status", "status must be CONFIRMED_MATCH or FALSE_POSITIVE");
        }
        String reviewer = TenantContext.principal();
        JsonNode result = db.require("screening_result", resultId, false);
        requirePotential(result);
        List<JsonNode> matches = db.list("select row_to_json(m)::text from screening_match m where screening_result_id = ?", resultId);
        List<JsonNode> trueMatches = matches.stream().filter(m -> "TRUE_MATCH".equals(m.get("disposition").asString())).toList();
        UUID caseId = null;
        if ("CONFIRMED_MATCH".equals(status)) {
            if (trueMatches.isEmpty()) {
                throw ApiException.unprocessable("no_true_match", "Disposition at least one match as TRUE_MATCH first");
            }
            for (JsonNode m : trueMatches) {
                if (reviewer.equals(Db.str(m, "dispositioned_by"))) {
                    throw ApiException.unprocessable("four_eyes_violation",
                            "A confirmed match must be reviewed by someone other than the analyst who dispositioned it");
                }
            }
            caseId = downstream.openSanctionsCase(result, trueMatches, reason);
        } else if (!trueMatches.isEmpty()) {
            throw ApiException.conflict("true_match_present",
                    "A match is dispositioned TRUE_MATCH; a second reviewer must clear it before the result can be cleared");
        }
        UUID finalCase = caseId;
        return tx.execute(s -> {
            JsonNode locked = db.require("screening_result", resultId, true);
            requirePotential(locked);
            boolean pep = false;
            if ("CONFIRMED_MATCH".equals(status)) {
                for (JsonNode m : trueMatches) {
                    JsonNode entry = db.require("sanctions_list_entry", UUID.fromString(m.get("sanctions_list_entry_id").asString()), false);
                    String source = db.one("select json_build_object('s', source)::text from sanctions_list where id = ?",
                            UUID.fromString(entry.get("sanctions_list_id").asString())).map(n -> n.get("s").asString()).orElse("");
                    pep |= "PEP".equals(source) || entry.hasNonNull("pep_level");
                }
            } else {
                db.update("""
                        update screening_match set disposition = 'FALSE_POSITIVE', dispositioned_by = ?,
                            disposition_reason = coalesce(disposition_reason, ?), updated_by = ?
                        where screening_result_id = ? and disposition = 'PENDING'""", reviewer, reason, reviewer, resultId);
                if (whitelist) {
                    for (JsonNode m : matches) {
                        db.update("""
                                insert into screening_whitelist (tenant_id, created_by, updated_by, subject_type, subject_id,
                                    sanctions_list_entry_id, reason, approved_by, expires_at)
                                values (current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, now() + interval '1 year')
                                on conflict (tenant_id, subject_id, sanctions_list_entry_id) do nothing""",
                                reviewer, reviewer, locked.get("subject_type").asString(),
                                UUID.fromString(locked.get("subject_id").asString()),
                                UUID.fromString(m.get("sanctions_list_entry_id").asString()), reason, reviewer);
                    }
                }
            }
            db.update("""
                    update screening_result set status = ?, reviewed_by = ?, reviewed_at = now(), decision_reason = ?,
                        is_pep = ?, aml_case_id = ?, updated_by = ?,
                        rescreen_due_at = case when ? = 'FALSE_POSITIVE' then now() + interval '1 year' else rescreen_due_at end
                    where id = ?""", status, reviewer, reason, pep, finalCase, reviewer, status, resultId);
            db.update("update screening_request set status = ? where id = ?", status,
                    UUID.fromString(locked.get("screening_request_id").asString()));
            JsonNode row = db.require("screening_result", resultId, false);
            outbox.publish("CONFIRMED_MATCH".equals(status) ? "risk.screening.confirmed_match" : "risk.screening.cleared",
                    "screening_result", resultId, row);
            return row;
        });
    }

    /** PATCH of a match disposition; clearing a TRUE_MATCH needs a different reviewer (four eyes). */
    public JsonNode disposition(UUID matchId, JsonNode changes, int expectedVersion) {
        return tx.execute(s -> {
            JsonNode m = db.require("screening_match", matchId, true);
            if (m.get("row_version").asInt() != expectedVersion) {
                throw ApiException.conflict("version_conflict", "If-Match " + expectedVersion + " does not match the current version; reload and retry");
            }
            JsonNode result = db.require("screening_result", UUID.fromString(m.get("screening_result_id").asString()), false);
            requirePotential(result);
            String to = Db.str(changes, "disposition");
            String reason = Db.str(changes, "disposition_reason");
            String from = m.get("disposition").asString();
            String who = TenantContext.principal();
            if (to == null || to.equals(from)) {
                if (reason != null) {
                    db.update("update screening_match set disposition_reason = ?, updated_by = ? where id = ?", reason, who, matchId);
                }
                return db.require("screening_match", matchId, false);
            }
            if (!"PENDING".equals(to) && (reason == null || reason.isBlank())) {
                throw ApiException.unprocessable("disposition_reason_required", "A rationale is required for a disposition");
            }
            if ("TRUE_MATCH".equals(from) && who.equals(Db.str(m, "dispositioned_by"))) {
                throw ApiException.unprocessable("four_eyes_violation",
                        "Clearing a true match needs a second reviewer (not " + who + ")");
            }
            db.update("""
                    update screening_match set disposition = ?, disposition_reason = ?, dispositioned_by = ?, updated_by = ?
                    where id = ?""", to, reason, "PENDING".equals(to) ? null : who, who, matchId);
            return db.require("screening_match", matchId, false);
        });
    }

    // ------------------------------------------------------------------ whitelist

    public JsonNode whitelist(JsonNode body) {
        UUID entryId = Db.uuid(body, "sanctions_list_entry_id");
        catalog.requireEntry(entryId);
        if (body.hasNonNull("expires_at")
                && !java.time.OffsetDateTime.parse(body.get("expires_at").asString()).isAfter(io.meridian.common.time.Clocks.now())) {
            throw ApiException.unprocessable("invalid_expiry", "expires_at must be in the future");
        }
        return tx.execute(s -> {
            String who = TenantContext.principal();
            JsonNode row = db.one("""
                    insert into screening_whitelist as w (tenant_id, created_by, updated_by, subject_type, subject_id,
                        sanctions_list_entry_id, reason, approved_by, expires_at)
                    values (current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, cast(? as timestamptz))
                    on conflict (tenant_id, subject_id, sanctions_list_entry_id) do nothing
                    returning row_to_json(w)::text""", who, who, Db.str(body, "subject_type"), Db.uuid(body, "subject_id"),
                    entryId, Db.str(body, "reason"), who, Db.str(body, "expires_at"))
                    .orElseThrow(() -> ApiException.conflict("already_whitelisted", "The subject is already whitelisted for this entry"));
            outbox.publish("risk.screening_whitelist.added", "screening_whitelist", UUID.fromString(row.get("id").asString()), row);
            return row;
        });
    }

    // ------------------------------------------------------------------ helpers

    private static void requirePotential(JsonNode result) {
        String st = result.get("status").asString();
        if (!"POTENTIAL_MATCH".equals(st)) {
            throw ApiException.conflict("invalid_transition", "Only POTENTIAL_MATCH results can be reviewed (result is " + st + ")");
        }
    }

    static double threshold(JsonNode body) {
        return body.hasNonNull("threshold") ? validThreshold(new BigDecimal(body.get("threshold").asString()).doubleValue()) : 0.85;
    }

    static double validThreshold(double t) {
        if (t < 0.5 || t > 1.0) {
            throw ApiException.unprocessable("invalid_threshold", "threshold must be between 0.5 and 1.0");
        }
        return t;
    }

    static Set<String> codes(JsonNode n) {
        Set<String> out = new LinkedHashSet<>();
        if (n != null) {
            n.forEach(c -> out.add(c.asString()));
        }
        return out;
    }

    List<String> ids(List<JsonNode> rows) {
        List<String> out = new ArrayList<>();
        rows.forEach(r -> out.add(r.get("id").asString()));
        return out;
    }
}
