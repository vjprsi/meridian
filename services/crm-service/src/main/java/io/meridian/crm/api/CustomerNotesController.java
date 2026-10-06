package io.meridian.crm.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.crm.api.generated.CustomerNotesApi;
import io.meridian.crm.api.generated.model.CustomerNote;
import io.meridian.crm.api.generated.model.CustomerNoteCreate;
import io.meridian.crm.api.generated.model.CustomerNoteUpdate;
import io.meridian.crm.application.InteractionService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Customer notes; alert and vulnerability notes are pinned to the customer 360 by default. */
@RestController
public class CustomerNotesController implements CustomerNotesApi {
    private final InteractionService notes;
    private final Http http;

    public CustomerNotesController(InteractionService notes, Http http) {
        this.notes = notes;
        this.http = http;
    }

    @Override
    public ResponseEntity<CustomerNote> customerNotesCreate(String idempotencyKey, CustomerNoteCreate body) {
        return http.created(idempotencyKey, "notes.create", body, CustomerNote.class, () -> notes.createNote(body));
    }

    @Override
    public ResponseEntity<CustomerNote> customerNotesUpdate(UUID id, String ifMatch, CustomerNoteUpdate body) {
        TenantContext.require();
        return http.withEtag(notes.updateNote(id, ifMatch, body), CustomerNote.class);
    }
}
