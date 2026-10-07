package io.meridian.productcatalog.application;

import io.meridian.common.error.ApiException;
import io.meridian.events.OutboxWriter;
import io.meridian.productcatalog.domain.Lifecycles;
import io.meridian.productcatalog.domain.fees.FeeCalculator;
import io.meridian.productcatalog.domain.rates.RateCalculator;
import io.meridian.productcatalog.domain.simulation.SimulationEngine;
import io.meridian.productcatalog.infrastructure.CatalogStore;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Product simulations: deterministic month-by-month projections run synchronously on creation
 * (QUEUED -> RUNNING -> SUCCEEDED/FAILED in one transaction), stored with results and summary, exportable as CSV.
 */
@Service
public class SimulationService {
    static final String SIMULATION = "product_simulation";
    static final List<String> CSV_COLUMNS = List.of("month", "period_start", "period_end", "opening_balance", "deposits",
            "withdrawals", "interest_rate", "payment", "interest", "principal", "interest_accrued", "interest_capitalised",
            "tax", "fees", "closing_balance");

    private final CatalogStore store;
    private final CrudEngine crud;
    private final PricingInputs inputs;
    private final OutboxWriter outbox;
    private final JsonMapper json;

    public SimulationService(CatalogStore store, CrudEngine crud, PricingInputs inputs, OutboxWriter outbox,
                             JsonMapper json) {
        this.store = store;
        this.crud = crud;
        this.inputs = inputs;
        this.outbox = outbox;
        this.json = json;
    }

    @Transactional
    public String create(Object body) {
        ObjectNode values = crud.toColumns(SIMULATION, body);
        UUID versionId = UUID.fromString(values.get("product_version_id").asString());
        JsonNode version = store.find("product_version", versionId).orElseThrow(() ->
                ApiException.unprocessable("product_version_not_found", "product version " + versionId + " does not exist"));
        JsonNode product = store.require("product", UUID.fromString(version.get("product_id").asString()));
        int horizon = values.hasNonNull("horizon_months") ? values.get("horizon_months").asInt() : 60;
        values.put("horizon_months", horizon);
        Map<String, Object> scenarioMap = CatalogStore.toMap(values.get("scenario"));
        boolean lending = PricingService.LENDING.contains(product.get("family").asString());
        SimulationEngine.Scenario scenario = scenario(scenarioMap, horizon, lending, store.today());
        String currency = product.get("currency").asString().trim();
        SimulationEngine.Product p = productInputs(versionId, currency, lending, scenario.startDate());

        values.put("status", "QUEUED");
        JsonNode row = store.insert(SIMULATION, values);
        UUID id = ProductService.id(row);
        ObjectNode start = json.createObjectNode();
        start.put("status", Lifecycles.SIMULATION.apply("QUEUED", "start"));
        start.put("started_at", io.meridian.common.time.Clocks.now().toString());
        store.update(SIMULATION, id, start);

        ObjectNode done = json.createObjectNode();
        try {
            SimulationEngine.Result r = SimulationEngine.run(p, scenario);
            done.put("status", Lifecycles.SIMULATION.apply("RUNNING", "succeed"));
            done.set("results", store.toNode(r.periods()));
            done.set("summary", store.toNode(r.summary()));
        } catch (ApiException e) {
            done.put("status", Lifecycles.SIMULATION.apply("RUNNING", "fail"));
            done.put("error", e.code() + ": " + e.getMessage());
        }
        done.put("finished_at", io.meridian.common.time.Clocks.now().toString());
        JsonNode finished = store.update(SIMULATION, id, done);
        outbox.publish("product.product_simulation.completed", SIMULATION, id, finished);
        return finished.toString();
    }

    @Transactional
    public String cancel(UUID id) {
        JsonNode s = store.lock(SIMULATION, id);
        ObjectNode values = json.createObjectNode();
        values.put("status", Lifecycles.SIMULATION.apply(s.get("status").asString(), "cancel"));
        values.put("finished_at", io.meridian.common.time.Clocks.now().toString());
        return store.update(SIMULATION, id, values).toString();
    }

    /** Results as CSV (one row per period; columns from the period records). */
    public String exportCsv(UUID id) {
        JsonNode s = store.require(SIMULATION, id);
        if (!"SUCCEEDED".equals(s.get("status").asString())) {
            throw ApiException.conflict("no_results", "Simulation " + id + " is " + s.get("status").asString()
                    + " and has no results to export");
        }
        JsonNode periods = s.path("results");
        StringBuilder csv = new StringBuilder();
        List<String> cols = new ArrayList<>();
        if (!periods.isEmpty()) {
            // jsonb does not keep key order: use the engine's column order, then anything else alphabetically
            JsonNode first = periods.get(0);
            CSV_COLUMNS.stream().filter(first::has).forEach(cols::add);
            first.propertyNames().stream().filter(c -> !cols.contains(c)).sorted().forEach(cols::add);
        }
        csv.append(String.join(",", cols)).append("\r\n");
        for (JsonNode p : periods) {
            List<String> cells = new ArrayList<>();
            for (String c : cols) {
                JsonNode v = p.get(c);
                cells.add(v == null || v.isNull() ? "" : csvCell(v.asString()));
            }
            csv.append(String.join(",", cells)).append("\r\n");
        }
        return csv.toString();
    }

    private SimulationEngine.Product productInputs(UUID versionId, String currency, boolean lending, LocalDate start) {
        List<String> kinds = lending ? PricingService.LENDING_KINDS : PricingService.DEPOSIT_KINDS;
        List<JsonNode> configs = store.children("interest_rate_config", "product_version_id", versionId, "rate_kind, effective_from");
        JsonNode cfg = null;
        for (String k : kinds) {
            for (JsonNode c : configs) {
                if (k.equals(c.get("rate_kind").asString()) && cfg == null) {
                    cfg = c;
                }
            }
        }
        RateCalculator.Config rc = cfg == null ? null : inputs.rateConfig(cfg);
        JsonNode chosen = cfg;
        java.util.function.Function<LocalDate, BigDecimal> index = chosen == null ? null
                : d -> inputs.indexRate(chosen, d).orElse(null);
        List<SimulationEngine.PeriodicFee> fees = new ArrayList<>();
        for (JsonNode f : store.children("fee_definition", "product_version_id", versionId, "code")) {
            FeeCalculator.Tax tax = null;
            try {
                tax = inputs.tax(f, start).orElse(null);
            } catch (ApiException e) {
                // tax rule not valid at the simulation start: simulate without it
            }
            fees.add(new SimulationEngine.PeriodicFee(f.get("trigger").asString(), PricingInputs.text(f, "frequency"),
                    inputs.fee(f), tax));
        }
        String capFreq = cfg == null ? "MONTHLY" : PricingInputs.text(cfg, "capitalisation_frequency");
        return new SimulationEngine.Product(currency, rc, capFreq, index, fees);
    }

    /** Parses and validates the scenario JSON (422 on bad input). */
    static SimulationEngine.Scenario scenario(Map<String, Object> s, int horizon, boolean lending, LocalDate today) {
        try {
            String mode = s.get("mode") != null ? s.get("mode").toString().toUpperCase(java.util.Locale.ROOT)
                    : (lending ? "LOAN" : "DEPOSIT");
            if (!mode.equals("LOAN") && !mode.equals("DEPOSIT")) {
                throw ApiException.unprocessable("invalid_scenario", "mode must be DEPOSIT or LOAN");
            }
            LocalDate start = s.get("start_date") != null ? LocalDate.parse(s.get("start_date").toString())
                    : today.withDayOfMonth(1);
            NavigableMap<Integer, BigDecimal> path = new TreeMap<>();
            if (s.get("rate_path") instanceof List<?> l) {
                for (Object o : l) {
                    if (!(o instanceof Map<?, ?> m) || m.get("month") == null || m.get("rate") == null) {
                        throw ApiException.unprocessable("invalid_scenario", "rate_path entries need month and rate");
                    }
                    path.put(new BigDecimal(m.get("month").toString()).intValueExact(), new BigDecimal(m.get("rate").toString()));
                }
            }
            BigDecimal opening = PricingService.dec(s, "opening_balance");
            BigDecimal deposit = PricingService.dec(s, "monthly_deposit");
            BigDecimal withdrawal = PricingService.dec(s, "monthly_withdrawal");
            for (BigDecimal b : new BigDecimal[] {opening, deposit, withdrawal}) {
                if (b != null && b.signum() < 0) {
                    throw ApiException.unprocessable("invalid_scenario", "amounts cannot be negative");
                }
            }
            Integer txns = PricingService.integer(s, "monthly_transactions");
            if (txns != null && (txns < 0 || txns > 10_000)) {
                throw ApiException.unprocessable("invalid_scenario", "monthly_transactions must be 0..10000");
            }
            if (horizon < 1 || horizon > SimulationEngine.MAX_HORIZON) {
                throw ApiException.unprocessable("invalid_horizon", "horizon_months must be 1.." + SimulationEngine.MAX_HORIZON);
            }
            return new SimulationEngine.Scenario(mode, start, horizon, opening, deposit, withdrawal,
                    PricingService.dec(s, "principal"), PricingService.integer(s, "term_months"),
                    txns == null ? 0 : txns, PricingService.dec(s, "average_transaction_amount"),
                    PricingService.dec(s, "withholding_tax_rate"), path, PricingService.str(s, "segment"));
        } catch (java.time.format.DateTimeParseException | NumberFormatException | ArithmeticException e) {
            throw ApiException.unprocessable("invalid_scenario", "scenario is invalid: " + e.getMessage());
        }
    }

    private static String csvCell(String s) {
        return s.contains(",") || s.contains("\"") || s.contains("\n") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
