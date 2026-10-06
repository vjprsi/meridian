package io.meridian.notification.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.notification.api.generated.NotificationProvidersApi;
import io.meridian.notification.api.generated.model.NotificationProvider;
import io.meridian.notification.api.generated.model.NotificationProviderCreate;
import io.meridian.notification.api.generated.model.NotificationProviderUpdate;
import io.meridian.notification.api.generated.model.NotificationProvidersTestRequest;
import io.meridian.notification.application.ProviderService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/** Delivery providers (failover by priority) and their test endpoint. */
@RestController
public class NotificationProvidersController implements NotificationProvidersApi {
    private final ProviderService providers;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public NotificationProvidersController(ProviderService providers, CrudEngine crud, Idempotency idempotency,
                                           JsonMapper json) {
        this.providers = providers;
        this.crud = crud;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<NotificationProvider> notificationProvidersCreate(String idempotencyKey,
                                                                           NotificationProviderCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, NotificationProvider.class, () -> {
            String row = providers.create(body);
            return Responses.created(crud.read(row, NotificationProvider.class), json.readTree(row));
        });
    }

    @Override
    public ResponseEntity<NotificationProvider> notificationProvidersUpdate(UUID id, String ifMatch,
                                                                           NotificationProviderUpdate body) {
        ProviderService.validate(json.valueToTree(body));
        return crud.update("notification_provider", NotificationProvider.class, body, ifMatch, Crud.params().with("id", id));
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public ResponseEntity<Void> notificationProvidersTest(UUID id, String idempotencyKey,
                                                          NotificationProvidersTestRequest body) {
        TenantContext.require();
        String destination = json.valueToTree(body).get("destination").asString();
        ResponseEntity<Map> r = idempotency.execute(idempotencyKey,
                Map.of("op", "provider-test", "id", id.toString(), "destination", destination), Map.class,
                () -> ResponseEntity.ok(providers.test(id, destination)));
        return (ResponseEntity) r;
    }
}
