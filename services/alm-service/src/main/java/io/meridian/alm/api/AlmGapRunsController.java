package io.meridian.alm.api;

import io.meridian.alm.api.generated.AlmGapRunsApi;
import io.meridian.alm.api.generated.model.AlmGapBucketPage;
import io.meridian.alm.api.generated.model.AlmGapRun;
import io.meridian.alm.api.generated.model.AlmGapRunCreate;
import io.meridian.alm.application.GapService;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Repricing gap runs: computed on create; buckets as a child list. */
@RestController
public class AlmGapRunsController implements AlmGapRunsApi {
    private final GapService service;
    private final Actions actions;
    private final CrudEngine crud;

    public AlmGapRunsController(GapService service, Actions actions, CrudEngine crud) {
        this.service = service;
        this.actions = actions;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<AlmGapRun> almGapRunsCreate(String idempotencyKey, AlmGapRunCreate body) {
        return actions.create(idempotencyKey, body, AlmGapRun.class, () -> service.run(body, actions.tree(body)));
    }

    @Override
    public ResponseEntity<AlmGapBucketPage> almGapRunsBuckets(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        crud.get("alm_gap_run", AlmGapRun.class, Crud.params().with("id", id)); // 404 for an unknown run
        return crud.list("alm_gap_bucket", AlmGapBucketPage.class,
                Crud.params().with("alm_gap_run_id", id).with("limit", limit).with("cursor", cursor));
    }
}
