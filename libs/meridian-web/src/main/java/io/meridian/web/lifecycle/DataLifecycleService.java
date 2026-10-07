package io.meridian.web.lifecycle;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.lifecycle.PersonalDataCatalog.TableInfo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Data-lifecycle operations every data-owning service offers to archive-service and to the DSAR orchestration in
 * customer-service (GAP-08), generic over the service's own tables:
 * <ul>
 *   <li><b>count / extract / confirm</b>: archive runs. Extract returns aged rows (created before the cutoff, legal-hold
 *       exclusions applied) in id order; confirm removes exactly the confirmed batch from the online store once the
 *       archive object is written (append-only tables stay online);</li>
 *   <li><b>purge</b>: end of retention, {@code PURGE} (delete) or {@code ANONYMISE} (overwrite the spec's pii columns);</li>
 *   <li><b>export</b>: every row linked to a customer (DSAR access), hidden / secret columns left out;</li>
 *   <li><b>erase</b>: anonymises the customer's pii columns, except where a retention obligation applies
 *       ({@code meridian.data-lifecycle.retain-on-erasure.<table>: <reason>} while the request says the customer is
 *       within the statutory retention period) or the table is append-only; both are reported, never silently skipped.</li>
 * </ul>
 * All statements run under row-level security for the tenant bound by the caller.
 */
public class DataLifecycleService {
    static final int MAX_EXTRACT = 5_000;
    static final int MAX_EXPORT_ROWS_PER_TABLE = 10_000;
    static final String ACTOR = "data-lifecycle";

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final PersonalDataCatalog catalog;
    private final String service;
    private final Map<String, String> retainOnErasure;
    private final Map<String, Map<String, Column>> columns = new ConcurrentHashMap<>();

    /** Column metadata from information_schema. */
    record Column(String name, String type, boolean nullable, Integer maxLength, boolean array) {}

    public DataLifecycleService(JdbcClient jdbc, TransactionTemplate tx, JsonMapper json, PersonalDataCatalog catalog,
                                String service, Map<String, String> retainOnErasure) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
        this.catalog = catalog;
        this.service = service;
        this.retainOnErasure = Map.copyOf(retainOnErasure);
    }

    public PersonalDataCatalog catalog() {
        return catalog;
    }

    // ------------------------------------------------------------------ archive

    public ObjectNode count(JsonNode body) {
        TenantContext.require();
        String table = table(body);
        Selection sel = Selection.from(body);
        Selection.Sql p = predicate(table, sel);
        long n = jdbc.sql("select count(*) from " + table + " t where true" + p.where()).params(p.args()).query(Long.class).single();
        ObjectNode out = json.createObjectNode();
        out.put("table_name", table);
        out.put("count", n);
        return out;
    }

    public ObjectNode extract(JsonNode body) {
        TenantContext.require();
        String table = table(body);
        Selection sel = Selection.from(body);
        if (sel.createdBefore() == null) {
            throw ApiException.unprocessable("created_before_required", "created_before (the archive cutoff) is required");
        }
        int limit = Math.max(1, Math.min(MAX_EXTRACT, body.path("limit").asInt(1000)));
        UUID after = uuid(body, "after_id");
        Selection.Sql p = predicate(table, sel);
        List<Object> args = new ArrayList<>(p.args());
        String afterClause = "";
        if (after != null) {
            afterClause = " and t.id > ?";
            args.add(after);
        }
        args.add(limit + 1);
        List<String> rows = jdbc.sql("select row_to_json(t)::text from " + table + " t where true" + p.where() + afterClause
                + " order by t.id limit ?").params(args).query(String.class).list();
        ObjectNode out = json.createObjectNode();
        ArrayNode records = out.putArray("records");
        rows.stream().limit(limit).forEach(r -> records.add(json.readTree(r)));
        out.put("has_more", rows.size() > limit);
        long skipped = 0;
        if (after == null && body.path("exclude").isObject() && !body.path("exclude").isEmpty()) {
            // rows of the same age that legal holds keep out of the archive (reported once, on the first batch)
            Selection.Sql all = predicate(table, Selection.from(withoutExclusions(body)));
            long total = jdbc.sql("select count(*) from " + table + " t where true" + all.where()).params(all.args())
                    .query(Long.class).single();
            long selected = jdbc.sql("select count(*) from " + table + " t where true" + p.where()).params(p.args())
                    .query(Long.class).single();
            skipped = Math.max(0, total - selected);
        }
        out.put("skipped_hold", skipped);
        return out;
    }

    /**
     * The batch {@code first_record_id..last_record_id} is safely archived: removes exactly the rows extract returned
     * (same cutoff and exclusions); refuses (409) when the count differs, i.e. the data changed since extraction.
     */
    public ObjectNode confirm(JsonNode body) {
        TenantContext.require();
        String table = table(body);
        UUID first = requireUuid(body, "first_record_id");
        UUID last = requireUuid(body, "last_record_id");
        long expected = body.path("record_count").asLong(-1);
        Selection sel = Selection.from(body);
        Selection.Sql p = predicate(table, sel);
        ObjectNode out = json.createObjectNode();
        out.put("table_name", table);
        out.put("archived_object_id", body.path("archived_object_id").asString(null));
        if (catalog.table(table).map(TableInfo::immutable).orElse(false) || appendOnly(table)) {
            out.put("removed", 0);
            out.put("retained_online", true);
            out.put("reason", "append_only");
            return out;
        }
        if (sel.createdBefore() == null) {
            out.put("removed", 0);
            out.put("retained_online", true);
            out.put("reason", "no_cutoff");     // older callers: without the cutoff the batch cannot be re-selected safely
            return out;
        }
        List<Object> args = new ArrayList<>(p.args());
        args.add(first);
        args.add(last);
        try {
            Integer removed = tx.execute(s -> {
                int n = jdbc.sql("delete from " + table + " t where true" + p.where() + " and t.id between ? and ?")
                        .params(args).update();
                if (expected >= 0 && n != expected) {
                    s.setRollbackOnly();
                    return -1;
                }
                return n;
            });
            if (removed == null || removed < 0) {
                throw ApiException.conflict("confirm_mismatch", "The confirmed batch of " + table
                        + " no longer matches the online rows; nothing was removed");
            }
            out.put("removed", removed);
            out.put("retained_online", false);
        } catch (DataIntegrityViolationException e) {
            out.put("removed", 0);
            out.put("retained_online", true);
            out.put("reason", "referenced_by_other_rows");
        }
        return out;
    }

    /** End of retention: {@code PURGE} deletes, {@code ANONYMISE} overwrites the pii columns of the selected rows. */
    public ObjectNode purge(JsonNode body) {
        TenantContext.require();
        String table = table(body);
        String action = body.path("action").asString("PURGE");
        Selection sel = Selection.from(body);
        if (!sel.restricted()) {
            throw ApiException.unprocessable("selector_required",
                    "record_selector must restrict the rows (created_before or ids); a whole table is never purged");
        }
        Selection.Sql p = predicate(table, sel);
        long n;
        try {
            n = switch (action) {
                case "PURGE" -> tx.execute(s -> (long) jdbc.sql("delete from " + table + " t where true" + p.where())
                        .params(p.args()).update());
                case "ANONYMISE" -> {
                    String set = anonymiseSet(table);
                    if (set == null) {
                        throw ApiException.unprocessable("no_personal_data", "Table " + table + " has no pii columns to anonymise");
                    }
                    yield tx.execute(s -> (long) jdbc.sql("update " + table + " t set " + set + " where true" + p.where())
                            .params(p.args()).update());
                }
                default -> throw ApiException.unprocessable("invalid_action", "action must be PURGE or ANONYMISE");
            };
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("referenced_rows", "Rows of " + table + " are still referenced; purge the dependent data first");
        } catch (DataAccessException e) {
            if (appendOnlyViolation(e)) {
                throw ApiException.conflict("append_only", "Table " + table + " is append-only; its rows cannot be "
                        + ("PURGE".equals(action) ? "deleted" : "changed"));
            }
            throw e;
        }
        ObjectNode out = json.createObjectNode();
        out.put("table_name", table);
        out.put("action", action);
        out.put("records_purged", n);
        return out;
    }

    // ------------------------------------------------------------------ data subject requests

    /** DSAR access: every row of this service linked to the customer, without hidden / secret columns. */
    public ObjectNode export(JsonNode body) {
        TenantContext.require();
        UUID customer = requireUuid(body, "customer_id");
        ObjectNode out = json.createObjectNode();
        out.put("service", service);
        out.put("customer_id", customer.toString());
        out.put("generated_at", io.meridian.common.time.Clocks.now().toString());
        ObjectNode tables = out.putObject("tables");
        ArrayNode truncated = out.putArray("truncated");
        long total = 0;
        for (TableInfo info : catalog.subjectTables()) {
            if (!exists(info.table())) {
                continue;
            }
            Selection.Sql s = subject(info.table(), customer);
            if (s == null) {
                continue;
            }
            List<Object> args = new ArrayList<>(s.args());
            args.add(MAX_EXPORT_ROWS_PER_TABLE + 1);
            List<String> rows = jdbc.sql("select row_to_json(t)::text from " + info.table() + " t where true" + s.where()
                    + " order by t.id limit ?").params(args).query(String.class).list();
            if (rows.isEmpty()) {
                continue;
            }
            ArrayNode arr = tables.putArray(info.table());
            rows.stream().limit(MAX_EXPORT_ROWS_PER_TABLE).forEach(r -> {
                ObjectNode row = (ObjectNode) json.readTree(r);
                info.hidden().forEach(row::remove);
                row.remove("tenant_id");
                arr.add(row);
            });
            total += arr.size();
            if (rows.size() > MAX_EXPORT_ROWS_PER_TABLE) {
                truncated.add(info.table());
            }
        }
        out.put("record_count", total);
        return out;
    }

    /**
     * DSAR erasure: anonymises the customer's pii columns in every linked table, unless the table is append-only or a
     * retention obligation applies ({@code within_retention}: the orchestrator decided the statutory retention period
     * of the relationship has not ended). Idempotent.
     */
    public ObjectNode erase(JsonNode body) {
        TenantContext.require();
        UUID customer = requireUuid(body, "customer_id");
        boolean withinRetention = body.path("within_retention").asBoolean(true);
        ObjectNode out = json.createObjectNode();
        out.put("service", service);
        out.put("customer_id", customer.toString());
        ArrayNode results = out.putArray("tables");
        long anonymised = 0;
        long retained = 0;
        for (TableInfo info : catalog.subjectTables()) {
            if (info.pii().isEmpty() || !exists(info.table())) {
                continue;
            }
            Selection.Sql s = subject(info.table(), customer);
            if (s == null) {
                continue;
            }
            ObjectNode r = results.addObject();
            r.put("table", info.table());
            String reason = retainOnErasure.get(info.table());
            if (info.immutable() || appendOnly(info.table())) {
                long n = count(info.table(), s);
                r.put("action", "RETAINED").put("records", n).put("lawful_basis", "LEGAL_OBLIGATION")
                        .put("reason", "Append-only record (" + info.table() + "): kept unchanged for its retention period");
                retained += n;
            } else if (reason != null && withinRetention) {
                long n = count(info.table(), s);
                r.put("action", "RETAINED").put("records", n).put("lawful_basis", "LEGAL_OBLIGATION").put("reason", reason);
                retained += n;
            } else {
                String set = anonymiseSet(info.table());
                long n = tx.execute(x -> (long) jdbc.sql("update " + info.table() + " t set " + set + " where true" + s.where())
                        .params(s.args()).update());
                r.put("action", "ANONYMISED").put("records", n);
                anonymised += n;
            }
        }
        out.put("records_anonymised", anonymised);
        out.put("records_retained", retained);
        return out;
    }

    // ------------------------------------------------------------------ tenant moves (MR-GAME-DAY data mover)

    /** Tables the data mover never copies: transport and per-cell technical state, not tenant data. */
    static final Set<String> MOVE_EXCLUDED = Set.of("outbox", "idempotency_key", "callback_nonce", "flyway_schema_history");
    static final int MAX_IMPORT = 5_000;

    /**
     * The tenant's tables of this service for a cell-to-cell copy: every table of the schema with {@code id} and
     * {@code tenant_id} columns, parents before children (foreign keys), each with the tenant's row count, whether it
     * is append-only, and the schema version (both cells must run the same migrations).
     */
    public ObjectNode moveTables() {
        UUID tenant = TenantContext.require();
        List<String> tables = jdbc.sql("""
                select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = current_schema() and c.relkind in ('r', 'p')
                  and exists (select 1 from pg_attribute a where a.attrelid = c.oid and a.attname = 'id' and not a.attisdropped)
                  and exists (select 1 from pg_attribute a where a.attrelid = c.oid and a.attname = 'tenant_id' and not a.attisdropped)
                order by c.relname""").query(String.class).list().stream()
                .filter(t -> Selection.IDENT.matcher(t).matches() && !MOVE_EXCLUDED.contains(t)).toList();
        Map<String, Set<String>> parents = new LinkedHashMap<>();
        tables.forEach(t -> parents.put(t, new java.util.TreeSet<>()));
        jdbc.sql("""
                select ch.relname, pa.relname from pg_constraint k
                join pg_class ch on ch.oid = k.conrelid join pg_class pa on pa.oid = k.confrelid
                join pg_namespace n on n.oid = ch.relnamespace
                where k.contype = 'f' and n.nspname = current_schema()""")
                .query((rs, i) -> {
                    String child = rs.getString(1);
                    String parent = rs.getString(2);
                    if (parents.containsKey(child) && parents.containsKey(parent) && !child.equals(parent)) {
                        parents.get(child).add(parent);
                    }
                    return null;
                }).list();
        ObjectNode out = json.createObjectNode();
        out.put("service", service);
        out.put("tenant_id", tenant.toString());
        boolean flyway = Boolean.TRUE.equals(jdbc.sql("select to_regclass('flyway_schema_history') is not null")
                .query(Boolean.class).single());
        String version = null;
        if (flyway) {
            try {
                version = jdbc.sql("""
                        select version from flyway_schema_history where success and version is not null
                        order by installed_rank desc limit 1""").query(String.class).optional().orElse(null);
            } catch (DataAccessException e) {
                version = null;      // history not readable by the runtime role: no schema check
            }
        }
        if (version == null) {
            out.putNull("schema_version");
        } else {
            out.put("schema_version", version);
        }
        ArrayNode arr = out.putArray("tables");
        for (String t : topological(parents)) {
            ObjectNode n = arr.addObject();
            n.put("table_name", t);
            n.put("rows", jdbc.sql("select count(*) from " + t + " t where t.tenant_id = ?").param(tenant)
                    .query(Long.class).single());
            n.put("append_only", appendOnly(t));
            ArrayNode p = n.putArray("parents");
            parents.get(t).forEach(p::add);
        }
        return out;
    }

    /** Parents first; a cycle (rare: mutual references) keeps the remaining tables in name order. */
    static List<String> topological(Map<String, Set<String>> parents) {
        List<String> out = new ArrayList<>();
        Set<String> placed = new java.util.HashSet<>();
        boolean progressed = true;
        while (progressed && out.size() < parents.size()) {
            progressed = false;
            for (var e : parents.entrySet()) {
                if (!placed.contains(e.getKey()) && placed.containsAll(e.getValue())) {
                    out.add(e.getKey());
                    placed.add(e.getKey());
                    progressed = true;
                }
            }
        }
        parents.keySet().stream().filter(t -> !placed.contains(t)).forEach(out::add);
        return out;
    }

    /**
     * One page of the tenant's rows of a table in id order ({@code after_id}, {@code limit} up to 5,000), every column
     * (the copy must be exact), always restricted to the bound tenant even where a table has no RLS policy.
     */
    public ObjectNode moveExtract(JsonNode body) {
        UUID tenant = TenantContext.require();
        String table = moveTable(body);
        int limit = Math.max(1, Math.min(MAX_EXTRACT, body.path("limit").asInt(1000)));
        UUID after = uuid(body, "after_id");
        List<Object> args = new ArrayList<>();
        args.add(tenant);
        String afterClause = "";
        if (after != null) {
            afterClause = " and t.id > ?";
            args.add(after);
        }
        args.add(limit + 1);
        List<String> rows = jdbc.sql("select row_to_json(t)::text from " + table + " t where t.tenant_id = ?" + afterClause
                + " order by t.id limit ?").params(args).query(String.class).list();
        ObjectNode out = json.createObjectNode();
        out.put("table_name", table);
        ArrayNode records = out.putArray("records");
        rows.stream().limit(limit).forEach(r -> records.add(json.readTree(r)));
        out.put("has_more", rows.size() > limit);
        if (!records.isEmpty()) {
            out.put("last_id", records.get(records.size() - 1).path("id").asString());
        }
        return out;
    }

    /**
     * Bulk load of rows extracted from the source cell: {@code insert ... on conflict do nothing} (idempotent: a resumed
     * or repeated page inserts nothing twice and never overwrites). Every record must belong to the bound tenant
     * (row-level security checks it again). Rows whose parent row is not there yet (foreign key, e.g. a self-reference
     * to a later id) are inserted one by one where possible and the rest returned as {@code deferred} for a retry.
     */
    public ObjectNode moveImport(JsonNode body) {
        UUID tenant = TenantContext.require();
        String table = moveTable(body);
        JsonNode records = body.path("records");
        if (!records.isArray()) {
            throw ApiException.unprocessable("validation_failed", "records must be an array");
        }
        if (records.size() > MAX_IMPORT) {
            throw ApiException.unprocessable("too_many_records", "At most " + MAX_IMPORT + " records per import");
        }
        for (JsonNode r : records) {
            if (!r.isObject() || !tenant.toString().equals(r.path("tenant_id").asString(""))) {
                throw ApiException.unprocessable("tenant_mismatch", "Every record must belong to tenant " + tenant);
            }
            if (r.path("id").asString("").isBlank()) {
                throw ApiException.unprocessable("validation_failed", "Every record needs an id");
            }
        }
        ObjectNode out = json.createObjectNode();
        out.put("table_name", table);
        ArrayNode deferred = out.putArray("deferred");
        if (records.isEmpty()) {
            out.put("inserted", 0);
            out.put("existing", 0);
            return out;
        }
        String cols = String.join(", ", jdbc.sql("""
                select column_name from information_schema.columns
                where table_schema = current_schema() and table_name = ? and is_generated = 'NEVER'
                  and coalesce(identity_generation, '') <> 'ALWAYS' order by ordinal_position""")
                .param(table).query(String.class).list());
        String sql = "insert into " + table + " (" + cols + ") select " + cols
                + " from json_populate_recordset(null::" + table + ", ?::json) on conflict do nothing";
        int inserted;
        try {
            inserted = tx.execute(s -> jdbc.sql(sql).param(json.writeValueAsString(records)).update());
        } catch (DataIntegrityViolationException e) {
            if (!"23503".equals(sqlState(e))) {
                throw ApiException.conflict("import_rejected", "Rows of " + table + " were rejected: " + rootMessage(e));
            }
            inserted = tx.execute(s -> {
                int n = 0;
                for (JsonNode r : records) {
                    Object sp = s.createSavepoint();
                    try {
                        n += jdbc.sql(sql).param("[" + json.writeValueAsString(r) + "]").update();
                        s.releaseSavepoint(sp);
                    } catch (DataIntegrityViolationException fk) {
                        s.rollbackToSavepoint(sp);
                        if (!"23503".equals(sqlState(fk))) {
                            throw ApiException.conflict("import_rejected", "Row " + r.path("id").asString() + " of "
                                    + table + " was rejected: " + rootMessage(fk));
                        }
                        deferred.add(r.path("id").asString());
                    }
                }
                return n;
            });
        }
        out.put("inserted", inserted);
        out.put("existing", records.size() - inserted - deferred.size());
        return out;
    }

    private String moveTable(JsonNode body) {
        String t = table(body);
        if (MOVE_EXCLUDED.contains(t) || !columns(t).containsKey("tenant_id") || !columns(t).containsKey("id")) {
            throw ApiException.unprocessable("not_movable", "Table " + t + " is not tenant data of " + service);
        }
        return t;
    }

    private static String sqlState(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        String m = String.valueOf(t.getMessage());
        return m.length() > 300 ? m.substring(0, 300) : m;
    }

    // ------------------------------------------------------------------ helpers

    private long count(String table, Selection.Sql s) {
        return jdbc.sql("select count(*) from " + table + " t where true" + s.where()).params(s.args()).query(Long.class).single();
    }

    private Selection.Sql predicate(String table, Selection sel) {
        return sel.predicate(table, catalog.table(table).orElse(null), columns(table).keySet(),
                t -> exists(t) ? columns(t).keySet() : Set.of(), t -> catalog.table(t).orElse(null));
    }

    private Selection.Sql subject(String table, UUID customer) {
        return Selection.subject(table, catalog.table(table).orElse(null), columns(table).keySet(),
                t -> exists(t) ? columns(t).keySet() : Set.of(), t -> catalog.table(t).orElse(null), List.of(customer));
    }

    /** SET clause overwriting the table's pii columns, or null when it has none. */
    String anonymiseSet(String table) {
        TableInfo info = catalog.table(table).orElse(null);
        if (info == null || info.pii().isEmpty()) {
            return null;
        }
        Map<String, Column> cols = columns(table);
        List<String> parts = new ArrayList<>();
        for (String c : info.pii()) {
            Column col = cols.get(c);
            if (col != null) {
                parts.add(c + " = " + replacement(col));
            }
        }
        if (cols.containsKey("updated_by")) {
            parts.add("updated_by = '" + ACTOR + "'");
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    /** Overwrite value of a pii column: NULL where allowed, else a type-correct, per-row unique placeholder. */
    static String replacement(Column c) {
        if (c.nullable()) {
            return "null";
        }
        if (c.array()) {
            return "'{}'";
        }
        return switch (c.type()) {
            case "character varying", "text", "character" -> {
                int len = c.maxLength() == null ? 40 : Math.min(c.maxLength(), 40);
                yield "left('erased:' || md5(t.id::text), " + len + ")";
            }
            case "jsonb" -> "'{}'::jsonb";
            case "json" -> "'{}'::json";
            case "date" -> "date '1900-01-01'";
            case "timestamp with time zone", "timestamp without time zone" -> "timestamptz '1900-01-01T00:00:00Z'";
            case "inet" -> "inet '0.0.0.0'";
            case "smallint", "integer", "bigint", "numeric", "real", "double precision" -> "0";
            default -> c.name();   // unknown type: left unchanged rather than corrupted
        };
    }

    Map<String, Column> columns(String table) {
        Map<String, Column> cols = columns.computeIfAbsent(table, t -> {
            Map<String, Column> m = new LinkedHashMap<>();
            jdbc.sql("""
                    select column_name, data_type, is_nullable = 'YES', character_maximum_length
                    from information_schema.columns where table_schema = current_schema() and table_name = ?
                    order by ordinal_position""").param(t)
                    .query((rs, i) -> m.put(rs.getString(1), new Column(rs.getString(1), rs.getString(2), rs.getBoolean(3),
                            (Integer) rs.getObject(4), "ARRAY".equals(rs.getString(2))))).list();
            return m;
        });
        if (cols.isEmpty()) {
            columns.remove(table);
        }
        return cols;
    }

    private boolean exists(String table) {
        return Selection.IDENT.matcher(table).matches() && !columns(table).isEmpty();
    }

    /** True when the table carries the generated append-only trigger (forbid_mutation). */
    private boolean appendOnly(String table) {
        return jdbc.sql("""
                select exists(select 1 from pg_trigger g join pg_class c on c.oid = g.tgrelid
                    join pg_namespace n on n.oid = c.relnamespace join pg_proc f on f.oid = g.tgfoid
                    where n.nspname = current_schema() and c.relname = ? and f.proname = 'forbid_mutation')""")
                .param(table).query(Boolean.class).single();
    }

    private static boolean appendOnlyViolation(DataAccessException e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof java.sql.SQLException sql && "55000".equals(sql.getSQLState())) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    private String table(JsonNode body) {
        String t = body.path("table_name").asString("");
        if (!Selection.IDENT.matcher(t).matches() || !exists(t)) {
            throw ApiException.unprocessable("unknown_table", service + " has no table " + (t.isBlank() ? "(missing table_name)" : t));
        }
        return t;
    }

    private static JsonNode withoutExclusions(JsonNode body) {
        ObjectNode copy = (ObjectNode) body.deepCopy();
        copy.remove("exclude");
        return copy;
    }

    private static UUID uuid(JsonNode body, String f) {
        String v = body.path(f).asString(null);
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(v.trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable("invalid_id", f + " must be a uuid");
        }
    }

    private static UUID requireUuid(JsonNode body, String f) {
        UUID u = uuid(body, f);
        if (u == null) {
            throw ApiException.unprocessable("validation_failed", f + " is required");
        }
        return u;
    }
}
