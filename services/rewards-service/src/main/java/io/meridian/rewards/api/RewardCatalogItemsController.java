package io.meridian.rewards.api;

import io.meridian.rewards.api.generated.RewardCatalogItemsApi;
import io.meridian.rewards.api.generated.model.RewardCatalogItem;
import io.meridian.rewards.api.generated.model.RewardCatalogItemCreate;
import io.meridian.rewards.api.generated.model.RewardCatalogItemUpdate;
import io.meridian.rewards.application.ProgramService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Redemption catalogue; items with redemptions are deactivated rather than deleted. */
@RestController
public class RewardCatalogItemsController implements RewardCatalogItemsApi {
    private final ProgramService programs;
    private final Http http;

    public RewardCatalogItemsController(ProgramService programs, Http http) {
        this.programs = programs;
        this.http = http;
    }

    @Override
    public ResponseEntity<RewardCatalogItem> rewardCatalogItemsCreate(String idempotencyKey, RewardCatalogItemCreate body) {
        return http.created(idempotencyKey, "item.create", body, RewardCatalogItem.class, () -> programs.createItem(body));
    }

    @Override
    public ResponseEntity<RewardCatalogItem> rewardCatalogItemsUpdate(UUID id, String ifMatch, RewardCatalogItemUpdate body) {
        return http.withEtag(programs.updateItem(id, body, ifMatch), RewardCatalogItem.class);
    }

    @Override
    public ResponseEntity<Void> rewardCatalogItemsDelete(UUID id) {
        programs.deleteItem(id);
        return ResponseEntity.noContent().build();
    }
}
