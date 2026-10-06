package io.meridian.notification.api;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.notification.api.generated.NotificationPreferencesApi;
import io.meridian.notification.api.generated.model.NotificationPreference;
import io.meridian.notification.api.generated.model.NotificationPreferenceCreate;
import io.meridian.notification.api.generated.model.NotificationPreferenceUpdate;
import io.meridian.notification.application.PreferenceService;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Recipient preferences: validation (mandatory categories, quiet hours, timezone) and change events. */
@RestController
public class NotificationPreferencesController implements NotificationPreferencesApi {
    private final PreferenceService preferences;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public NotificationPreferencesController(PreferenceService preferences, CrudEngine crud, Idempotency idempotency,
                                             JsonMapper json) {
        this.preferences = preferences;
        this.crud = crud;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<NotificationPreference> notificationPreferencesCreate(String idempotencyKey,
                                                                               NotificationPreferenceCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, NotificationPreference.class, () -> {
            String row = preferences.create(body);
            return Responses.created(crud.read(row, NotificationPreference.class), json.readTree(row));
        });
    }

    @Override
    public ResponseEntity<NotificationPreference> notificationPreferencesUpdate(UUID id, String ifMatch,
                                                                               NotificationPreferenceUpdate body) {
        TenantContext.require();
        String row = preferences.update(id, body, version(ifMatch));
        JsonNode node = json.readTree(row);
        return ResponseEntity.ok().eTag("\"" + node.get("row_version").asString() + "\"")
                .body(crud.read(row, NotificationPreference.class));
    }

    private static int version(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw ApiException.badRequest("if_match_required", "If-Match header with the current row_version is required");
        }
        try {
            return Integer.parseInt(ifMatch.replace("W/", "").replace("\"", "").trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("invalid_if_match", "If-Match must be the resource's row_version");
        }
    }
}
