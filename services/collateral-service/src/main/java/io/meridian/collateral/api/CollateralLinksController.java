package io.meridian.collateral.api;

import io.meridian.collateral.api.generated.CollateralLinksApi;
import io.meridian.collateral.api.generated.model.CollateralLink;
import io.meridian.collateral.api.generated.model.CollateralLinkCreate;
import io.meridian.collateral.api.generated.model.CollateralLinkUpdate;
import io.meridian.collateral.api.generated.model.CollateralLinksReleaseRequest;
import io.meridian.collateral.application.LinkService;
import io.meridian.common.tenancy.TenantContext;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Links of collateral to loans and credit lines with LTV calculation and alerts. */
@RestController
public class CollateralLinksController implements CollateralLinksApi {
    private final LinkService links;
    private final Actions actions;

    public CollateralLinksController(LinkService links, Actions actions) {
        this.links = links;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<CollateralLink> collateralLinksCreate(String idempotencyKey, CollateralLinkCreate body) {
        return actions.create(idempotencyKey, body, CollateralLink.class, () -> links.create(body));
    }

    @Override
    public ResponseEntity<CollateralLink> collateralLinksUpdate(UUID id, String ifMatch, CollateralLinkUpdate body) {
        TenantContext.require();
        return ResponseEntity.ok(actions.model(links.update(id, ifMatch, body), CollateralLink.class));
    }

    @Override
    public ResponseEntity<CollateralLink> collateralLinksRelease(UUID id, String idempotencyKey,
                                                                CollateralLinksReleaseRequest body) {
        return actions.action(idempotencyKey, id, "release", body, CollateralLink.class,
                () -> links.release(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<CollateralLink> collateralLinksRecalculateLtv(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "recalculate-ltv", null, CollateralLink.class,
                () -> links.recalculateLink(id));
    }
}
