package io.meridian.search.infrastructure;

import io.meridian.common.tenancy.TenantContext;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * PostgreSQL full-text storage on the spec-owned tables {@code search_document} (documents per physical index:
 * weighted text A/B/C, JSONB body) and {@code search_ingest_log} (append-only journal), with ranked queries. The
 * weighted tsvector is computed from the A/B/C text at query time. All statements are parameterised; RLS scopes
 * them to the tenant.
 */
@Repository
public class DocumentStore {
    /** Weighted tsvector of a document row (A = boost >= 3, B = boost >= 2, C = the rest). */
    private static final String TSV = "(setweight(to_tsvector('simple', coalesce(text_a, '')), 'A')"
            + " || setweight(to_tsvector('simple', coalesce(text_b, '')), 'B')"
            + " || setweight(to_tsvector('simple', coalesce(text_c, '')), 'C'))";

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public DocumentStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record IngestEntry(UUID id, String entityType, String entityId, String operation, JsonNode document,
                              String eventId, Long version, OffsetDateTime occurredAt) {}

    public UUID journal(String entityType, String entityId, String op, JsonNode doc, String eventId, Long version,
                        OffsetDateTime occurredAt) {
        return jdbc.sql("""
                insert into search_ingest_log (tenant_id, entity_type, entity_id, operation, document, source_event_id,
                                               source_version, occurred_at, created_by)
                values (?, ?, ?, ?, ?::jsonb, ?, ?, coalesce(?, now()), ?) returning id""")
                .params(TenantContext.require(), entityType, entityId, op, doc == null ? null : json.writeValueAsString(doc),
                        eventId, version, occurredAt, TenantContext.principal())
                .query(UUID.class).single();
    }

    /**
     * Upserts a document unless the stored one carries a newer source version (out-of-order events). Returns the
     * number of rows written (0 = stale, ignored).
     */
    public int upsert(String physicalIndex, String entityType, String entityId, String title, JsonNode body,
                       String[] weighted, String eventId, Long version, OffsetDateTime occurredAt) {
        String all = String.join(" ", weighted).trim();
        return jdbc.sql("""
                insert into search_document as d (tenant_id, physical_index, entity_type, entity_id, title, body, search_text,
                       text_a, text_b, text_c, source_event_id, source_version, occurred_at, indexed_at, created_by, updated_by)
                values (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, now(), ?, ?)
                on conflict (tenant_id, physical_index, entity_id) do update
                   set title = excluded.title, body = excluded.body, search_text = excluded.search_text,
                       text_a = excluded.text_a, text_b = excluded.text_b, text_c = excluded.text_c,
                       source_event_id = excluded.source_event_id, source_version = excluded.source_version,
                       occurred_at = excluded.occurred_at, indexed_at = now(), updated_by = excluded.updated_by
                 where d.source_version is null or excluded.source_version is null
                       or excluded.source_version >= d.source_version""")
                .params(TenantContext.require(), physicalIndex, entityType, entityId, title, json.writeValueAsString(body),
                        all, weighted[0], weighted[1], weighted[2], eventId, version, occurredAt,
                        TenantContext.principal(), TenantContext.principal())
                .update();
    }

    public int delete(String physicalIndex, String entityId, Long version) {
        return jdbc.sql("""
                delete from search_document where physical_index = ? and entity_id = ?
                  and (cast(? as bigint) is null or source_version is null or source_version <= cast(? as bigint))""")
                .params(physicalIndex, entityId, version, version).update();
    }

    /** Highest source version at which the entity was deleted (tombstone in the journal), if any. */
    public Long lastDeletedVersion(String entityType, String entityId) {
        return jdbc.sql("""
                select max(source_version) from search_ingest_log
                where entity_type = ? and entity_id = ? and operation = 'DELETE'""")
                .params(entityType, entityId).query(Long.class).optional().orElse(null);
    }

    public int dropIndex(String physicalIndex) {
        return jdbc.sql("delete from search_document where physical_index = ?").param(physicalIndex).update();
    }

    public long count(String physicalIndex) {
        return jdbc.sql("select count(*) from search_document where physical_index = ?").param(physicalIndex)
                .query(Long.class).single();
    }

    /** Journal entries of a type that occurred at or after {@code from}, in ingest order, after {@code afterId}. */
    public List<IngestEntry> since(String entityType, OffsetDateTime from, UUID afterId, int limit) {
        return jdbc.sql("""
                select id, entity_type, entity_id, operation, document::text, source_event_id, source_version, occurred_at
                from search_ingest_log where entity_type = ? and occurred_at >= ? and id > ?
                order by id limit ?""")
                .params(entityType, from, afterId == null ? new UUID(0, 0) : afterId, limit)
                .query((rs, i) -> entry(rs))
                .list();
    }

    /** Average event-to-index lag over the latest ingests, in seconds, and the last indexing time. */
    public record Lag(Integer seconds, OffsetDateTime lastIndexedAt) {}

    public Lag lag(String entityType) {
        return jdbc.sql("""
                select round(avg(extract(epoch from (ingested_at - occurred_at))))::int, max(ingested_at)
                from (select ingested_at, occurred_at from search_ingest_log where entity_type = ?
                      order by id desc limit 100) l""")
                .param(entityType)
                .query((rs, i) -> new Lag((Integer) rs.getObject(1), rs.getObject(2, OffsetDateTime.class)))
                .single();
    }

    public record Hit(String entityType, String entityId, String title, JsonNode body, double score) {}

    /** Ranked full-text (and/or JSONB containment filter) query over several physical indexes. */
    public List<Hit> search(List<String> physicalIndexes, String tsquery, JsonNode filters, int offset, int limit) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("select entity_type, entity_id, title, body::text, ");
        if (tsquery != null) {
            sql.append("ts_rank_cd(").append(TSV).append(", to_tsquery('simple', ?), 32)");
            args.add(tsquery);
        } else {
            sql.append("0");
        }
        sql.append(" as score from search_document where physical_index = any(?)");
        args.add(physicalIndexes.toArray(String[]::new));
        if (tsquery != null) {
            sql.append(" and ").append(TSV).append(" @@ to_tsquery('simple', ?)");
            args.add(tsquery);
        }
        if (filters != null && filters.isObject() && !filters.isEmpty()) {
            sql.append(" and body @> ?::jsonb");
            args.add(json.writeValueAsString(filters));
        }
        sql.append(" order by score desc, indexed_at desc, entity_id offset ? limit ?");
        args.add(offset);
        args.add(limit);
        return jdbc.sql(sql.toString()).params(args)
                .query((rs, i) -> new Hit(rs.getString(1), rs.getString(2), rs.getString(3), json.readTree(rs.getString(4)),
                        rs.getDouble(5)))
                .list();
    }

    /** Titles starting with the prefix (case-insensitive), shortest first. */
    public List<Hit> suggest(List<String> physicalIndexes, String prefix, int limit) {
        String like = prefix.toLowerCase(java.util.Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return jdbc.sql("""
                select entity_type, entity_id, title, body::text from search_document
                where physical_index = any(?) and lower(title) like ?
                order by length(title), title limit ?""")
                .params(physicalIndexes.toArray(String[]::new), like, limit)
                .query((rs, i) -> new Hit(rs.getString(1), rs.getString(2), rs.getString(3), json.readTree(rs.getString(4)), 1))
                .list();
    }

    private IngestEntry entry(java.sql.ResultSet rs) throws java.sql.SQLException {
        String doc = rs.getString(5);
        return new IngestEntry(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                doc == null ? null : json.readTree(doc), rs.getString(6), (Long) rs.getObject(7),
                rs.getObject(8, OffsetDateTime.class));
    }
}
