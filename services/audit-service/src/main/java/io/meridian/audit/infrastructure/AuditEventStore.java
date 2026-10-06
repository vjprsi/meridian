package io.meridian.audit.infrastructure;

import io.meridian.audit.domain.AuditRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** Audit-event SQL beyond generic CRUD. All queries run under row-level security (current tenant only). */
@Repository
public class AuditEventStore {
    private static final String COLUMNS = """
            id, tenant_id, sequence_no, occurred_at, actor_type, actor_id, actor_display, impersonator_id, action,
            resource_type, resource_id, outcome, reason, before_state::text, after_state::text, legal_entity_id,
            approval_request_id, ip_address::text, user_agent, session_id, request_id, trace_id, service_name,
            prev_hash, hash""";

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public AuditEventStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record Head(long sequenceNo, String hash) {}

    /** Serialises appends per tenant for the rest of the transaction (gapless sequence, linear chain). */
    public void lockTenantChain(UUID tenantId) {
        jdbc.sql("select 1 from (select pg_advisory_xact_lock(hashtextextended(?, 0))) l")
                .param("audit-chain:" + tenantId).query(Integer.class).single();
    }

    public Optional<Head> head() {
        return jdbc.sql("select sequence_no, hash from audit_event order by sequence_no desc limit 1")
                .query((rs, i) -> new Head(rs.getLong(1), rs.getString(2))).optional();
    }

    public boolean existsByRequestId(String requestId) {
        return jdbc.sql("select exists(select 1 from audit_event where request_id = ?)").param(requestId)
                .query(Boolean.class).single();
    }

    /** Round-trips a JSON document through jsonb so the hashed form equals what is stored. */
    public String normaliseJson(String jsonText) {
        return jsonText == null ? null : jdbc.sql("select ?::jsonb::text").param(jsonText).query(String.class).single();
    }

    public String normaliseInet(String ip) {
        return ip == null ? null : jdbc.sql("select ?::inet::text").param(ip).query(String.class).single();
    }

    public List<AuditRecord> range(long from, long to) {
        return jdbc.sql("select " + COLUMNS + " from audit_event where sequence_no between ? and ? order by sequence_no")
                .params(from, to).query(this::map).list();
    }

    public Optional<String> hashAt(long sequenceNo) {
        return jdbc.sql("select hash from audit_event where sequence_no = ?").param(sequenceNo)
                .query(String.class).optional();
    }

    public long count(OffsetDateTime from, OffsetDateTime to) {
        StringBuilder sql = new StringBuilder("select count(*) from audit_event where true");
        List<Object> args = new ArrayList<>();
        if (from != null) {
            sql.append(" and occurred_at >= ?");
            args.add(from);
        }
        if (to != null) {
            sql.append(" and occurred_at < ?");
            args.add(to);
        }
        return jdbc.sql(sql.toString()).params(args).query(Long.class).single();
    }

    /** Runs a search built by the application layer (where clause with bind parameters only). */
    public List<String> search(String where, List<Object> args, UUID before, int limit) {
        StringBuilder sql = new StringBuilder("select row_to_json(t)::text from audit_event t where true").append(where);
        List<Object> all = new ArrayList<>(args);
        if (before != null) {
            sql.append(" and t.id < ?");
            all.add(before);
        }
        sql.append(" order by t.id desc limit ?");
        all.add(limit + 1);
        return jdbc.sql(sql.toString()).params(all).query(String.class).list();
    }

    private AuditRecord map(ResultSet rs, int i) throws SQLException {
        return new AuditRecord(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getLong(3),
                rs.getObject(4, OffsetDateTime.class).toInstant(), rs.getString(5), rs.getString(6), rs.getString(7),
                rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12),
                rs.getString(13), parse(rs.getString(14)), parse(rs.getString(15)), rs.getObject(16, UUID.class),
                rs.getObject(17, UUID.class), rs.getString(18), rs.getString(19), rs.getString(20), rs.getString(21),
                rs.getString(22), rs.getString(23), rs.getString(24), rs.getString(25));
    }

    public Object parse(String jsonText) {
        return jsonText == null ? null : json.readValue(jsonText, Object.class);
    }
}
