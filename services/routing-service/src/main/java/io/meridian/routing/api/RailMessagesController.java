package io.meridian.routing.api;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.routing.api.generated.RailMessagesApi;
import io.meridian.routing.api.generated.model.RailMessage;
import io.meridian.routing.api.generated.model.RailMessagesPacs008Request;
import io.meridian.routing.api.generated.model.RailMessagesReplayRequest;
import io.meridian.routing.application.InboundService;
import io.meridian.routing.application.MessageService;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Rail messages: outbound pacs.008 building, raw payload (audit) and replay of inbound messages. */
@RestController
public class RailMessagesController implements RailMessagesApi {
    private final MessageService messages;
    private final InboundService inbound;
    private final Actions actions;

    public RailMessagesController(MessageService messages, InboundService inbound, Actions actions) {
        this.messages = messages;
        this.inbound = inbound;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<RailMessage> railMessagesPacs008(String idempotencyKey, RailMessagesPacs008Request body) {
        JsonNode req = actions.tree(body);
        String rail = Http.text(req, "payment_rail_id");
        if (rail == null) {
            throw ApiException.unprocessable("payment_rail_id_required", "payment_rail_id is required");
        }
        return actions.action(idempotencyKey, null, "pacs008", body, RailMessage.class,
                () -> messages.pacs008(UUID.fromString(rail), req.get("payment")));
    }

    @Override
    public ResponseEntity<String> railMessagesPayload(UUID id) {
        TenantContext.require();
        String xml = messages.payload(id);
        Http.write(200, "application/xml;charset=UTF-8", xml.getBytes(StandardCharsets.UTF_8), null, null);
        return null;
    }

    @Override
    public ResponseEntity<Map<String, Object>> railMessagesReplay(UUID id, String idempotencyKey,
                                                                RailMessagesReplayRequest body) {
        String reason = Http.text(actions.tree(body), "reason");
        if (reason == null || reason.isBlank()) {
            throw ApiException.unprocessable("reason_required", "reason is required");
        }
        return actions.object(idempotencyKey, id, "replay", body, () -> inbound.replay(id, reason));
    }
}
