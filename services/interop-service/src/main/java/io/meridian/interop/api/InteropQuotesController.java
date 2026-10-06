package io.meridian.interop.api;

import io.meridian.interop.api.generated.InteropQuotesApi;
import io.meridian.interop.api.generated.model.InteropQuote;
import io.meridian.interop.api.generated.model.InteropQuoteCreate;
import io.meridian.interop.api.generated.model.InteropQuotesCallbacksRequest;
import io.meridian.interop.api.generated.model.InteropQuotesLookupPartyRequest;
import io.meridian.interop.application.CallbackService;
import io.meridian.interop.application.QuoteService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Party lookup, outbound quotes and the public quote callback from the switch. */
@RestController
public class InteropQuotesController implements InteropQuotesApi {
    private final QuoteService quotes;
    private final CallbackService callbacks;
    private final Actions actions;
    private final HttpServletRequest request;

    public InteropQuotesController(QuoteService quotes, CallbackService callbacks, Actions actions, HttpServletRequest request) {
        this.quotes = quotes;
        this.callbacks = callbacks;
        this.actions = actions;
        this.request = request;
    }

    @Override
    public ResponseEntity<InteropQuote> interopQuotesCreate(String idempotencyKey, InteropQuoteCreate body) {
        return actions.create(idempotencyKey, body, InteropQuote.class, () -> quotes.createOutbound(actions.tree(body)));
    }

    @Override
    public ResponseEntity<Map<String, Object>> interopQuotesLookupParty(String idempotencyKey, InteropQuotesLookupPartyRequest body) {
        JsonNode req = actions.tree(body);
        return actions.objectAction(idempotencyKey, "lookup-party", body, () -> quotes.lookupParty(
                Http.uuid(req, "interop_switch_id"), Http.text(req, "party_type"), Http.text(req, "party_id")));
    }

    /** Public, signature-verified switch callback; the tenant comes from the registered callback URL. */
    @Override
    public ResponseEntity<Map<String, Object>> interopQuotesCallbacks(UUID tenantId, InteropQuotesCallbacksRequest body) {
        return actions.object(callbacks.quoteCallback(tenantId, actions.tree(body), request.getHeader(CallbackService.HEADER)));
    }
}
