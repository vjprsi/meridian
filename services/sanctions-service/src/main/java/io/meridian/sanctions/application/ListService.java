package io.meridian.sanctions.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.page.Cursor;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.sanctions.domain.ListFileParser;
import io.meridian.sanctions.infrastructure.Db;
import io.meridian.sanctions.infrastructure.Downstream;
import io.meridian.web.crud.CrudEngine;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Sanctions / PEP / adverse-media lists. Lists created through the API belong to the calling tenant; platform-managed
 * global lists (no owner) are visible to every tenant but read-only. Sync replaces a list version (entries missing
 * from the file are delisted); import adds or updates entries.
 */
@Service
public class ListService {
    private static final int MAX_ROWS = 200_000;

    private final Db db;
    private final CrudEngine crud;
    private final ListCatalog catalog;
    private final Downstream downstream;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final JsonMapper json;

    public ListService(Db db, CrudEngine crud, ListCatalog catalog, Downstream downstream, OutboxWriter outbox,
                       TransactionTemplate tx, JsonMapper json) {
        this.db = db;
        this.crud = crud;
        this.catalog = catalog;
        this.downstream = downstream;
        this.outbox = outbox;
        this.tx = tx;
        this.json = json;
    }

    // ------------------------------------------------------------------ lists

    public String createList(JsonNode body) {
        UUID tenant = TenantContext.require();
        return tx.execute(s -> {
            ObjectNode v = crud.toColumns("sanctions_list", body);
            v.put("owner_tenant_id", tenant.toString());
            v.put("entry_count", 0);
            if (db.one("select 1::text from sanctions_list where owner_tenant_id = ? and code = ?", tenant, Db.str(v, "code")).isPresent()) {
                throw ApiException.conflict("duplicate_code", "A list with code " + Db.str(v, "code") + " already exists");
            }
            String row = crud.insert("sanctions_list", v);
            catalog.invalidate();
            return row;
        });
    }

    public String updateList(UUID id, JsonNode changes, String ifMatch) {
        catalog.requireList(id, true);
        ObjectNode v = crud.toColumns("sanctions_list", changes);
        v.remove("owner_tenant_id");
        String row = crud.updateRow("sanctions_list", Map.of("id", id), v, ifMatchVersion(ifMatch))
                .orElseThrow(() -> ApiException.conflict("version_conflict", "If-Match does not match the current version; reload and retry"));
        catalog.invalidate();
        return row;
    }

    /** Lists visible to the tenant with the declared filters, newest first, cursor paged. */
    public String pageLists(Map<String, Object> filters, Integer limit, String cursor, Object after, Object before) {
        return page("select row_to_json(t)::text from sanctions_list t where (t.owner_tenant_id is null or t.owner_tenant_id = ?)",
                filters, limit, cursor, after, before, Map.of("source", "text", "code", "text", "is_active", "boolean"));
    }

    public String pageEntries(Map<String, Object> filters, Integer limit, String cursor, Object after, Object before) {
        return page("""
                select row_to_json(t)::text from sanctions_list_entry t join sanctions_list l on l.id = t.sanctions_list_id
                where (l.owner_tenant_id is null or l.owner_tenant_id = ?)""", filters, limit, cursor, after, before,
                Map.of("sanctions_list_id", "uuid", "entry_type", "text", "nationality", "text", "external_id", "text"));
    }

    private String page(String base, Map<String, Object> filters, Integer limitParam, String cursor, Object after,
                        Object before, Map<String, String> types) {
        List<Object> args = new ArrayList<>();
        args.add(TenantContext.require());
        StringBuilder sql = new StringBuilder(base);
        filters.forEach((k, v) -> {
            if (v != null && types.containsKey(k)) {
                sql.append(" and t.").append(k).append(" = cast(? as ").append(types.get(k)).append(")");
                args.add(v.toString().trim());
            }
        });
        if (cursor != null && !cursor.isBlank()) {
            sql.append(" and t.id < ?");
            args.add(Cursor.decode(cursor));
        }
        if (after != null) {
            sql.append(" and t.created_at >= cast(? as timestamptz)");
            args.add(after.toString());
        }
        if (before != null) {
            sql.append(" and t.created_at < cast(? as timestamptz)");
            args.add(before.toString());
        }
        int limit = Cursor.limit(limitParam);
        sql.append(" order by t.id desc limit ?");
        args.add(limit + 1);
        List<String> rows = db.jdbc().sql(sql.toString()).params(args).query(String.class).list();
        boolean more = rows.size() > limit;
        List<String> data = more ? rows.subList(0, limit) : rows;
        String next = more ? Cursor.encode(UUID.fromString(json.readTree(data.getLast()).get("id").asString())) : null;
        return "{\"data\":[" + String.join(",", data) + "],\"has_more\":" + more + ",\"next_cursor\":"
                + (next == null ? "null" : "\"" + next + "\"") + "}";
    }

    // ------------------------------------------------------------------ entries (tenant-owned lists only)

    public String createEntry(JsonNode body) {
        UUID listId = Db.uuid(body, "sanctions_list_id");
        catalog.requireList(listId, true);
        return tx.execute(s -> {
            ObjectNode v = crud.toColumns("sanctions_list_entry", body);
            validateEntry(v);
            if (db.one("select 1::text from sanctions_list_entry where sanctions_list_id = ? and external_id = ?",
                    listId, Db.str(v, "external_id")).isPresent()) {
                throw ApiException.conflict("duplicate_external_id", "The list already has entry " + Db.str(v, "external_id"));
            }
            String row = crud.insert("sanctions_list_entry", v);
            touchList(listId);
            return row;
        });
    }

    public String updateEntry(UUID id, JsonNode changes, String ifMatch) {
        JsonNode e = catalog.requireEntry(id);
        UUID listId = UUID.fromString(e.get("sanctions_list_id").asString());
        catalog.requireList(listId, true);
        return tx.execute(s -> {
            ObjectNode v = crud.toColumns("sanctions_list_entry", changes);
            v.remove("sanctions_list_id");
            ObjectNode merged = ((ObjectNode) e.deepCopy()).setAll(v);
            validateEntry(merged);
            String row = crud.updateRow("sanctions_list_entry", Map.of("id", id), v, ifMatchVersion(ifMatch))
                    .orElseThrow(() -> ApiException.conflict("version_conflict", "If-Match does not match the current version; reload and retry"));
            touchList(listId);
            return row;
        });
    }

    private static void validateEntry(JsonNode v) {
        String type = Db.str(v, "entry_type");
        if ("COUNTRY".equals(type) && (!v.has("countries") || v.get("countries").isEmpty())) {
            throw ApiException.unprocessable("countries_required", "COUNTRY entries must list the embargoed countries");
        }
    }

    /** Recounts active entries; the row_version bump makes every instance rebuild the list's match index. */
    private void touchList(UUID listId) {
        db.update("""
                update sanctions_list set entry_count = (select count(*) from sanctions_list_entry
                    where sanctions_list_id = ? and (delisted_on is null or delisted_on > current_date)), updated_by = ?
                where id = ?""", listId, TenantContext.principal(), listId);
        catalog.invalidate();
    }

    // ------------------------------------------------------------------ sync and import

    /** Loads a full list version (inline content or download from source_url); missing entries are delisted. */
    public String sync(UUID listId, String content, String format, String listVersion, String publishedAt) {
        JsonNode list = catalog.requireList(listId, true);
        if (content == null || content.isBlank()) {
            String url = Db.str(list, "source_url");
            if (url == null) {
                throw ApiException.unprocessable("content_required", "Give content or configure the list's source_url");
            }
            content = downstream.download(url);
        }
        String sha = sha256(content);
        List<ListFileParser.Row> rows = parse(content, format);
        List<ListFileParser.Row> bad = rows.stream().filter(r -> r.error() != null).toList();
        if (!bad.isEmpty()) {
            throw ApiException.unprocessable("invalid_list_file", bad.size() + " invalid rows, first: line "
                    + bad.getFirst().line() + ": " + bad.getFirst().error());
        }
        return tx.execute(s -> {
            JsonNode locked = db.require("sanctions_list", listId, true);
            if (sha.equals(Db.str(locked, "content_sha256"))) {
                db.update("update sanctions_list set loaded_at = now() where id = ?", listId);
                return db.text(db.require("sanctions_list", listId, false));
            }
            upsert(listId, rows);
            Set<String> ids = new LinkedHashSet<>();
            rows.forEach(r -> ids.add(r.fields().get("external_id").toString()));
            db.update("""
                    update sanctions_list_entry set delisted_on = current_date, updated_by = ?
                    where sanctions_list_id = ? and delisted_on is null
                      and not (external_id = any(cast(? as text[])))""",
                    TenantContext.principal(), listId, ids.toArray(new String[0]));
            db.update("""
                    update sanctions_list set list_version = coalesce(?, list_version),
                        published_at = coalesce(cast(? as timestamptz), published_at), loaded_at = now(),
                        content_sha256 = ?, updated_by = ?,
                        entry_count = (select count(*) from sanctions_list_entry where sanctions_list_id = ?
                                       and (delisted_on is null or delisted_on > current_date))
                    where id = ?""", listVersion, publishedAt, sha, TenantContext.principal(), listId, listId);
            JsonNode row = db.require("sanctions_list", listId, false);
            outbox.publish("risk.sanctions_list.updated", "sanctions_list", listId, row);
            catalog.invalidate();
            return db.text(row);
        });
    }

    /** Adds / updates entries from a CSV or JSON file (document-service or inline); invalid rows are reported. */
    public Map<String, Object> importFile(UUID listId, UUID documentId, String content, String format) {
        catalog.requireList(listId, true);
        if ((content == null || content.isBlank()) && documentId == null) {
            throw ApiException.unprocessable("content_required", "Give document_id or content");
        }
        String text = content != null && !content.isBlank() ? content : downstream.documentContent(documentId);
        List<ListFileParser.Row> rows = parse(text, format);
        List<ListFileParser.Row> good = rows.stream().filter(r -> r.error() == null).toList();
        List<Map<String, Object>> errors = new ArrayList<>();
        rows.stream().filter(r -> r.error() != null).limit(100).forEach(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("line", r.line());
            m.put("error", r.error());
            errors.add(m);
        });
        int[] counts = tx.execute(s -> {
            db.require("sanctions_list", listId, true);
            int[] c = upsert(listId, good);
            db.update("""
                    update sanctions_list set loaded_at = now(), updated_by = ?,
                        entry_count = (select count(*) from sanctions_list_entry where sanctions_list_id = ?
                                       and (delisted_on is null or delisted_on > current_date))
                    where id = ?""", TenantContext.principal(), listId, listId);
            JsonNode row = db.require("sanctions_list", listId, false);
            if (c[0] + c[1] > 0) {
                outbox.publish("risk.sanctions_list.updated", "sanctions_list", listId, row);
            }
            return c;
        });
        catalog.invalidate();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list_id", listId.toString());
        out.put("format", detect(text, format));
        out.put("rows", rows.size());
        out.put("added", counts[0]);
        out.put("updated", counts[1]);
        out.put("unchanged", good.size() - counts[0] - counts[1]);
        out.put("rejected", rows.size() - good.size());
        out.put("errors", errors);
        return out;
    }

    /** Upserts entries; returns {added, updated} (rows whose content did not change are left untouched). */
    private int[] upsert(UUID listId, List<ListFileParser.Row> rows) {
        if (rows.isEmpty()) {
            return new int[] {0, 0};
        }
        ArrayNode arr = json.createArrayNode();
        for (ListFileParser.Row r : rows) {
            arr.add(json.valueToTree(r.fields()));
        }
        List<Boolean> inserted = db.jdbc().sql("""
                insert into sanctions_list_entry as e (created_by, updated_by, sanctions_list_id, external_id, entry_type,
                    primary_name, aliases, date_of_birth, place_of_birth, nationality, countries, identifiers, programs,
                    pep_level, remarks, listed_on)
                select cast(:p as text), cast(:p as text), cast(:list as uuid), r.external_id, r.entry_type, r.primary_name,
                    coalesce(r.aliases, '[]'::jsonb), r.date_of_birth, r.place_of_birth, r.nationality,
                    coalesce(r.countries, '[]'::jsonb), coalesce(r.identifiers, '[]'::jsonb),
                    coalesce(r.programs, '[]'::jsonb), r.pep_level, r.remarks, r.listed_on
                from json_populate_recordset(null::sanctions_list_entry, cast(:rows as json)) r
                on conflict (sanctions_list_id, external_id) do update set
                    entry_type = excluded.entry_type, primary_name = excluded.primary_name, aliases = excluded.aliases,
                    date_of_birth = excluded.date_of_birth, place_of_birth = excluded.place_of_birth,
                    nationality = excluded.nationality, countries = excluded.countries, identifiers = excluded.identifiers,
                    programs = excluded.programs, pep_level = excluded.pep_level, remarks = excluded.remarks,
                    listed_on = excluded.listed_on, delisted_on = null, updated_by = excluded.updated_by
                where (e.entry_type, e.primary_name, e.aliases, e.date_of_birth, e.nationality, e.countries,
                       e.identifiers, e.programs, e.pep_level, e.remarks, e.delisted_on)
                   is distinct from (excluded.entry_type, excluded.primary_name, excluded.aliases, excluded.date_of_birth,
                       excluded.nationality, excluded.countries, excluded.identifiers, excluded.programs,
                       excluded.pep_level, excluded.remarks, null::date)
                returning (xmax = 0)""")
                .param("p", TenantContext.principal())
                .param("list", listId)
                .param("rows", json.writeValueAsString(arr))
                .query(Boolean.class).list();
        int added = (int) inserted.stream().filter(Boolean::booleanValue).count();
        return new int[] {added, inserted.size() - added};
    }

    List<ListFileParser.Row> parse(String content, String format) {
        String f = detect(content, format);
        List<ListFileParser.Row> rows;
        if ("JSON".equals(f)) {
            JsonNode root;
            try {
                root = json.readTree(content);
            } catch (RuntimeException e) {
                throw ApiException.unprocessable("invalid_list_file", "Content is not valid JSON");
            }
            JsonNode items = root.isArray() ? root : root.path("entries");
            rows = new ArrayList<>();
            int line = 0;
            for (JsonNode n : items) {
                line++;
                @SuppressWarnings("unchecked")
                Map<String, Object> m = n.isObject() ? json.convertValue(n, LinkedHashMap.class) : new LinkedHashMap<>();
                rows.add(ListFileParser.validate(line, m));
            }
        } else {
            rows = ListFileParser.csv(content);
        }
        if (rows.size() > MAX_ROWS) {
            throw ApiException.unprocessable("list_too_large", "At most " + MAX_ROWS + " entries per file");
        }
        return rows;
    }

    static String detect(String content, String format) {
        if (format != null && !format.isBlank()) {
            String f = format.trim().toUpperCase(java.util.Locale.ROOT);
            if (!f.equals("CSV") && !f.equals("JSON")) {
                throw ApiException.unprocessable("invalid_format", "format must be CSV or JSON");
            }
            return f;
        }
        String t = content.stripLeading();
        return t.startsWith("[") || t.startsWith("{") ? "JSON" : "CSV";
    }

    private static int ifMatchVersion(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw ApiException.badRequest("if_match_required", "If-Match header with the current row_version is required");
        }
        try {
            return Integer.parseInt(ifMatch.replace("W/", "").replace("\"", "").trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("invalid_if_match", "If-Match must be the resource's row_version");
        }
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
