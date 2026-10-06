package io.meridian.channel.api;

import io.meridian.channel.api.generated.SmsMessagesApi;
import io.meridian.channel.api.generated.model.SmsMessagesDeliveryReportsRequest;
import io.meridian.channel.api.generated.model.SmsMessagesInboundRequest;
import io.meridian.channel.application.SmsService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** SMS log plus the signed gateway webhooks for inbound messages and delivery receipts. */
@RestController
public class SmsMessagesController implements SmsMessagesApi {
    private final SmsService sms;
    private final GatewayCallbacks callbacks;
    private final Actions actions;

    public SmsMessagesController(SmsService sms, GatewayCallbacks callbacks, Actions actions) {
        this.sms = sms;
        this.callbacks = callbacks;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<Map<String, Object>> smsMessagesInbound(UUID tenantId, SmsMessagesInboundRequest body) {
        JsonNode req = actions.tree(body);
        return actions.object(callbacks.run(tenantId, body, "sms-gateway", () -> sms.inbound(Http.text(req, "msisdn"),
                Http.text(req, "short_code"), Http.text(req, "body"), Http.text(req, "provider_message_id"),
                Http.text(req, "mno"))));
    }

    @Override
    public ResponseEntity<Map<String, Object>> smsMessagesDeliveryReports(UUID tenantId, SmsMessagesDeliveryReportsRequest body) {
        JsonNode req = actions.tree(body);
        return actions.object(callbacks.run(tenantId, body, "sms-gateway", () -> sms.deliveryReport(
                Http.text(req, "provider_message_id"), Http.text(req, "status"), Http.text(req, "failure_reason"))));
    }
}
