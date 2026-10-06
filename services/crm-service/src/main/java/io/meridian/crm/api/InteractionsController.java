package io.meridian.crm.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.crm.api.generated.InteractionsApi;
import io.meridian.crm.api.generated.model.Interaction;
import io.meridian.crm.api.generated.model.InteractionCreate;
import io.meridian.crm.api.generated.model.InteractionUpdate;
import io.meridian.crm.application.InteractionService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Interactions on every channel with sentiment and intent detection; each one lands on the customer timeline. */
@RestController
public class InteractionsController implements InteractionsApi {
    private final InteractionService interactions;
    private final Http http;

    public InteractionsController(InteractionService interactions, Http http) {
        this.interactions = interactions;
        this.http = http;
    }

    @Override
    public ResponseEntity<Interaction> interactionsCreate(String idempotencyKey, InteractionCreate body) {
        return http.created(idempotencyKey, "interactions.create", body, Interaction.class, () -> interactions.create(body));
    }

    @Override
    public ResponseEntity<Interaction> interactionsUpdate(UUID id, String ifMatch, InteractionUpdate body) {
        TenantContext.require();
        return http.withEtag(interactions.update(id, ifMatch, body), Interaction.class);
    }
}
