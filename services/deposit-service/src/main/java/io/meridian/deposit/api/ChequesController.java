package io.meridian.deposit.api;

import io.meridian.deposit.api.generated.ChequesApi;
import io.meridian.deposit.api.generated.model.Cheque;
import io.meridian.deposit.api.generated.model.ChequesPresentRequest;
import io.meridian.deposit.api.generated.model.ChequesReturnRequest;
import io.meridian.deposit.api.generated.model.ChequesStopRequest;
import io.meridian.deposit.application.ChequeService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Cheques: stop payment, presentment (clearing or unpaid return), return. */
@RestController
public class ChequesController implements ChequesApi {
    private final ChequeService cheques;
    private final Actions actions;

    public ChequesController(ChequeService cheques, Actions actions) {
        this.cheques = cheques;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<Cheque> chequesStop(UUID id, String idempotencyKey, ChequesStopRequest body) {
        return actions.action(idempotencyKey, id, "stop", body, Cheque.class,
                () -> cheques.stop(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<Cheque> chequesPresent(UUID id, String idempotencyKey, ChequesPresentRequest body) {
        JsonNode req = actions.tree(body);
        String image = Http.text(req, "image_document_id");
        return actions.action(idempotencyKey, id, "present", body, Cheque.class,
                () -> cheques.present(id, Http.decimal(req, "amount"), Http.text(req, "currency"),
                        Http.text(req, "payee_name"), image == null ? null : UUID.fromString(image), null, idempotencyKey));
    }

    @Override
    public ResponseEntity<Cheque> chequesReturn(UUID id, String idempotencyKey, ChequesReturnRequest body) {
        return actions.action(idempotencyKey, id, "return", body, Cheque.class,
                () -> cheques.returnCheque(id, Http.text(actions.tree(body), "return_reason"), idempotencyKey));
    }
}
