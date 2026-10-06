package io.meridian.tenant.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.tenant.api.generated.SettingsApi;
import io.meridian.tenant.api.generated.model.TenantSetting;
import io.meridian.tenant.api.generated.model.TenantSettingCreate;
import io.meridian.tenant.api.generated.model.TenantSettingUpdate;
import io.meridian.tenant.application.SettingsService;
import io.meridian.web.idempotency.Idempotency;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/** Typed tenant settings: values validated against their value_type; every change is an event. */
@RestController
public class SettingsController implements SettingsApi {
    private final SettingsService settings;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public SettingsController(SettingsService settings, Idempotency idempotency, JsonMapper json) {
        this.settings = settings;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<TenantSetting> settingsCreate(String idempotencyKey, TenantSettingCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, TenantSetting.class,
                () -> Responses.of(HttpStatus.CREATED, settings.createSetting(body), TenantSetting.class, json));
    }

    @Override
    public ResponseEntity<TenantSetting> settingsUpdate(UUID id, String ifMatch, TenantSettingUpdate body) {
        TenantContext.require();
        return settings.updateSetting(id, body, ifMatch, TenantSetting.class);
    }

    @Override
    public ResponseEntity<Void> settingsDelete(UUID id) {
        TenantContext.require();
        return settings.deleteSetting(id);
    }
}
