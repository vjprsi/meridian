package io.meridian.loanorigination.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.loanorigination.api.generated.ESignatureRequestsApi;
import io.meridian.loanorigination.api.generated.model.ESignatureRequest;
import io.meridian.loanorigination.api.generated.model.ESignatureRequestsCallbacksEventsRequest;
import io.meridian.loanorigination.api.generated.model.ESignatureRequestsVoidRequest;
import io.meridian.loanorigination.application.BookingService;
import io.meridian.loanorigination.application.CallbackSecurity;
import io.meridian.loanorigination.application.SignatureService;
import io.meridian.loanorigination.config.OriginationConfig;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** E-signature envelopes: send, resend, void and the public provider callback (which books the signed loan). */
@RestController
public class ESignatureRequestsController implements ESignatureRequestsApi {
    private final SignatureService signatures;
    private final BookingService booking;
    private final CallbackSecurity callbacks;
    private final Actions actions;
    private final HttpServletRequest request;

    public ESignatureRequestsController(SignatureService signatures, BookingService booking, CallbackSecurity callbacks,
                                        Actions actions, HttpServletRequest request) {
        this.signatures = signatures;
        this.booking = booking;
        this.callbacks = callbacks;
        this.actions = actions;
        this.request = request;
    }

    @Override
    public ResponseEntity<ESignatureRequest> eSignatureRequestsSend(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "send", null, ESignatureRequest.class, () -> signatures.send(id));
    }

    @Override
    public ResponseEntity<ESignatureRequest> eSignatureRequestsResend(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "resend", null, ESignatureRequest.class, () -> signatures.resend(id));
    }

    @Override
    public ResponseEntity<ESignatureRequest> eSignatureRequestsVoid(UUID id, String idempotencyKey,
                                                                   ESignatureRequestsVoidRequest body) {
        String reason = Http.text(actions.tree(body), "reason");
        return actions.action(idempotencyKey, id, "void", body, ESignatureRequest.class,
                () -> signatures.voidEnvelope(id, reason));
    }

    /**
     * Public provider callback: HMAC verified over the raw bytes, tenant resolved from the envelope id, the status
     * applied as that tenant; a completed signature then books the loan in loan-service (after the callback's own
     * transaction committed, so a loan-service outage never loses the signature).
     */
    @Override
    public ResponseEntity<Map<String, Object>> eSignatureRequestsCallbacksEvents(String provider,
                                                                                ESignatureRequestsCallbacksEventsRequest body) {
        callbacks.verify(OriginationConfig.currentBody(), request.getHeader(CallbackSecurity.SIGNATURE_HEADER));
        JsonNode req = actions.tree(body);
        CallbackSecurity.Route route = callbacks.route("E_SIGNATURE", provider, Http.text(req, "provider_envelope_id"));
        try {
            return ResponseEntity.ok(TenantContext.callAs(route.tenantId(), "esign-callback:" + provider, () -> {
                SignatureService.CallbackResult r = signatures.callback(provider, route.entityId(), req);
                Map<String, Object> out = new LinkedHashMap<>(r.body());
                if (r.signed()) {
                    ObjectNode app = booking.book(r.applicationId(), false);
                    out.put("application_status", app.path("status").asString());
                    if (app.hasNonNull("loan_id")) {
                        out.put("loan_id", app.get("loan_id").asString());
                    }
                    if (app.hasNonNull("booking_error")) {
                        out.put("booking_error", app.get("booking_error").asString());
                    }
                }
                return out;
            }));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
