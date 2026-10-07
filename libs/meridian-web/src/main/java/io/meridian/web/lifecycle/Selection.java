package io.meridian.web.lifecycle;

import io.meridian.common.error.ApiException;
import io.meridian.web.lifecycle.PersonalDataCatalog.SubjectPath;
import io.meridian.web.lifecycle.PersonalDataCatalog.TableInfo;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Translates archive-service selectors ({@code record_selector} / {@code exclude}: {@code customer_ids},
 * {@code account_ids}, {@code case_ids}, {@code document_ids}, {@code ids}, {@code created_before}) into a SQL
 * predicate over one table, using only identifiers checked against the table's real columns and bind parameters for
 * every value.
 *
 * <p>Customer ids follow the spec's subject paths (customer column, polymorphic owner, parent foreign key). An
 * <em>exclusion</em> (legal hold) on a table that cannot be linked to that kind of entity excludes nothing: such rows
 * are not that entity's data. A <em>selection</em> by an entity the table cannot be linked to is refused.
 */
public final class Selection {
    static final Pattern IDENT = Pattern.compile("[a-z_][a-z0-9_]{0,62}");
    /** Columns tried for each selector key (besides {@code id} on the entity's own table). */
    static final Map<String, List<String>> ENTITY_COLUMNS = Map.of(
            "account_ids", List.of("account_id", "deposit_account_id"),
            "case_ids", List.of("case_id", "service_case_id", "aml_case_id"),
            "document_ids", List.of("document_id"));
    static final Map<String, Set<String>> ENTITY_TABLES = Map.of(
            "account_ids", Set.of("deposit_account", "account"),
            "case_ids", Set.of("service_case", "aml_case", "case"),
            "document_ids", Set.of("document"));
    static final List<String> KEYS = List.of("customer_ids", "account_ids", "case_ids", "document_ids", "ids");

    /** A predicate fragment (starting with {@code " and "}, or empty) and its bind values in order. */
    public record Sql(String where, List<Object> args) {
        static Sql empty() {
            return new Sql("", List.of());
        }
    }

    private final Map<String, List<UUID>> include;
    private final Map<String, List<UUID>> exclude;
    private final OffsetDateTime createdBefore;

    private Selection(Map<String, List<UUID>> include, Map<String, List<UUID>> exclude, OffsetDateTime createdBefore) {
        this.include = include;
        this.exclude = exclude;
        this.createdBefore = createdBefore;
    }

    /** From a request body: {@code record_selector} (optional), {@code exclude} and {@code created_before}. */
    public static Selection from(JsonNode body) {
        JsonNode sel = body.path("record_selector");
        String cb = text(body, "created_before");
        if (cb == null) {
            cb = text(sel, "created_before");
        }
        return new Selection(ids(sel), ids(body.path("exclude")), cb == null ? null : timestamp(cb));
    }

    public OffsetDateTime createdBefore() {
        return createdBefore;
    }

    /** True when the selection restricts rows at all (a purge without any restriction is refused). */
    public boolean restricted() {
        return createdBefore != null || include.values().stream().anyMatch(l -> !l.isEmpty());
    }

    /**
     * Predicate for table {@code info.table()} (alias {@code t}); {@code columns} are the table's real columns and
     * {@code columnsOf} gives those of a parent table (subject paths through a foreign key).
     */
    public Sql predicate(String table, TableInfo info, Set<String> columns, Function<String, Set<String>> columnsOf,
                         Function<String, TableInfo> infoOf) {
        StringBuilder w = new StringBuilder();
        List<Object> args = new ArrayList<>();
        if (createdBefore != null) {
            require(columns, "created_at");
            w.append(" and t.created_at < ?");
            args.add(createdBefore);
        }
        for (String key : KEYS) {
            List<UUID> in = include.getOrDefault(key, List.of());
            if (!in.isEmpty()) {
                Sql s = entity(key, table, info, columns, columnsOf, infoOf, in, "t", 0);
                if (s == null) {
                    throw ApiException.unprocessable("selector_not_applicable",
                            "Table " + table + " cannot be selected by " + key);
                }
                w.append(" and ").append(s.where());
                args.addAll(s.args());
            }
            List<UUID> out = exclude.getOrDefault(key, List.of());
            if (!out.isEmpty()) {
                Sql s = entity(key, table, info, columns, columnsOf, infoOf, out, "t", 0);
                if (s != null) {
                    w.append(" and not coalesce(").append(s.where()).append(", false)");
                    args.addAll(s.args());
                }
            }
        }
        return new Sql(w.toString(), args);
    }

    /** Predicate selecting the rows of {@code table} (alias {@code t}) linked to the given customers. */
    public static Sql subject(String table, TableInfo info, Set<String> columns, Function<String, Set<String>> columnsOf,
                              Function<String, TableInfo> infoOf, List<UUID> customers) {
        Sql s = entity("customer_ids", table, info, columns, columnsOf, infoOf, customers, "t", 0);
        return s == null ? null : new Sql(" and " + s.where(), s.args());
    }

    /** Predicate (without leading "and") for rows of {@code table} linked to {@code ids}, or null when not linkable. */
    private static Sql entity(String key, String table, TableInfo info, Set<String> columns,
                              Function<String, Set<String>> columnsOf, Function<String, TableInfo> infoOf,
                              List<UUID> ids, String alias, int depth) {
        String array = array(ids);
        List<String> parts = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if ("ids".equals(key)) {
            return new Sql("(" + alias + ".id = any(?::uuid[]))", List.of(array));
        }
        if ("customer_ids".equals(key)) {
            if (info == null) {
                return null;
            }
            for (SubjectPath p : info.subject()) {
                if (!ok(columns, p.column())) {
                    continue;
                }
                if (p.parent() != null) {
                    if (depth >= 2 || !IDENT.matcher(p.parent()).matches()) {
                        continue;
                    }
                    Set<String> pc = columnsOf.apply(p.parent());
                    Sql inner = entity(key, p.parent(), infoOf.apply(p.parent()), pc, columnsOf, infoOf, ids,
                            "p" + depth, depth + 1);
                    if (inner != null) {
                        parts.add(alias + "." + p.column() + " in (select p" + depth + ".id from " + p.parent() + " p" + depth
                                + " where " + inner.where() + ")");
                        args.addAll(inner.args());
                    }
                } else if (p.typeColumn() != null) {
                    if (ok(columns, p.typeColumn())) {
                        parts.add("(" + alias + "." + p.typeColumn() + " = ? and " + alias + "." + p.column() + " = any(?::uuid[]))");
                        args.add(p.typeValue());
                        args.add(array);
                    }
                } else {
                    parts.add(alias + "." + p.column() + " = any(?::uuid[])");
                    args.add(array);
                }
            }
        } else {
            if (ENTITY_TABLES.getOrDefault(key, Set.of()).contains(table)) {
                parts.add(alias + ".id = any(?::uuid[])");
                args.add(array);
            }
            for (String c : ENTITY_COLUMNS.getOrDefault(key, List.of())) {
                if (ok(columns, c)) {
                    parts.add(alias + "." + c + " = any(?::uuid[])");
                    args.add(array);
                }
            }
        }
        return parts.isEmpty() ? null : new Sql("(" + String.join(" or ", parts) + ")", args);
    }

    private static boolean ok(Set<String> columns, String c) {
        return c != null && IDENT.matcher(c).matches() && columns.contains(c);
    }

    private static void require(Set<String> columns, String c) {
        if (!columns.contains(c)) {
            throw ApiException.unprocessable("selector_not_applicable", "Table has no column " + c);
        }
    }

    static String array(List<UUID> ids) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < ids.size(); i++) {
            sb.append(i == 0 ? "" : ",").append(ids.get(i));
        }
        return sb.append('}').toString();
    }

    private static Map<String, List<UUID>> ids(JsonNode n) {
        Map<String, List<UUID>> out = new LinkedHashMap<>();
        if (n == null || !n.isObject()) {
            return out;
        }
        for (String key : KEYS) {
            JsonNode arr = n.path(key);
            if (arr.isArray()) {
                List<UUID> l = new ArrayList<>();
                for (JsonNode x : arr) {
                    try {
                        l.add(UUID.fromString(x.asString().trim()));
                    } catch (IllegalArgumentException e) {
                        throw ApiException.unprocessable("invalid_selector", key + " must contain uuids");
                    }
                }
                out.put(key, l);
            }
        }
        return out;
    }

    static OffsetDateTime timestamp(String v) {
        try {
            return v.length() == 10 ? LocalDate.parse(v).atStartOfDay().atOffset(ZoneOffset.UTC) : OffsetDateTime.parse(v);
        } catch (DateTimeParseException e) {
            throw ApiException.unprocessable("invalid_selector", "created_before must be a date or a timestamp");
        }
    }

    private static String text(JsonNode n, String f) {
        return n != null && n.hasNonNull(f) && !n.get(f).asString().isBlank() ? n.get(f).asString() : null;
    }
}
