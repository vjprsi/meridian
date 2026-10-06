package io.meridian.liquidity.api;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.liquidity.api.generated.LiquidityLadderRunsApi;
import io.meridian.liquidity.api.generated.model.LiquidityBucketPage;
import io.meridian.liquidity.api.generated.model.LiquidityLadderRun;
import io.meridian.liquidity.api.generated.model.LiquidityLadderRunCreate;
import io.meridian.liquidity.application.LadderService;
import io.meridian.liquidity.infrastructure.Rows;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Maturity ladder runs, computed synchronously on create; buckets are listed in ladder order. */
@RestController
public class LiquidityLadderRunsController implements LiquidityLadderRunsApi {
    private final LadderService ladders;
    private final Rows rows;
    private final Http http;

    public LiquidityLadderRunsController(LadderService ladders, Rows rows, Http http) {
        this.ladders = ladders;
        this.rows = rows;
        this.http = http;
    }

    @Override
    public ResponseEntity<LiquidityLadderRun> liquidityLadderRunsCreate(String idempotencyKey,
                                                                        LiquidityLadderRunCreate body) {
        return http.created(idempotencyKey, "ladder-run-create", body, LiquidityLadderRun.class, () -> ladders.create(body));
    }

    /** Buckets in bucket_order; the cursor is the last bucket_order returned. */
    @Override
    public ResponseEntity<LiquidityBucketPage> liquidityLadderRunsBuckets(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        rows.get("liquidity_ladder_run", id);
        int max = limit == null ? 50 : Math.max(1, Math.min(200, limit));
        int after = 0;
        if (cursor != null && !cursor.isBlank()) {
            try {
                after = Integer.parseInt(cursor);
            } catch (NumberFormatException e) {
                throw ApiException.badRequest("invalid_cursor", "cursor is not valid");
            }
        }
        List<ObjectNode> found = rows.query("""
                select row_to_json(b)::text from liquidity_bucket b where liquidity_ladder_run_id = ? and bucket_order > ?
                order by bucket_order limit ?""", id, after, max + 1);
        boolean more = found.size() > max;
        List<ObjectNode> data = more ? found.subList(0, max) : found;
        ObjectNode page = rows.object();
        ArrayNode arr = page.putArray("data");
        data.forEach(arr::add);
        page.put("has_more", more);
        if (more) {
            page.put("next_cursor", String.valueOf(data.getLast().get("bucket_order").asInt()));
        } else {
            page.putNull("next_cursor");
        }
        return ResponseEntity.ok(rows.as(page, LiquidityBucketPage.class));
    }
}
