package io.meridian.fraud.application;

import io.meridian.common.error.ApiException;
import io.meridian.fraud.domain.ContextPseudonymisation;
import io.meridian.fraud.domain.Pseudonyms;
import io.meridian.fraud.domain.features.FeatureEvent;
import io.meridian.fraud.infrastructure.Db;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * One-off operator migration of a tenant's stored identifiers to keyed pseudonyms
 * ({@code POST /v1/risk/fraud/scores/pseudonymise-stored}, scope {@code risk:*}):
 * <ul>
 *   <li>{@code fraud_score.features}: the {@code context} of evaluations stored before pseudonymisation is rewritten
 *       with {@link ContextPseudonymisation} (same pseudonyms as new evaluations) and marked
 *       {@code pseudonymisation: h1}; the append-only guard admits this update of {@code features} only
 *       ({@code V001_2__fraud_score_pseudonymisation.sql});</li>
 *   <li>{@code fraud_list_entry.value_hash}: an unkeyed SHA-256 digest becomes {@code h1:HMAC(tenant key, digest)}
 *       (lists keep matching; a legacy row whose value was re-added meanwhile is dropped);</li>
 *   <li>feature store: beneficiary keys made of an unkeyed SHA-256 ({@code acct:} / {@code msisdn:} + 40 hex) cannot
 *       be re-keyed without the clear value and are dropped (counters, profiles, links); that velocity history
 *       restarts under the new keys.</li>
 * </ul>
 * Idempotent and resumable: each run processes up to {@code batch_size} rows per table per batch, at most
 * {@value #MAX_BATCHES} batches, and reports what remains ({@code complete}); {@code dry_run} only counts.
 */
@Service
public class PseudonymisationService {
    private static final Logger log = LoggerFactory.getLogger(PseudonymisationService.class);
    /** Key in {@code fraud_score.features} marking a pseudonymised evaluation. */
    public static final String MARKER = "pseudonymisation";
    public static final String VERSION = "h1";
    static final int MAX_BATCHES = 50;
    static final String LEGACY_KEY = "^(acct|msisdn):[0-9a-f]{40}$";

    private final Db db;
    private final TransactionTemplate tx;
    private final Pseudonymizer pseudonymizer;
    private final JsonMapper json;

    public PseudonymisationService(Db db, TransactionTemplate tx, Pseudonymizer pseudonymizer, JsonMapper json) {
        this.db = db;
        this.tx = tx;
        this.pseudonymizer = pseudonymizer;
        this.json = json;
    }

    public Map<String, Object> run(boolean dryRun, Integer batchSize) {
        int batch = batchSize == null ? 500 : batchSize;
        if (batch < 1 || batch > 5000) {
            throw ApiException.unprocessable("invalid_batch_size", "batch_size must be between 1 and 5000");
        }
        Pseudonyms ps = pseudonymizer.current();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dry_run", dryRun);
        if (dryRun) {
            out.put("scores_to_pseudonymise", count("select count(*) from fraud_score where " + SCORE_PENDING));
            out.put("list_entries_to_rekey", count("select count(*) from fraud_list_entry where value_hash not like 'h1:%'"));
            out.put("feature_rows_to_drop", legacyFeatureRows());
            out.put("complete", false);
            return out;
        }
        long scores = 0;
        long entries = 0;
        long dropped = 0;
        boolean more = true;
        for (int i = 0; i < MAX_BATCHES && more; i++) {
            long s = nz(tx.execute(st -> scores(ps, batch)));
            long e = nz(tx.execute(st -> listEntries(ps, batch)));
            long d = nz(tx.execute(st -> dropLegacyFeatureKeys(batch)));
            scores += s;
            entries += e;
            dropped += d;
            more = s == batch || e == batch || d > 0;
        }
        long remaining = count("select count(*) from fraud_score where " + SCORE_PENDING)
                + count("select count(*) from fraud_list_entry where value_hash not like 'h1:%'") + legacyFeatureRows();
        out.put("scores_pseudonymised", scores);
        out.put("list_entries_rekeyed", entries);
        out.put("feature_rows_dropped", dropped);
        out.put("remaining", remaining);
        out.put("complete", remaining == 0);
        log.info("Pseudonymised stored identifiers: {} scores, {} list entries, {} legacy feature rows dropped, {} remaining",
                scores, entries, dropped, remaining);
        return out;
    }

    /** Evaluations whose features were stored before pseudonymisation and carry a context. */
    static final String SCORE_PENDING = "features is not null and jsonb_typeof(features -> 'context') = 'object' "
            + "and coalesce(features ->> '" + MARKER + "', '') <> '" + VERSION + "'";

    private long scores(Pseudonyms ps, int batch) {
        db.jdbc().sql("select set_config('fraud.pseudonymise', 'on', true)").query(String.class).single();
        long n = 0;
        for (JsonNode r : db.list("select json_build_object('id', id, 'context', features -> 'context')::text from fraud_score "
                + "where " + SCORE_PENDING + " order by id limit ?", batch)) {
            ObjectNode context = ContextPseudonymisation.sanitise(r.get("context"), ps);
            n += db.update("update fraud_score set features = jsonb_set(features, '{context}', cast(? as jsonb)) "
                            + "|| jsonb_build_object('" + MARKER + "', '" + VERSION + "') where id = ?",
                    json.writeValueAsString(context), UUID.fromString(r.get("id").asString()));
        }
        return n;
    }

    private long listEntries(Pseudonyms ps, int batch) {
        long n = 0;
        for (JsonNode r : db.list("select json_build_object('id', id, 'list', fraud_list_id, 'hash', value_hash)::text "
                + "from fraud_list_entry where value_hash not like 'h1:%' order by id limit ?", batch)) {
            UUID id = UUID.fromString(r.get("id").asString());
            String keyed = ps.listHash(r.get("hash").asString());
            boolean exists = db.one("select to_json(1)::text from fraud_list_entry where fraud_list_id = ? and value_hash = ?",
                    UUID.fromString(r.get("list").asString()), keyed).isPresent();
            n += exists ? db.update("delete from fraud_list_entry where id = ?", id)
                    : db.update("update fraud_list_entry set value_hash = ? where id = ?", keyed, id);
        }
        return n;
    }

    private long dropLegacyFeatureKeys(int batch) {
        long n = 0;
        n += db.update("delete from fraud_feature_counter where id in (select id from fraud_feature_counter "
                + "where entity_type = 'BENEFICIARY' and entity_key ~ ? limit ?)", LEGACY_KEY, batch);
        n += db.update("delete from fraud_feature_profile where id in (select id from fraud_feature_profile "
                + "where entity_type = 'BENEFICIARY' and entity_key ~ ? limit ?)", LEGACY_KEY, batch);
        n += db.update("delete from fraud_feature_link where id in (select id from fraud_feature_link "
                + "where from_key ~ ? or to_key ~ ? limit ?)", LEGACY_KEY, LEGACY_KEY, batch);
        return n;
    }

    private long legacyFeatureRows() {
        return count("select count(*) from fraud_feature_counter where entity_type = 'BENEFICIARY' and entity_key ~ '"
                + LEGACY_KEY + "'")
                + count("select count(*) from fraud_feature_profile where entity_type = 'BENEFICIARY' and entity_key ~ '"
                + LEGACY_KEY + "'")
                + count("select count(*) from fraud_feature_link where from_key ~ '" + LEGACY_KEY + "' or to_key ~ '"
                + LEGACY_KEY + "'");
    }

    private static long nz(Long n) {
        return n == null ? 0 : n;
    }

    private long count(String sql) {
        Long n = db.jdbc().sql(sql).query(Long.class).single();
        return n == null ? 0 : n;
    }

    /** Pattern of legacy beneficiary keys (also {@link FeatureEvent.Keys#LEGACY}). */
    static boolean legacy(String key) {
        return key != null && FeatureEvent.Keys.LEGACY.matcher(key).matches();
    }
}
