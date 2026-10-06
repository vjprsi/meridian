package io.meridian.cardauthorization.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * SQL for the authorisation service beyond generic CRUD. Rows travel as JSON in API shape ({@code row_to_json});
 * writes go through {@link CrudEngine} (tenant, audit columns). RLS scopes every statement to the tenant.
 */
@Component
public class AuthStore {
    private static final Pattern IDENT = Pattern.compile("[a-z][a-z0-9_]*");

    private final JdbcClient jdbc;
    private final CrudEngine crud;
    private final JsonMapper json;
    private final io.meridian.web.crud.TableCatalog catalog;

    public AuthStore(JdbcClient jdbc, CrudEngine crud, JsonMapper json, io.meridian.web.crud.TableCatalog catalog) {
        this.jdbc = jdbc;
        this.crud = crud;
        this.json = json;
        this.catalog = catalog;
    }

    /** A counter increment for {@link #insertDecided}. */
    public record CounterDelta(UUID cardId, String limitRef, String period, String channel, OffsetDateTime periodStart,
                               String currency, BigDecimal amount, int count) {}

    /**
     * Hot-path write: inserts the decided authorisation, its outbox event and the counter increments in ONE
     * statement (data-modifying CTEs are atomic), i.e. one round trip instead of a multi-statement transaction.
     * The outbox row has exactly the shape {@code OutboxWriter} writes.
     */
    public ObjectNode insertDecided(ObjectNode row, String eventType, List<CounterDelta> deltas) {
        String tenant = TenantContext.require().toString();
        String principal = TenantContext.principal();
        if (!row.has("id")) {
            row.put("id", Ids.newId().toString());
        }
        row.put("tenant_id", tenant);
        row.put("created_by", principal);
        row.put("updated_by", principal);
        List<String> cols = new java.util.ArrayList<>();
        row.propertyNames().forEach(n -> {
            if (catalog.has("card_authorization", n)) {
                cols.add(ident(n));
            }
        });
        tools.jackson.databind.node.ArrayNode counters = json.createArrayNode();
        for (CounterDelta d : deltas) {
            ObjectNode c = counters.addObject();
            c.put("id", Ids.newId().toString());
            c.put("tenant_id", tenant);
            c.put("created_by", principal);
            c.put("updated_by", principal);
            c.put("card_id", d.cardId().toString());
            c.put("limit_ref", d.limitRef());
            c.put("period", d.period());
            c.put("channel", d.channel() == null ? "ANY" : d.channel());
            c.put("period_start", d.periodStart().toString());
            c.put("currency", d.currency());
            c.put("used_amount", d.amount().toPlainString());
            c.put("used_count", d.count());
        }
        String colList = String.join(", ", cols);
        String sel = String.join(", ", cols.stream().map(c -> "r." + c).toList());
        String sql = "with a as (insert into card_authorization as t (" + colList + ") select " + sel
                + " from json_populate_record(null::card_authorization, ?::json) r returning t.*),"
                + " o as (insert into outbox (id, tenant_id, aggregate_type, aggregate_id, event_type, payload, headers)"
                + " select ?::uuid, a.tenant_id, 'card_authorization', a.id, ?, row_to_json(a)::jsonb, ?::jsonb from a),"
                + " c as (insert into card_velocity_counter as v (id, tenant_id, created_by, updated_by, card_id, limit_ref,"
                + " period, channel, period_start, currency, used_amount, used_count)"
                + " select x.id, x.tenant_id, x.created_by, x.updated_by, x.card_id, x.limit_ref, x.period, x.channel,"
                + " x.period_start, x.currency, x.used_amount, x.used_count"
                + " from json_populate_recordset(null::card_velocity_counter, ?::json) x"
                + " on conflict (tenant_id, card_id, limit_ref, period_start) do update"
                + " set used_amount = v.used_amount + excluded.used_amount, used_count = v.used_count + excluded.used_count)"
                + " select row_to_json(a)::text from a";
        String headers = json.writeValueAsString(Map.of("ce_principal", principal));
        return parse(jdbc.sql(sql).params(json.writeValueAsString(row), Ids.newId().toString(), eventType, headers,
                json.writeValueAsString(counters)).query(String.class).single());
    }

    public JsonMapper json() {
        return json;
    }

    public CrudEngine crud() {
        return crud;
    }

    /** Database clock plus all velocity counters of a card in one round trip. */
    public record Snapshot(OffsetDateTime now, List<ObjectNode> counters) {}

    public Snapshot nowAndCounters(UUID cardId) {
        String text = jdbc.sql("""
                select json_build_object('now', now(), 'counters',
                  coalesce((select json_agg(row_to_json(c)) from card_velocity_counter c where c.card_id = ?), '[]'::json))::text""")
                .param(cardId).query(String.class).single();
        JsonNode n = json.readTree(text);
        List<ObjectNode> counters = new java.util.ArrayList<>();
        n.get("counters").forEach(c -> counters.add((ObjectNode) c));
        return new Snapshot(OffsetDateTime.parse(n.get("now").asString()), counters);
    }

    public OffsetDateTime now() {
        return OffsetDateTime.parse(jdbc.sql("select to_json(now())::text").query(String.class).single().replace("\"", ""));
    }

    public List<ObjectNode> counters(UUID cardId) {
        return query("select row_to_json(c)::text from card_velocity_counter c where card_id = ? order by limit_ref, period_start", cardId);
    }

    /** Adds amount / count to a counter (insert or update in one statement). */
    public void addToCounter(UUID cardId, String limitRef, String period, String channel, OffsetDateTime periodStart,
                             String currency, BigDecimal amount, int count) {
        jdbc.sql("""
                insert into card_velocity_counter (id, tenant_id, created_by, updated_by, card_id, limit_ref, period, channel,
                  period_start, currency, used_amount, used_count)
                values (?::uuid, ?::uuid, ?, ?, ?::uuid, ?, ?, ?, ?, ?, ?, ?)
                on conflict (tenant_id, card_id, limit_ref, period_start) do update
                  set used_amount = card_velocity_counter.used_amount + excluded.used_amount,
                      used_count = card_velocity_counter.used_count + excluded.used_count""")
                .params(Ids.newId().toString(), TenantContext.require().toString(), TenantContext.principal(),
                        TenantContext.principal(), cardId.toString(), limitRef, period, channel == null ? "ANY" : channel,
                        periodStart, currency, amount, count)
                .update();
    }

    /** Subtracts amount / count from a counter row (never below zero). */
    public void subtractFromCounter(UUID counterId, BigDecimal amount, int count) {
        jdbc.sql("""
                update card_velocity_counter set used_amount = greatest(used_amount - ?, 0),
                  used_count = greatest(used_count - ?, 0) where id = ?""")
                .params(amount, count, counterId).update();
    }

    public ObjectNode insert(String table, ObjectNode values) {
        if (!values.has("id")) {
            values.put("id", Ids.newId().toString());
        }
        return parse(crud.insert(table, values));
    }

    public ObjectNode update(String table, UUID id, ObjectNode values) {
        return crud.updateRow(table, Map.of("id", id), values, null).map(this::parse)
                .orElseThrow(() -> ApiException.notFound(table.replace('_', ' '), id));
    }

    public ObjectNode lock(String table, UUID id) {
        return queryOne("select row_to_json(t)::text from " + ident(table) + " t where id = ? for update", id)
                .orElseThrow(() -> ApiException.notFound(table.replace('_', ' '), id));
    }

    public Optional<ObjectNode> find(String table, UUID id) {
        return queryOne("select row_to_json(t)::text from " + ident(table) + " t where id = ?", id);
    }

    public ObjectNode get(String table, UUID id) {
        return find(table, id).orElseThrow(() -> ApiException.notFound(table.replace('_', ' '), id));
    }

    public Optional<ObjectNode> queryOne(String sql, Object... params) {
        return jdbc.sql(sql).params(Arrays.asList(params)).query(String.class).optional().map(this::parse);
    }

    public List<ObjectNode> query(String sql, Object... params) {
        return jdbc.sql(sql).params(Arrays.asList(params)).query(String.class).list().stream().map(this::parse).toList();
    }

    public int execute(String sql, Object... params) {
        return jdbc.sql(sql).params(Arrays.asList(params)).update();
    }

    public ObjectNode parse(String text) {
        return (ObjectNode) json.readTree(text);
    }

    private static String ident(String name) {
        if (!IDENT.matcher(name).matches()) {
            throw new IllegalArgumentException("illegal identifier " + name);
        }
        return name;
    }
}
