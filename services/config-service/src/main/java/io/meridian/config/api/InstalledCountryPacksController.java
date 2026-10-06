package io.meridian.config.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.config.api.generated.InstalledCountryPacksApi;
import io.meridian.config.api.generated.model.InstalledCountryPacksUninstallRequest;
import io.meridian.config.api.generated.model.InstalledCountryPacksUpgradeRequest;
import io.meridian.config.api.generated.model.TenantCountryPack;
import io.meridian.config.application.CountryPackService;
import io.meridian.web.idempotency.Idempotency;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/** Installed country packs: upgrade to a newer version of the same country, uninstall. */
@RestController
public class InstalledCountryPacksController implements InstalledCountryPacksApi {
    private final CountryPackService packs;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public InstalledCountryPacksController(CountryPackService packs, Idempotency idempotency, JsonMapper json) {
        this.packs = packs;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<TenantCountryPack> installedCountryPacksUpgrade(UUID id, String idempotencyKey,
                                                                          InstalledCountryPacksUpgradeRequest body) {
        TenantContext.require();
        UUID target = body.getCountryPackId();
        return idempotency.execute(idempotencyKey, Responses.request("upgrade", id, body), TenantCountryPack.class,
                () -> Responses.row(json, HttpStatus.OK, packs.upgrade(id, target), TenantCountryPack.class));
    }

    @Override
    public ResponseEntity<TenantCountryPack> installedCountryPacksUninstall(UUID id, String idempotencyKey,
                                                                            InstalledCountryPacksUninstallRequest body) {
        TenantContext.require();
        String reason = body.getReason();
        return idempotency.execute(idempotencyKey, Responses.request("uninstall", id, body), TenantCountryPack.class,
                () -> Responses.row(json, HttpStatus.OK, packs.uninstall(id, reason), TenantCountryPack.class));
    }
}
