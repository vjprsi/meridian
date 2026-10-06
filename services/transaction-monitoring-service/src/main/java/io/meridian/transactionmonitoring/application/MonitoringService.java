package io.meridian.transactionmonitoring.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.transactionmonitoring.domain.Scenarios;
import io.meridian.transactionmonitoring.infrastructure.Db;
import io.meridian.transactionmonitoring.infrastructure.TmStore;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The monitoring pipeline: ingest transaction legs (idempotent), update the risk graph, evaluate the customer's
 * active streaming scenarios and raise de-duplicated alerts with their contributing transactions. Batch runs and
 * backtests reuse {@link #evaluateCustomer}.
 */
@Service
public class MonitoringService {
    static final int MAX_INGEST = 1000;
    static final Set<String> MULE_TYPES = Set.of("MULE_ACCOUNT", "FAN_IN");

    private final TmStore store;
    private final ScenarioCatalog scenarios;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final Db db;
    private final JsonMapper json;

    public MonitoringService(TmStore store, ScenarioCatalog scenarios, OutboxWriter outbox, TransactionTemplate tx, Db db,
                             JsonMapper json) {
        this.store = store;
        this.scenarios = scenarios;
        this.outbox = outbox;
        this.tx = tx;
        this.db = db;
        this.json = json;
    }

    public record Raised(UUID alertId, String scenarioCode, UUID customerId, int score) {}

    // ------------------------------------------------------------------ ingest

    public Map<String, Object> ingest(JsonNode items) {
        TenantContext.require();
        if (items == null || !items.isArray() || items.isEmpty()) {
            throw ApiException.unprocessable("transactions_required", "transactions must be a non-empty array");
        }
        if (items.size() > MAX_INGEST) {
            throw ApiException.unprocessable("too_many_transactions", "At most " + MAX_INGEST + " legs per call");
        }
        ArrayNode rows = json.createArrayNode();
        int i = 0;
        for (JsonNode t : items) {
            rows.add(validate(t, ++i));
        }
        return tx.execute(s -> {
            List<JsonNode> inserted = store.insertTransactions(rows, TenantContext.principal());
            List<Scenarios.Txn> txns = new ArrayList<>();
            Map<UUID, List<Scenarios.Txn>> byCustomer = new LinkedHashMap<>();
            for (JsonNode n : inserted) {
                Scenarios.Txn t = TmStore.txn(n);
                txns.add(t);
                byCustomer.computeIfAbsent(UUID.fromString(n.get("customer_id").asString()), k -> new ArrayList<>()).add(t);
            }
            updateGraph(byCustomer);
            List<Raised> raised = new ArrayList<>();
            List<ScenarioCatalog.Active> streaming = scenarios.active().stream()
                    .filter(a -> "STREAMING".equals(a.runMode())).toList();
            for (Map.Entry<UUID, List<Scenarios.Txn>> e : byCustomer.entrySet()) {
                Instant from = e.getValue().stream().map(Scenarios.Txn::occurredAt).min(Instant::compareTo).orElseThrow();
                Instant to = e.getValue().stream().map(Scenarios.Txn::occurredAt).max(Instant::compareTo).orElseThrow();
                raised.addAll(evaluateCustomer(e.getKey(), streaming, from, to, null, false).raised());
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("received", items.size());
            out.put("inserted", inserted.size());
            out.put("duplicates", items.size() - inserted.size());
            out.put("customers_evaluated", byCustomer.size());
            out.put("scenarios_evaluated", streaming.size());
            List<Map<String, Object>> alerts = new ArrayList<>();
            for (Raised r : raised) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("tm_alert_id", r.alertId().toString());
                m.put("scenario_code", r.scenarioCode());
                m.put("customer_id", r.customerId().toString());
                m.put("score", r.score());
                alerts.add(m);
            }
            out.put("alerts_raised", alerts);
            return out;
        });
    }

    /** Ingests a single leg (CRUD create) through the same pipeline; returns the stored row or the existing one. */
    public String ingestOne(JsonNode body) {
        ArrayNode arr = json.createArrayNode();
        arr.add(body);
        ingest(arr);
        ObjectNode v = validate(body, 1);
        return db.one("""
                select row_to_json(t)::text from tm_transaction t
                where transaction_id = cast(? as uuid) and customer_id = cast(? as uuid) and direction = ?""",
                v.get("transaction_id").asString(), v.get("customer_id").asString(), v.get("direction").asString())
                .map(db::text).orElseThrow();
    }

    ObjectNode validate(JsonNode t, int line) {
        if (!t.isObject()) {
            throw ApiException.unprocessable("invalid_transaction", "Leg " + line + " must be an object");
        }
        ObjectNode v = (ObjectNode) t.deepCopy();
        for (String f : List.of("transaction_id", "transaction_type", "customer_id", "direction", "amount", "currency", "occurred_at")) {
            if (!v.hasNonNull(f) || v.get(f).asString("").isBlank()) {
                throw ApiException.unprocessable("invalid_transaction", "Leg " + line + ": " + f + " is required");
            }
        }
        try {
            UUID.fromString(v.get("transaction_id").asString());
            UUID.fromString(v.get("customer_id").asString());
            OffsetDateTime.parse(v.get("occurred_at").asString());
        } catch (RuntimeException e) {
            throw ApiException.unprocessable("invalid_transaction", "Leg " + line + ": " + e.getMessage());
        }
        String dir = v.get("direction").asString();
        if (!"DEBIT".equals(dir) && !"CREDIT".equals(dir)) {
            throw ApiException.unprocessable("invalid_transaction", "Leg " + line + ": direction must be DEBIT or CREDIT");
        }
        BigDecimal amount = new BigDecimal(v.get("amount").asString());
        if (amount.signum() <= 0) {
            throw ApiException.unprocessable("invalid_transaction", "Leg " + line + ": amount must be positive");
        }
        if (!v.get("currency").asString().matches("[A-Z]{3}")) {
            throw ApiException.unprocessable("invalid_transaction", "Leg " + line + ": currency must be ISO 4217");
        }
        return v;
    }

    // ------------------------------------------------------------------ evaluation

    public record CustomerResult(List<Raised> raised, Map<String, Integer> wouldAlert) {}

    /**
     * Evaluates scenarios for one customer over [from, to]. Real runs raise alerts (skipping periods an alert of the
     * same scenario already covers); backtests only count would-be alerts.
     */
    public CustomerResult evaluateCustomer(UUID customerId, List<ScenarioCatalog.Active> list, Instant from, Instant to,
                                           UUID runId, boolean backtest) {
        List<Raised> raised = new ArrayList<>();
        Map<String, Integer> would = new TreeMap<>();
        if (list.isEmpty()) {
            return new CustomerResult(raised, would);
        }
        int history = list.stream().mapToInt(a -> Scenarios.historyDays(a.type(), a.params())).max().orElse(30);
        Instant historyStart = from.minus(Duration.ofDays(history));
        List<Scenarios.Txn> txns = new ArrayList<>(store.customerTransactions(customerId, historyStart, to));
        if (list.stream().anyMatch(a -> "DORMANT_REACTIVATION".equals(a.type()))) {
            // dormancy is measured from the last activity, however old: add the latest leg before the loaded history
            store.lastTransactionBefore(customerId, historyStart).ifPresent(t -> txns.addFirst(t));
        }
        for (ScenarioCatalog.Active a : list) {
            Optional<Scenarios.Detection> d = Scenarios.evaluate(a.type(), a.params(), txns, from, to);
            if (d.isEmpty()) {
                continue;
            }
            if (backtest) {
                would.merge(a.code() + "@v" + a.version(), 1, Integer::sum);
                continue;
            }
            Scenarios.Detection det = d.get();
            if (store.overlappingAlert(a.scenarioId(), customerId, det.periodStart(), det.periodEnd())) {
                continue;
            }
            raised.add(raise(a, customerId, det, runId));
        }
        return new CustomerResult(raised, would);
    }

    private Raised raise(ScenarioCatalog.Active a, UUID customerId, Scenarios.Detection d, UUID runId) {
        ObjectNode alert = json.createObjectNode();
        UUID id = Ids.newId();
        alert.put("id", id.toString());
        alert.put("tm_scenario_id", a.scenarioId().toString());
        alert.put("tm_scenario_version_id", a.versionId().toString());
        if (runId != null) {
            alert.put("tm_run_id", runId.toString());
        }
        alert.put("customer_id", customerId.toString());
        Set<UUID> accounts = new LinkedHashSet<>();
        d.contributing().forEach(t -> {
            if (t.accountId() != null) {
                accounts.add(t.accountId());
            }
        });
        if (accounts.size() == 1) {
            alert.put("account_id", accounts.iterator().next().toString());
        }
        alert.put("score", d.score());
        alert.put("severity", Scenarios.severity(d.score()));
        ObjectNode reason = json.valueToTree(d.reason());
        reason.put("scenario_code", a.code());
        reason.put("scenario_type", a.type());
        reason.put("scenario_version", a.version());
        alert.set("reason", reason);
        alert.put("total_amount", d.totalAmount().toPlainString());
        alert.put("currency", d.currency());
        alert.put("transaction_count", d.contributing().size());
        alert.put("period_start", d.periodStart().toString());
        alert.put("period_end", d.periodEnd().toString());
        ArrayNode txns = json.createArrayNode();
        for (Scenarios.Txn t : d.contributing()) {
            ObjectNode n = txns.addObject();
            n.put("transaction_type", t.transactionType());
            n.put("transaction_id", t.transactionId().toString());
            if (t.journalEntryId() != null) {
                n.put("journal_entry_id", t.journalEntryId().toString());
            }
            n.put("direction", t.direction());
            n.put("amount", t.amount().toPlainString());
            n.put("currency", t.currency());
            n.put("occurred_at", t.occurredAt().toString());
            if (t.counterpartyName() != null) {
                n.put("counterparty_name", t.counterpartyName());
            }
            if (t.counterpartyCountry() != null) {
                n.put("counterparty_country", t.counterpartyCountry());
            }
            n.put("contribution", d.totalAmount().signum() == 0 ? "0"
                    : t.amount().divide(d.totalAmount(), MathContext.DECIMAL64).setScale(6, RoundingMode.HALF_EVEN).toPlainString());
        }
        store.insertAlert(alert, txns, TenantContext.principal());
        if (MULE_TYPES.contains(a.type()) || "FAN_OUT".equals(a.type())) {
            flagMule(customerId, d.score());
        }
        return new Raised(id, a.code(), customerId, d.score());
    }

    /** Marks the customer's graph node as a mule suspect (event only on the first flag). */
    void flagMule(UUID customerId, int score) {
        db.update("""
                insert into risk_graph_entity (tenant_id, created_by, updated_by, entity_type, entity_ref, customer_id, last_seen_at)
                values (current_setting('app.tenant_id')::uuid, ?, ?, 'CUSTOMER', ?, ?, now())
                on conflict (tenant_id, entity_type, entity_ref) do nothing""",
                TenantContext.principal(), TenantContext.principal(), customerId.toString(), customerId);
        var flagged = db.one("""
                update risk_graph_entity set is_mule_suspect = true, risk_score = greatest(risk_score, ?)
                where entity_type = 'CUSTOMER' and entity_ref = ? and not is_mule_suspect
                returning row_to_json(risk_graph_entity)::text""", score, customerId.toString());
        flagged.ifPresent(n -> outbox.publish("risk.graph_entity.mule_suspected", "risk_graph_entity",
                UUID.fromString(n.get("id").asString()), n));
        if (flagged.isEmpty()) {
            db.update("update risk_graph_entity set risk_score = greatest(risk_score, ?) where entity_type = 'CUSTOMER' and entity_ref = ?",
                    score, customerId.toString());
        }
    }

    // ------------------------------------------------------------------ graph

    /** Customer owns account; transfers link the customer's account (or the customer) and the counterparty. */
    void updateGraph(Map<UUID, List<Scenarios.Txn>> byCustomer) {
        Map<String, ObjectNode> entities = new TreeMap<>();
        Map<String, ObjectNode> links = new TreeMap<>();
        for (Map.Entry<UUID, List<Scenarios.Txn>> e : byCustomer.entrySet()) {
            String customer = e.getKey().toString();
            for (Scenarios.Txn t : e.getValue()) {
                entity(entities, "CUSTOMER", customer, customer, t.occurredAt());
                String ownType = "CUSTOMER";
                String ownRef = customer;
                if (t.accountId() != null) {
                    entity(entities, "ACCOUNT", t.accountId().toString(), customer, t.occurredAt());
                    link(links, "CUSTOMER", customer, "ACCOUNT", t.accountId().toString(), "OWNS", null, t.occurredAt());
                    ownType = "ACCOUNT";
                    ownRef = t.accountId().toString();
                }
                String cp = t.counterpartyAccount() != null ? t.counterpartyAccount()
                        : t.counterpartyName() != null ? t.counterpartyName().trim().toUpperCase(java.util.Locale.ROOT) : null;
                if (cp == null) {
                    continue;
                }
                String cpType = t.counterpartyAccount() != null ? "ACCOUNT" : "BENEFICIARY";
                entity(entities, cpType, cp, null, t.occurredAt());
                if (t.credit()) {
                    link(links, cpType, cp, ownType, ownRef, "TRANSFER", t.amount(), t.occurredAt());
                } else {
                    link(links, ownType, ownRef, cpType, cp, "TRANSFER", t.amount(), t.occurredAt());
                }
            }
        }
        ArrayNode ea = json.createArrayNode();
        entities.values().forEach(ea::add);
        ArrayNode la = json.createArrayNode();
        links.values().forEach(la::add);
        store.upsertGraph(ea, la, TenantContext.principal());
    }

    private void entity(Map<String, ObjectNode> m, String type, String ref, String customer, Instant seen) {
        ObjectNode n = m.computeIfAbsent(type + "|" + ref, k -> {
            ObjectNode x = json.createObjectNode();
            x.put("entity_type", type);
            x.put("entity_ref", ref.length() > 128 ? ref.substring(0, 128) : ref);
            if (customer != null) {
                x.put("customer_id", customer);
            }
            x.put("last_seen_at", seen.toString());
            return x;
        });
        if (Instant.parse(n.get("last_seen_at").asString()).isBefore(seen)) {
            n.put("last_seen_at", seen.toString());
        }
    }

    private void link(Map<String, ObjectNode> m, String ft, String fr, String tt, String tr, String type, BigDecimal amount,
                      Instant at) {
        String fRef = fr.length() > 128 ? fr.substring(0, 128) : fr;
        String tRef = tr.length() > 128 ? tr.substring(0, 128) : tr;
        ObjectNode n = m.computeIfAbsent(ft + "|" + fRef + "|" + tt + "|" + tRef + "|" + type, k -> {
            ObjectNode x = json.createObjectNode();
            x.put("from_type", ft);
            x.put("from_ref", fRef);
            x.put("to_type", tt);
            x.put("to_ref", tRef);
            x.put("link_type", type);
            x.put("cnt", 0);
            x.put("amount", "0");
            x.put("first", at.toString());
            x.put("last", at.toString());
            return x;
        });
        if (amount != null) {
            n.put("cnt", n.get("cnt").asInt() + 1);
            n.put("amount", new BigDecimal(n.get("amount").asString()).add(amount).toPlainString());
        }
        if (Instant.parse(n.get("first").asString()).isAfter(at)) {
            n.put("first", at.toString());
        }
        if (Instant.parse(n.get("last").asString()).isBefore(at)) {
            n.put("last", at.toString());
        }
    }
}
