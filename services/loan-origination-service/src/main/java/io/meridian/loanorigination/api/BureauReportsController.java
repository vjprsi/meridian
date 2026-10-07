package io.meridian.loanorigination.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.loanorigination.api.generated.BureauReportsApi;
import io.meridian.loanorigination.api.generated.model.BureauReportsCallbacksEventsRequest;
import io.meridian.loanorigination.application.BureauService;
import io.meridian.loanorigination.application.CallbackSecurity;
import io.meridian.loanorigination.config.OriginationConfig;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

/** Bureau reports (read via generic CRUD) and the public asynchronous bureau callback. */
@RestController
public class BureauReportsController implements BureauReportsApi {
    private final BureauService bureau;
    private final CallbackSecurity callbacks;
    private final Actions actions;
    private final HttpServletRequest request;

    public BureauReportsController(BureauService bureau, CallbackSecurity callbacks, Actions actions,
                                   HttpServletRequest request) {
        this.bureau = bureau;
        this.callbacks = callbacks;
        this.actions = actions;
        this.request = request;
    }

    /**
     * Public provider callback (no token, tenant header or Idempotency-Key): the HMAC over the raw bytes is verified
     * first, then the owning tenant is resolved from the bureau reference and the report completed as that tenant.
     * Redeliveries are acknowledged with outcome DUPLICATE.
     */
    @Override
    public ResponseEntity<Map<String, Object>> bureauReportsCallbacksEvents(String provider,
                                                                           BureauReportsCallbacksEventsRequest body) {
        callbacks.verify(provider, OriginationConfig.currentBody(), request.getHeader(CallbackSecurity.SIGNATURE_HEADER));
        ObjectNode req = (ObjectNode) actions.tree(body);
        CallbackSecurity.Route route = callbacks.route("BUREAU", provider, Http.text(req, "bureau_reference"));
        try {
            return ResponseEntity.ok(TenantContext.callAs(route.tenantId(), "bureau-callback:" + provider,
                    () -> bureau.callback(provider, route.entityId(), req)));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
