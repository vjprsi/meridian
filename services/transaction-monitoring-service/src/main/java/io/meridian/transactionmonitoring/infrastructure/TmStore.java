package io.meridian.transactionmonitoring.infrastructure;

import io.meridian.transactionmonitoring.domain.Scenarios;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Set-based SQL for the monitoring feed, alerts and the risk graph. */
@Component
public class TmStore {
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public TmStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Inserts transaction legs; legs already ingested (same transaction, customer and direction) are skipped. */
    public List<JsonNode> insertTransactions(ArrayNode rows, String principal) {
        return jdbc.sql("""
                insert into tm_transaction as t (tenant_id, created_by, transaction_id, transaction_type, customer_id,
                    account_id, journal_entry_id, direction, amount, currency, is_cash, channel, counterparty_name,
                    counterparty_account, counterparty_country, occurred_at)
                select current_setting('app.tenant_id')::uuid, cast(:p as text), r.transaction_id, r.transaction_type,
                    r.customer_id, r.account_id, r.journal_entry_id, r.direction, r.amount, r.currency,
                    coalesce(r.is_cash, false), r.channel, r.counterparty_name, r.counterparty_account,
                    r.counterparty_country, r.occurred_at
                from json_populate_recordset(null::tm_transaction, cast(:rows as json)) r
                on conflict (tenant_id, transaction_id, customer_id, direction) do nothing
                returning row_to_json(t)::text""")
                .param("p", principal)
                .param("rows", json.writeValueAsString(rows))
                .query(String.class).list().stream().map(json::readTree).toList();
    }

    /** A customer's legs since {@code from} (inclusive), oldest first. */
    public List<Scenarios.Txn> customerTransactions(UUID customerId, Instant from, Instant to) {
        return jdbc.sql("""
                select row_to_json(t)::text from tm_transaction t
                where customer_id = ? and occurred_at >= ? and occurred_at <= ? order by occurred_at, id""")
                .params(customerId, OffsetDateTime.ofInstant(from, java.time.ZoneOffset.UTC),
                        OffsetDateTime.ofInstant(to, java.time.ZoneOffset.UTC))
                .query(String.class).list().stream().map(s -> txn(json.readTree(s))).toList();
    }

    public java.util.Optional<Scenarios.Txn> lastTransactionBefore(UUID customerId, Instant before) {
        return jdbc.sql("""
                select row_to_json(t)::text from tm_transaction t
                where customer_id = ? and occurred_at < ? order by occurred_at desc limit 1""")
                .params(customerId, OffsetDateTime.ofInstant(before, java.time.ZoneOffset.UTC))
                .query(String.class).optional().map(s -> txn(json.readTree(s)));
    }

    public static Scenarios.Txn txn(JsonNode t) {
        return new Scenarios.Txn(UUID.fromString(t.get("id").asString()), UUID.fromString(t.get("transaction_id").asString()),
                t.get("transaction_type").asString(), Db.uuid(t, "account_id"), Db.uuid(t, "journal_entry_id"),
                t.get("direction").asString(), new BigDecimal(t.get("amount").asString()), t.get("currency").asString().trim(),
                t.path("is_cash").asBoolean(false), Db.str(t, "counterparty_name"), Db.str(t, "counterparty_account"),
                Db.str(t, "counterparty_country"), OffsetDateTime.parse(t.get("occurred_at").asString()).toInstant());
    }

    /** True when an alert of the scenario for the customer already covers part of the period. */
    public boolean overlappingAlert(UUID scenarioId, UUID customerId, Instant start, Instant end) {
        return jdbc.sql("""
                select count(*) from tm_alert where tm_scenario_id = ? and customer_id = ?
                  and period_start <= ? and period_end >= ?""")
                .params(scenarioId, customerId, OffsetDateTime.ofInstant(end, java.time.ZoneOffset.UTC),
                        OffsetDateTime.ofInstant(start, java.time.ZoneOffset.UTC))
                .query(Integer.class).single() > 0;
    }

    /** Inserts an alert, its contributing transactions and the raised event in one statement. */
    public JsonNode insertAlert(ObjectNode alert, ArrayNode transactions, String principal) {
        String headers = json.writeValueAsString(java.util.Map.of("ce_principal", principal));
        String row = jdbc.sql("""
                with a as (
                  insert into tm_alert (id, tenant_id, created_by, updated_by, tm_scenario_id, tm_scenario_version_id,
                      tm_run_id, customer_id, account_id, score, severity, status, reason, total_amount, currency,
                      transaction_count, period_start, period_end)
                  select r.id, current_setting('app.tenant_id')::uuid, cast(:p as text), cast(:p as text), r.tm_scenario_id,
                      r.tm_scenario_version_id, r.tm_run_id, r.customer_id, r.account_id, r.score, r.severity, 'OPEN',
                      r.reason, r.total_amount, r.currency, r.transaction_count, r.period_start, r.period_end
                  from json_populate_record(null::tm_alert, cast(:alert as json)) r
                  returning *
                ),
                x as (
                  insert into tm_alert_transaction (tenant_id, created_by, tm_alert_id, transaction_type, transaction_id,
                      journal_entry_id, direction, amount, currency, occurred_at, counterparty_name, counterparty_country,
                      contribution)
                  select current_setting('app.tenant_id')::uuid, cast(:p as text), a.id, r.transaction_type, r.transaction_id,
                      r.journal_entry_id, r.direction, r.amount, r.currency, r.occurred_at, r.counterparty_name,
                      r.counterparty_country, r.contribution
                  from json_populate_recordset(null::tm_alert_transaction, cast(:txns as json)) r, a
                  returning 1
                ),
                o as (
                  insert into outbox (tenant_id, aggregate_type, aggregate_id, event_type, payload, headers)
                  select current_setting('app.tenant_id')::uuid, 'tm_alert', a.id, 'risk.tm_alert.raised', to_jsonb(a),
                         cast(:headers as jsonb) from a
                  returning 1
                )
                select row_to_json(a)::text from a""")
                .param("p", principal)
                .param("alert", json.writeValueAsString(alert))
                .param("txns", json.writeValueAsString(transactions))
                .param("headers", headers)
                .query(String.class).single();
        return json.readTree(row);
    }

    /**
     * Upserts graph nodes {entity_type, entity_ref, customer_id, last_seen_at} (sorted, de-duplicated by the caller)
     * and links {from_type, from_ref, to_type, to_ref, link_type, cnt, amount, first, last}.
     */
    public List<JsonNode> upsertGraph(ArrayNode entities, ArrayNode links, String principal) {
        if (!entities.isEmpty()) {
            jdbc.sql("""
                    insert into risk_graph_entity as e (tenant_id, created_by, updated_by, entity_type, entity_ref, customer_id,
                        last_seen_at)
                    select current_setting('app.tenant_id')::uuid, cast(:p as text), cast(:p as text), r.entity_type,
                        r.entity_ref, r.customer_id, r.last_seen_at
                    from json_to_recordset(cast(:rows as json)) as r(entity_type text, entity_ref text, customer_id uuid,
                        last_seen_at timestamptz)
                    order by r.entity_type, r.entity_ref
                    on conflict (tenant_id, entity_type, entity_ref) do update set
                        customer_id = coalesce(e.customer_id, excluded.customer_id),
                        last_seen_at = greatest(e.last_seen_at, excluded.last_seen_at)""")
                    .param("p", principal).param("rows", json.writeValueAsString(entities)).update();
        }
        if (links.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                insert into risk_graph_link as l (tenant_id, created_by, updated_by, from_entity_id, to_entity_id, link_type,
                    transaction_count, total_amount, first_seen_at, last_seen_at)
                select current_setting('app.tenant_id')::uuid, cast(:p as text), cast(:p as text), f.id, t.id, r.link_type,
                    r.cnt, r.amount, r.first, r.last
                from json_to_recordset(cast(:rows as json)) as r(from_type text, from_ref text, to_type text, to_ref text,
                    link_type text, cnt int, amount numeric, first timestamptz, last timestamptz)
                join risk_graph_entity f on f.entity_type = r.from_type and f.entity_ref = r.from_ref
                join risk_graph_entity t on t.entity_type = r.to_type and t.entity_ref = r.to_ref
                order by f.id, t.id
                on conflict (from_entity_id, to_entity_id, link_type) do update set
                    transaction_count = l.transaction_count + excluded.transaction_count,
                    total_amount = l.total_amount + excluded.total_amount,
                    first_seen_at = least(l.first_seen_at, excluded.first_seen_at),
                    last_seen_at = greatest(l.last_seen_at, excluded.last_seen_at)
                returning row_to_json(l)::text""")
                .param("p", principal).param("rows", json.writeValueAsString(links))
                .query(String.class).list().stream().map(json::readTree).toList();
    }
}
