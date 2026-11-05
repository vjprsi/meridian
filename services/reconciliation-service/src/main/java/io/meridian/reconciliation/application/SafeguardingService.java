package io.meridian.reconciliation.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.common.time.Clocks;
import io.meridian.events.OutboxWriter;
import io.meridian.reconciliation.domain.SafeguardingPolicy;
import io.meridian.reconciliation.domain.SafeguardingPolicy.Assessment;
import io.meridian.reconciliation.domain.SafeguardingPolicy.Component;
import io.meridian.reconciliation.domain.SafeguardingPolicy.RunDecision;
import io.meridian.reconciliation.domain.SafeguardingPolicy.Status;
import io.meridian.reconciliation.infrastructure.GlBalanceGateway;
import io.meridian.reconciliation.infrastructure.ReconStore;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Trust / e-money safeguarding end-of-day stock reconciliation (WE-RECON-1, decisions D-ED-12 and D-ED-07). For every
 * active {@code recon_safeguarding_config} of the tenant it proves {@code A - (B + C + D + E) = 0} with the pure
 * {@link SafeguardingPolicy}, stores an immutable, audited {@code recon_safeguarding_run} record (component snapshot,
 * result, status, as-of date, source references), and emits an event on completion and on shortfall. A SHORTFALL
 * raises a CRITICAL regulator-reportable alarm and opens a high-priority break in the existing reconciliation
 * exception queue; a persistent surplus (AMOUNT) opens an ageing break there too. Timing surpluses are held.
 *
 * <p>The feature is additive and per-tenant gated: with no active config the tenant's behaviour is unchanged. The
 * run is idempotent per (tenant, config, business date) - a re-run returns the same record, and only advances the
 * stored status when the recomputed outcome is strictly more severe (the component snapshot is write-once).
 *
 * <p>The component figures A..E are read live from gl-service via {@link GlBalanceGateway} (and, for A, optionally a
 * trust bank statement imported as a recon source), or accepted directly on the run request as a documented fallback
 * so the feature is testable and the integration point is explicit.
 */
@Service
public class SafeguardingService {
    private static final Logger log = LoggerFactory.getLogger(SafeguardingService.class);
    private static final String RUN = "recon_safeguarding_run";
    private static final String CFG = "recon_safeguarding_config";

    private final ReconStore store;
    private final CrudEngine crud;
    private final GlBalanceGateway gl;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final JsonMapper json;

    public SafeguardingService(ReconStore store, CrudEngine crud, GlBalanceGateway gl, OutboxWriter outbox,
                               TransactionTemplate tx, JsonMapper json) {
        this.store = store;
        this.crud = crud;
        this.gl = gl;
        this.outbox = outbox;
        this.tx = tx;
        this.json = json;
    }

    /** One safeguarding run record by id (convenience read; the generated list/get serve the standard CRUD). */
    public Map<String, Object> get(UUID id) {
        JsonNode row = store.row("select row_to_json(t)::text from " + RUN + " t where t.id = ?", id)
                .orElseThrow(() -> ApiException.notFound("recon safeguarding run", id));
        return summary(row);
    }

    /** True when the tenant has at least one active safeguarding configuration (the per-tenant gate). */
    public boolean enabledForTenant() {
        return store.count("select count(*) from " + CFG + " where is_active") > 0;
    }

    /**
     * Runs the safeguarding reconciliation for a business date over every active config (or the single config named
     * in the request), applying any component figures supplied on the request. Returns a summary with each config's
     * resulting {@code recon_safeguarding_run} row.
     */
    public Map<String, Object> run(LocalDate businessDate, JsonNode req) {
        if (businessDate == null) {
            throw Fields.invalid("business_date", "required", "business_date is required");
        }
        JsonNode body = req == null ? json.createObjectNode() : req;
        UUID only = Fields.uuid(body, "recon_safeguarding_config_id");
        List<JsonNode> configs = only != null
                ? store.rows("select row_to_json(t)::text from " + CFG + " t where t.id = ? and t.is_active", only)
                : store.rows("select row_to_json(t)::text from " + CFG + " t where t.is_active order by t.name, t.id");

        List<Map<String, Object>> results = new ArrayList<>();
        int balanced = 0;
        int shortfalls = 0;
        int breaks = 0;
        for (JsonNode cfg : configs) {
            Map<String, Object> r = tx.execute(s -> assessConfig(cfg, businessDate, body));
            results.add(r);
            String status = String.valueOf(r.get("status"));
            if (Status.BALANCED.name().equals(status)) {
                balanced++;
            } else if (Status.SHORTFALL.name().equals(status)) {
                shortfalls++;
                breaks++;
            } else if (Status.AMOUNT.name().equals(status)) {
                breaks++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("business_date", businessDate.toString());
        out.put("configs", configs.size());
        out.put("balanced", balanced);
        out.put("shortfalls", shortfalls);
        out.put("breaks_open", breaks);
        out.put("results", results);
        return out;
    }

    /** Assesses one config for the business date and persists / advances its immutable run record (idempotent). */
    private Map<String, Object> assessConfig(JsonNode cfg, LocalDate businessDate, JsonNode body) {
        UUID configId = UUID.fromString(cfg.get("id").asString());
        String currency = Fields.text(cfg, "currency");
        Optional<JsonNode> existing = store.row("select row_to_json(t)::text from " + RUN
                + " t where t.recon_safeguarding_config_id = ? and t.business_date = ?", configId, businessDate);
        Status existingStatus = existing.map(e -> Status.valueOf(e.get("status").asString())).orElse(null);
        boolean priorUnclearedBreak = existingStatus == Status.TIMING || existingStatus == Status.AMOUNT;

        BigDecimal trust = resolveTrustBalance(cfg, body, currency);
        List<Component> components = resolveComponents(cfg, body, currency);
        BigDecimal tolerance = decimalOr(cfg, "tolerance", BigDecimal.ZERO);
        BigDecimal timing = decimalOr(cfg, "timing_tolerance", BigDecimal.ZERO);
        Assessment a = SafeguardingPolicy.assess(trust, components, tolerance, timing, priorUnclearedBreak);

        RunDecision decision = SafeguardingPolicy.decide(existing.isPresent(), existingStatus, a.status());
        if (decision == RunDecision.RETURN_EXISTING) {
            return summary(existing.get());
        }

        UUID runId = existing.map(e -> UUID.fromString(e.get("id").asString())).orElseGet(Ids::newId);
        ObjectNode snapshot = componentSnapshot(trust, components, a, currency);
        boolean firstSighting = decision == RunDecision.CREATE;
        if (firstSighting) {
            ObjectNode v = json.createObjectNode();
            v.put("id", runId.toString());
            v.put("recon_safeguarding_config_id", configId.toString());
            v.put("business_date", businessDate.toString());
            v.put("status", a.status().name());
            if (currency != null) {
                v.put("currency", currency);
            }
            v.put("trust_balance", trust.toPlainString());
            v.put("liability_total", a.liabilityTotal().toPlainString());
            v.put("difference", a.difference().toPlainString());
            v.set("components", snapshot.get("components"));
            v.put("tolerance", tolerance.toPlainString());
            v.put("timing_tolerance", timing.toPlainString());
            v.put("backed", a.backed());
            v.put("alarm_raised", a.alarm());
            v.put("age_days", 0);
            v.set("source_refs", snapshot.get("source_refs"));
            v.put("started_at", Clocks.now().toString());
            v.put("finished_at", Clocks.now().toString());
            crud.insert(RUN, v);
        } else {
            // forward-only advance: the component snapshot stays; status / alarm move up, ageing recomputed
            store.update("update " + RUN + " set status = ?, alarm_raised = alarm_raised or ?, backed = ?, "
                    + "age_days = greatest(0, current_date - business_date), finished_at = ?::timestamptz, updated_by = ? "
                    + "where id = ?", a.status().name(), a.alarm(), a.backed(), Clocks.now().toString(),
                    TenantContext.principal(), runId);
        }

        UUID exceptionId = null;
        if (a.exceptionRequired()) {
            exceptionId = openOrRefreshBreak(runId, a, currency);
            if (exceptionId != null) {
                store.update("update " + RUN + " set recon_exception_id = ? where id = ?", exceptionId, runId);
            }
        }

        JsonNode row = store.row("select row_to_json(t)::text from " + RUN + " t where t.id = ?", runId)
                .orElseThrow(() -> ApiException.notFound("recon safeguarding run", runId));
        emitEvents(runId, businessDate, configId, a, firstSighting, row);
        return summary(row);
    }

    // ------------------------------------------------------------------ component sourcing

    /** A = safeguarding bank balance: request figure, else the configured trust recon source's last closing balance, else a GL account. */
    private BigDecimal resolveTrustBalance(JsonNode cfg, JsonNode body, String currency) {
        BigDecimal supplied = Fields.decimal(body, "trust_balance");
        if (supplied != null) {
            return supplied;
        }
        UUID trustSource = Fields.uuid(cfg, "trust_recon_source_id");
        if (trustSource != null) {
            Optional<JsonNode> file = store.row("select row_to_json(t)::text from recon_file t "
                    + "where t.recon_source_id = ? and t.closing_balance is not null "
                    + "order by t.statement_date desc nulls last, t.created_at desc limit 1", trustSource);
            if (file.isPresent() && file.get().hasNonNull("closing_balance")) {
                return new BigDecimal(file.get().get("closing_balance").asString());
            }
        }
        String trustCode = Fields.text(cfg, "trust_gl_code");
        UUID legalEntity = Fields.uuid(cfg, "legal_entity_id");
        if (trustCode != null && legalEntity != null) {
            return gl.closingBalanceByCode(legalEntity, trustCode, currency);
        }
        throw ApiException.unprocessable("trust_balance_unresolved", "Could not determine the safeguarding bank "
                + "balance A: supply trust_balance on the request, or configure a trust recon source or GL code");
    }

    /**
     * B..E liability components: the request's {@code components} array when present, else each configured liability
     * component's live GL balance. Request figures are the documented fallback / override when a live read is not
     * wired for a tenant.
     */
    private List<Component> resolveComponents(JsonNode cfg, JsonNode body, String currency) {
        List<Component> out = new ArrayList<>();
        if (body.path("components").isArray() && !body.path("components").isEmpty()) {
            for (JsonNode c : body.path("components")) {
                out.add(new Component(Fields.requireText(c, "role"), Fields.text(c, "label"),
                        Fields.requireDecimal(c, "amount")));
            }
            return out;
        }
        UUID legalEntity = Fields.uuid(cfg, "legal_entity_id");
        for (JsonNode c : cfg.path("liability_components")) {
            String role = Fields.requireText(c, "role");
            String glCode = Fields.text(c, "gl_code");
            BigDecimal amount = Fields.decimal(c, "amount");
            if (amount == null && glCode != null && legalEntity != null) {
                amount = gl.closingBalanceByCode(legalEntity, glCode, currency);
            }
            if (amount == null) {
                throw ApiException.unprocessable("component_unresolved", "Could not determine the amount of "
                        + "safeguarding component " + role + "; supply it on the request or configure a GL code");
            }
            out.add(new Component(role, Fields.text(c, "label") != null ? Fields.text(c, "label") : glCode, amount));
        }
        if (out.isEmpty()) {
            throw ApiException.unprocessable("no_components", "The safeguarding config has no liability components "
                    + "(B..E) and none were supplied on the request");
        }
        return out;
    }

    private ObjectNode componentSnapshot(BigDecimal trust, List<Component> components, Assessment a, String currency) {
        ObjectNode snap = json.createObjectNode();
        ArrayNode comps = snap.putArray("components");
        for (Component c : components) {
            ObjectNode cn = comps.addObject();
            cn.put("role", c.role());
            if (c.label() != null) {
                cn.put("label", c.label());
            }
            cn.put("amount", c.amount().toPlainString());
        }
        ObjectNode refs = snap.putObject("source_refs");
        refs.put("trust_balance", trust.toPlainString());
        refs.put("liability_total", a.liabilityTotal().toPlainString());
        if (currency != null) {
            refs.put("currency", currency);
        }
        refs.put("formula", "A-(B+C+D+E)");
        return snap;
    }

    // ------------------------------------------------------------------ breaks + events

    /**
     * Opens (or refreshes) one high-priority break in the existing reconciliation exception queue for the current
     * shortfall / persistent surplus, reusing {@code recon_exception} and its lifecycle. One open break per run.
     */
    private UUID openOrRefreshBreak(UUID runId, Assessment a, String currency) {
        String reason = a.status() == Status.SHORTFALL ? "SAFEGUARDING_SHORTFALL" : "SAFEGUARDING_SURPLUS";
        Optional<UUID> open = store.row("select to_json(t.id)::text from recon_exception t "
                + "where t.recon_safeguarding_run_id = ? and t.status in ('OPEN', 'INVESTIGATING')", runId)
                .map(n -> UUID.fromString(n.asString()));
        if (open.isPresent()) {
            store.update("update recon_exception set reason = ?, amount = ?, updated_by = ? where id = ?",
                    reason, a.difference().abs().toPlainString(), TenantContext.principal(), open.get());
            return open.get();
        }
        ObjectNode v = json.createObjectNode();
        v.put("reason", reason);
        v.put("status", "OPEN");
        v.put("recon_safeguarding_run_id", runId.toString());
        v.put("amount", a.difference().abs().toPlainString());
        if (currency != null) {
            v.put("currency", currency);
        }
        JsonNode row = json.readTree(crud.insert("recon_exception", v));
        UUID id = UUID.fromString(row.get("id").asString());
        outbox.publish("ledger.recon_exception.raised", "recon_exception", id, row);
        return id;
    }

    private void emitEvents(UUID runId, LocalDate businessDate, UUID configId, Assessment a, boolean firstSighting,
                            JsonNode row) {
        String key = "recon-safeguarding:" + TenantContext.require() + ":" + configId + ":" + businessDate;
        if (firstSighting) {
            UUID completed = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
            outbox.publish("ledger.recon_safeguarding.completed", RUN, completed, row);
        }
        if (a.status() == Status.SHORTFALL) {
            UUID shortfall = UUID.nameUUIDFromBytes((key + ":shortfall").getBytes(StandardCharsets.UTF_8));
            outbox.publish("ledger.recon_safeguarding.shortfall", RUN, shortfall, row);
            log.warn("SAFEGUARDING SHORTFALL run {} business_date {}: A-(B+C+D+E) = {} (regulator-reportable)",
                    runId, businessDate, a.difference().toPlainString());
        }
    }

    private static BigDecimal decimalOr(JsonNode n, String field, BigDecimal fallback) {
        BigDecimal v = Fields.decimal(n, field);
        return v == null ? fallback : v;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> summary(JsonNode row) {
        return json.treeToValue(row, Map.class);
    }
}
