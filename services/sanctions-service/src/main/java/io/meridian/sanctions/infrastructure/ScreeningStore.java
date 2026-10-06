package io.meridian.sanctions.infrastructure;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Persistence of a screening in one statement: request, result, matches and outbox events. */
@Component
public class ScreeningStore {
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public ScreeningStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Entries whitelisted (good-guy list) for any of the subjects. */
    public List<UUID> whitelisted(Collection<UUID> subjects) {
        if (subjects.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                select sanctions_list_entry_id from screening_whitelist
                where subject_id = any(cast(? as uuid[])) and (expires_at is null or expires_at > now())""")
                .param(Db.array(subjects)).query(UUID.class).list();
    }

    public JsonNode persist(ObjectNode request, ObjectNode result, ArrayNode matches, String principal) {
        String headers = json.writeValueAsString(java.util.Map.of("ce_principal", principal));
        String sql = """
                with q as (
                  insert into screening_request (id, tenant_id, created_by, updated_by, subject_type, subject_id, trigger,
                      full_name, date_of_birth, country, nationality, identifiers, payload, list_codes, threshold,
                      requested_by_service, correlation_id, screening_batch_id, status, screening_result_id)
                  select r.id, current_setting('app.tenant_id')::uuid, cast(:p as text), cast(:p as text), r.subject_type,
                      r.subject_id, r.trigger, r.full_name, r.date_of_birth, r.country, r.nationality, r.identifiers,
                      r.payload, r.list_codes, coalesce(r.threshold, 0.85), r.requested_by_service, r.correlation_id,
                      r.screening_batch_id, r.status, r.screening_result_id
                  from json_populate_record(null::screening_request, cast(:req as json)) r
                  returning *
                ),
                res as (
                  insert into screening_result (id, tenant_id, created_by, updated_by, screening_request_id, subject_type,
                      subject_id, status, highest_score, match_count, is_pep, has_adverse_media, suppressed_count,
                      rescreen_due_at, latency_ms)
                  select r.id, current_setting('app.tenant_id')::uuid, cast(:p as text), cast(:p as text), q.id,
                      r.subject_type, r.subject_id, r.status, r.highest_score, r.match_count, false, r.has_adverse_media,
                      r.suppressed_count, r.rescreen_due_at, r.latency_ms
                  from json_populate_record(null::screening_result, cast(:res as json)) r, q
                  returning *
                ),
                m as (
                  insert into screening_match (tenant_id, created_by, updated_by, screening_result_id,
                      sanctions_list_entry_id, matched_name, match_score, match_algorithm, matched_fields, disposition)
                  select current_setting('app.tenant_id')::uuid, cast(:p as text), cast(:p as text), res.id,
                      r.sanctions_list_entry_id, r.matched_name, r.match_score, r.match_algorithm, r.matched_fields, 'PENDING'
                  from json_populate_recordset(null::screening_match, cast(:matches as json)) r, res
                  returning id
                ),
                o as (
                  insert into outbox (tenant_id, aggregate_type, aggregate_id, event_type, payload, headers)
                  select current_setting('app.tenant_id')::uuid, 'screening_result', res.id, x.type, to_jsonb(res),
                         cast(:headers as jsonb)
                  from res, (values ('risk.screening.completed'), ('risk.screening.potential_match')) as x(type)
                  where x.type = 'risk.screening.completed' or res.status = 'POTENTIAL_MATCH'
                  returning 1
                )
                select json_build_object('request', (select row_to_json(q) from q),
                                         'result', (select row_to_json(res) from res))::text""";
        String row = jdbc.sql(sql)
                .param("p", principal)
                .param("req", json.writeValueAsString(request))
                .param("res", json.writeValueAsString(result))
                .param("matches", json.writeValueAsString(matches))
                .param("headers", headers)
                .query(String.class).single();
        return json.readTree(row);
    }
}
