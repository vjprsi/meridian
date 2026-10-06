package io.meridian.interop.api;

import io.meridian.interop.api.generated.InteropTransfersApi;
import io.meridian.interop.api.generated.model.InteropTransfer;
import io.meridian.interop.api.generated.model.InteropTransferCreate;
import io.meridian.interop.api.generated.model.InteropTransfersAbortRequest;
import io.meridian.interop.api.generated.model.InteropTransfersCallbacksRequest;
import io.meridian.interop.application.CallbackService;
import io.meridian.interop.application.TransferService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Two-phase transfers: reserve, abort, expiry and the public transfer callback from the switch. */
@RestController
public class InteropTransfersController implements InteropTransfersApi {
    private final TransferService transfers;
    private final CallbackService callbacks;
    private final Actions actions;
    private final HttpServletRequest request;

    public InteropTransfersController(TransferService transfers, CallbackService callbacks, Actions actions,
                                      HttpServletRequest request) {
        this.transfers = transfers;
        this.callbacks = callbacks;
        this.actions = actions;
        this.request = request;
    }

    @Override
    public ResponseEntity<InteropTransfer> interopTransfersCreate(String idempotencyKey, InteropTransferCreate body) {
        return actions.create(idempotencyKey, body, InteropTransfer.class,
                () -> transfers.createOutbound(actions.tree(body), idempotencyKey));
    }

    @Override
    public ResponseEntity<InteropTransfer> interopTransfersAbort(UUID id, String idempotencyKey, InteropTransfersAbortRequest body) {
        return actions.action(idempotencyKey, id, "abort", body, InteropTransfer.class,
                () -> transfers.abortById(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<Map<String, Object>> interopTransfersExpire(String idempotencyKey) {
        return actions.objectAction(idempotencyKey, "expire", null, transfers::expire);
    }

    /** Public, signature-verified switch callback; the tenant comes from the registered callback URL. */
    @Override
    public ResponseEntity<Map<String, Object>> interopTransfersCallbacks(UUID tenantId, InteropTransfersCallbacksRequest body) {
        return actions.object(callbacks.transferCallback(tenantId, actions.tree(body), request.getHeader(CallbackService.HEADER)));
    }
}
