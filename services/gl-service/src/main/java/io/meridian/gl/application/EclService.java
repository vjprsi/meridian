package io.meridian.gl.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.events.OutboxWriter;
import io.meridian.gl.domain.EclCalculator;
import io.meridian.gl.domain.JournalLine;
import io.meridian.gl.infrastructure.GlStore;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * IFRS 9 ECL calculation for a legal entity and reporting date. Inputs come from the request ({@code exposures})
 * or, when omitted, are rolled forward from each exposure's latest persisted provision. Provisions are immutable;
 * a reporting date is calculated once. When the provision GL accounts are given, the change in total ECL per
 * currency is posted as a PROVISION journal (DR impairment expense / CR loss allowance, or the reverse on release).
 * The calculation is recorded as a period_close_run of the period covering the reporting date.
 */
@Service
public class EclService {
    private final GlStore store;
    private final CrudEngine crud;
    private final JournalService journals;
    private final OutboxWriter outbox;
    private final JsonMapper json;

    public EclService(GlStore store, CrudEngine crud, JournalService journals, OutboxWriter outbox, JsonMapper json) {
        this.store = store;
        this.crud = crud;
        this.journals = journals;
        this.outbox = outbox;
        this.json = json;
    }

    /**
     * Runs the calculation (request = EclProvisionsCalculateRequest) and returns the result object: legal_entity_id,
     * reporting_date, model_version, exposures, total_ecl per currency, gl_journal_id, period_close_run_id and the
     * persisted provisions.
     */
    @Transactional
    public JsonNode calculate(JsonNode req) {
        UUID le = Fields.requireUuid(req, "legal_entity_id");
        LocalDate date = Fields.requireDate(req, "reporting_date");
        UUID expenseGl = Fields.uuid(req, "provision_expense_gl_account_id");
        UUID allowanceGl = Fields.uuid(req, "allowance_gl_account_id");
        if ((expenseGl == null) != (allowanceGl == null)) {
            throw ApiException.unprocessable("provision_accounts_incomplete",
                    "Pass both provision_expense_gl_account_id and allowance_gl_account_id, or neither");
        }
        List<JsonNode> periods = store.periodsContaining(le, date);
        if (periods.isEmpty()) {
            throw ApiException.unprocessable("no_accounting_period",
                    "No accounting period of legal entity " + le + " covers " + date);
        }
        store.advisoryLock("ecl:" + le + ":" + date);
        if (store.count("select count(*) from ecl_provision where legal_entity_id = ? and reporting_date = ?", le, date) > 0) {
            throw ApiException.conflict("already_calculated",
                    "ECL provisions for " + date + " were already calculated; provisions are immutable");
        }

        Map<UUID, JsonNode> previous = new HashMap<>();
        store.rows("""
                select distinct on (t.exposure_id) row_to_json(t)::text from ecl_provision t
                where t.legal_entity_id = ? and t.reporting_date < ? order by t.exposure_id, t.reporting_date desc""",
                le, date).forEach(p -> previous.put(UUID.fromString(p.get("exposure_id").asString()), p));

        List<EclCalculator.Input> inputs = new ArrayList<>();
        JsonNode exposures = req.path("exposures");
        if (exposures.isArray() && !exposures.isEmpty()) {
            Set<UUID> seen = new HashSet<>();
            for (JsonNode e : exposures) {
                EclCalculator.Input in = parseInput(e);
                if (!seen.add(in.exposureId())) {
                    throw ApiException.unprocessable("duplicate_exposure", "Exposure " + in.exposureId() + " is listed twice");
                }
                inputs.add(in);
            }
        } else {
            previous.values().stream()
                    .sorted((a, b) -> a.get("exposure_id").asString().compareTo(b.get("exposure_id").asString()))
                    .forEach(p -> {
                        BigDecimal pd = new BigDecimal(p.get("pd").asString());
                        inputs.add(new EclCalculator.Input(p.get("exposure_type").asString(),
                                UUID.fromString(p.get("exposure_id").asString()),
                                EclCalculator.Stage.valueOf(p.get("stage").asString()),
                                new BigDecimal(p.get("ead").asString()), pd, pd, new BigDecimal(p.get("lgd").asString()),
                                p.get("currency").asString().trim()));
                    });
        }
        if (inputs.isEmpty()) {
            throw ApiException.unprocessable("no_exposures",
                    "No exposures given and no earlier provisions to roll forward for legal entity " + le);
        }

        List<EclCalculator.Result> results = inputs.stream().map(EclCalculator::calculate).toList();
        Map<String, BigDecimal> totals = new TreeMap<>();
        Map<String, BigDecimal> deltas = new TreeMap<>();
        for (EclCalculator.Result r : results) {
            String ccy = r.input().currency();
            totals.merge(ccy, r.ecl(), BigDecimal::add);
            JsonNode prev = previous.get(r.input().exposureId());
            BigDecimal before = prev != null && prev.get("currency").asString().trim().equals(ccy)
                    ? new BigDecimal(prev.get("ecl_amount").asString()) : BigDecimal.ZERO;
            deltas.merge(ccy, r.ecl().subtract(before), BigDecimal::add);
        }

        String journalId = null;
        if (expenseGl != null) {
            List<JournalLine> lines = new ArrayList<>();
            deltas.forEach((ccy, d) -> {
                if (d.signum() == 0) {
                    return;
                }
                boolean charge = d.signum() > 0;
                lines.add(new JournalLine(expenseGl, charge ? JournalLine.Direction.DEBIT : JournalLine.Direction.CREDIT,
                        d.abs(), ccy, d.abs(), null, null, null));
                lines.add(new JournalLine(allowanceGl, charge ? JournalLine.Direction.CREDIT : JournalLine.Direction.DEBIT,
                        d.abs(), ccy, d.abs(), null, null, null));
            });
            if (!lines.isEmpty()) {
                String j = journals.postSystemJournal(le, "PROVISION", date, "IFRS 9 ECL provision " + date, null,
                        "system:ecl", lines);
                journalId = json.readTree(j).get("id").asString();
            }
        }

        ObjectNode result = json.createObjectNode();
        ArrayNode provisions = json.createArrayNode();
        for (EclCalculator.Result r : results) {
            ObjectNode v = json.createObjectNode();
            v.put("legal_entity_id", le.toString());
            v.put("reporting_date", date.toString());
            v.put("exposure_type", r.input().exposureType());
            v.put("exposure_id", r.input().exposureId().toString());
            v.put("stage", r.input().stage().name());
            v.put("ead", r.input().ead().toPlainString());
            v.put("pd", r.appliedPd().toPlainString());
            v.put("lgd", r.input().lgd().toPlainString());
            v.put("ecl_amount", r.ecl().toPlainString());
            v.put("currency", r.input().currency());
            v.put("model_version", EclCalculator.MODEL_VERSION);
            if (journalId != null) {
                v.put("gl_journal_id", journalId);
            }
            JsonNode row = json.readTree(crud.insert("ecl_provision", v));
            provisions.add(row);
            outbox.publish("ledger.ecl_provision.calculated", "ecl_provision",
                    UUID.fromString(row.get("id").asString()), row);
        }

        ObjectNode steps = json.createObjectNode();
        ObjectNode step = steps.putArray("checklist").addObject();
        step.put("step", "ecl_calculation");
        step.put("status", "PASSED");
        step.put("detail", results.size() + " exposure(s) provisioned with model " + EclCalculator.MODEL_VERSION);
        steps.put("exposures", results.size());
        ObjectNode total = steps.putObject("total_ecl");
        totals.forEach((ccy, t) -> total.put(ccy, t.toPlainString()));
        if (journalId != null) {
            steps.put("gl_journal_id", journalId);
        }
        ObjectNode run = json.createObjectNode();
        UUID runId = Ids.newId();
        run.put("id", runId.toString());
        run.put("accounting_period_id", periods.getFirst().get("id").asString());
        run.put("status", "SUCCEEDED");
        run.put("started_at", io.meridian.common.time.Clocks.now().toString());
        run.put("finished_at", io.meridian.common.time.Clocks.now().toString());
        run.set("steps", steps);
        String runRow = crud.insert("period_close_run", run);
        outbox.publish("ledger.period_close_run.completed", "period_close_run", runId, json.readTree(runRow));

        result.put("legal_entity_id", le.toString());
        result.put("reporting_date", date.toString());
        result.put("model_version", EclCalculator.MODEL_VERSION);
        result.put("exposures", results.size());
        result.set("total_ecl", total.deepCopy());
        if (journalId != null) {
            result.put("gl_journal_id", journalId);
        } else {
            result.putNull("gl_journal_id");
        }
        result.put("period_close_run_id", runId.toString());
        result.set("provisions", provisions);
        return result;
    }

    private static EclCalculator.Input parseInput(JsonNode e) {
        EclCalculator.Stage stage = Fields.enumValue(e, "stage", EclCalculator.Stage.class);
        if (stage == null) {
            throw Fields.invalid("stage", "required", "Each exposure needs a stage");
        }
        BigDecimal pd = Fields.decimal(e, "pd");
        BigDecimal pd12 = Fields.decimal(e, "pd_12m");
        BigDecimal pdLife = Fields.decimal(e, "pd_lifetime");
        return new EclCalculator.Input(Fields.requireText(e, "exposure_type"), Fields.requireUuid(e, "exposure_id"),
                stage, Fields.requireDecimal(e, "ead"), pd12 != null ? pd12 : pd, pdLife != null ? pdLife : pd,
                Fields.requireDecimal(e, "lgd"), Fields.requireText(e, "currency"));
    }
}
