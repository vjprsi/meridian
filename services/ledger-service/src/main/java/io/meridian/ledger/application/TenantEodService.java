package io.meridian.ledger.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tenant-wide end of day: runs {@link LedgerOpsService#endOfDay} for every ledger of the tenant that is not archived
 * (optionally only those of one legal entity, or one ledger), each ledger in its own transaction. A ledger that
 * already has a SUCCEEDED run for the business date is skipped, so the business-day job can retry the whole call
 * after a partial failure. Any failed ledger makes the call fail (422 {@code ledger_eod_failed}) after the others ran.
 */
@Service
public class TenantEodService {
    private static final Logger log = LoggerFactory.getLogger(TenantEodService.class);

    private final LedgerOpsService ops;
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public TenantEodService(LedgerOpsService ops, JdbcClient jdbc, JsonMapper json) {
        this.ops = ops;
        this.jdbc = jdbc;
        this.json = json;
    }

    public ObjectNode endOfDay(LocalDate businessDate, UUID legalEntityId, UUID ledgerId) {
        TenantContext.require();
        List<UUID> ledgers = jdbc.sql("""
                select id from ledger where status <> 'ARCHIVED'
                  and (cast(? as uuid) is null or legal_entity_id = cast(? as uuid))
                  and (cast(? as uuid) is null or id = cast(? as uuid))
                order by code, id""")
                .params(legalEntityId, legalEntityId, ledgerId, ledgerId).query(UUID.class).list();
        if (ledgerId != null && ledgers.isEmpty()) {
            throw ApiException.notFound("Ledger", ledgerId);
        }
        ObjectNode out = json.createObjectNode();
        out.put("business_date", businessDate.toString());
        if (legalEntityId != null) {
            out.put("legal_entity_id", legalEntityId.toString());
        }
        ArrayNode runs = out.putArray("runs");
        ArrayNode skipped = out.putArray("skipped");
        ArrayNode failed = out.putArray("failed");
        for (UUID id : ledgers) {
            boolean done = jdbc.sql("select exists(select 1 from ledger_eod_run where ledger_id = ? and business_date = ? and status = 'SUCCEEDED')")
                    .params(id, businessDate).query(Boolean.class).single();
            if (done) {
                skipped.addObject().put("ledger_id", id.toString()).put("reason", "already_closed");
                continue;
            }
            try {
                runs.add(json.readTree(ops.endOfDay(id, businessDate)));
            } catch (RuntimeException e) {
                log.warn("ledger EOD failed for ledger {} on {}: {}", id, businessDate, e.getMessage());
                failed.addObject().put("ledger_id", id.toString()).put("error", String.valueOf(e.getMessage()));
            }
        }
        out.put("ledgers", ledgers.size());
        if (!failed.isEmpty()) {
            throw ApiException.unprocessable("ledger_eod_failed", "End of day failed for " + failed.size() + " of "
                    + ledgers.size() + " ledgers on " + businessDate + ": " + failed);
        }
        return out;
    }
}
