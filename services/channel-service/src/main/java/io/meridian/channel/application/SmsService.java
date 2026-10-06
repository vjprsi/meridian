package io.meridian.channel.application;

import io.meridian.channel.config.ChannelProperties;
import io.meridian.channel.domain.SmsParser;
import io.meridian.channel.domain.UssdEngine;
import io.meridian.channel.infrastructure.Rows;
import io.meridian.channel.infrastructure.WalletActions;
import io.meridian.common.error.ApiException;
import io.meridian.web.client.ServiceClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * SMS channel: keyword commands from inbound messages (logged with PINs masked), replies sent through
 * notification-service and logged as OUTBOUND messages, and gateway delivery reports that only move a message's
 * status forward. Inbound handling is idempotent on the gateway message id.
 */
@Service
public class SmsService {
    private static final Logger log = LoggerFactory.getLogger(SmsService.class);
    static final String TABLE = "sms_message";
    static final Map<String, Integer> RANK = Map.of("RECEIVED", 0, "QUEUED", 0, "SENT", 1, "DELIVERED", 2, "FAILED", 2);

    private final Rows rows;
    private final WalletActions actions;
    private final ServiceClient client;
    private final ChannelProperties props;
    private final Events events;

    public SmsService(Rows rows, WalletActions actions, ServiceClient client, ChannelProperties props, Events events) {
        this.rows = rows;
        this.actions = actions;
        this.client = client;
        this.props = props;
        this.events = events;
    }

    @Transactional
    public ObjectNode inbound(String msisdn, String shortCode, String body, String providerMessageId, String mno) {
        if (providerMessageId != null) {
            Optional<ObjectNode> seen = rows.queryOne("""
                    select row_to_json(m)::text from sms_message m where direction = 'INBOUND' and provider_message_id = ?""",
                    providerMessageId);
            if (seen.isPresent()) {
                return result(seen.get(), rows.queryOne(
                        "select row_to_json(m)::text from sms_message m where reply_to_id = ?", Rows.uuid(seen.get(), "id"))
                        .orElse(null), null);
            }
        }
        SmsParser.Parsed parsed = SmsParser.parse(body);
        Optional<ObjectNode> command = parsed.keyword().isEmpty() ? Optional.empty() : rows.queryOne("""
                select row_to_json(c)::text from sms_command c
                where upper(c.keyword) = ? and c.short_code = ? and c.is_active order by (c.language = 'en') desc limit 1""",
                parsed.keyword(), shortCode);
        ObjectNode in = rows.object();
        in.put("direction", "INBOUND");
        in.put("msisdn", msisdn);
        in.put("short_code", shortCode);
        in.put("body", SmsParser.mask(body, command.map(c -> Rows.str(c, "syntax")).orElse(null),
                command.map(c -> Rows.bool(c, "requires_pin")).orElse(true)));
        in.put("status", "RECEIVED");
        if (mno != null) {
            in.put("mno", mno);
        }
        in.put("provider", props.smsProvider());
        if (providerMessageId != null) {
            in.put("provider_message_id", providerMessageId);
        }
        command.ifPresent(c -> in.put("sms_command_id", Rows.str(c, "id")));
        ObjectNode inbound = rows.insert(TABLE, in);
        events.publish("wallets.sms_message.received", TABLE, inbound);

        String reply;
        UUID txn = null;
        if (command.isEmpty()) {
            reply = help(shortCode, parsed.keyword());
        } else {
            ObjectNode c = command.get();
            Map<String, String> args = SmsParser.bind(parsed, Rows.str(c, "syntax"));
            String action = Rows.str(c, "action_code");
            if ("HELP".equals(action)) {
                reply = Rows.str(c, "help_text") != null ? Rows.str(c, "help_text") : help(shortCode, null);
            } else {
                JsonNode wallet = actions.lookup(msisdn);
                if (wallet == null) {
                    reply = "You are not registered for mobile money. Visit an agent to open a wallet.";
                } else if (Rows.bool(c, "requires_pin") && args.get("pin") == null) {
                    reply = "Usage: " + (Rows.str(c, "syntax") == null ? Rows.str(c, "keyword") : Rows.str(c, "syntax"));
                } else {
                    Map<String, String> ctx = new LinkedHashMap<>(args);
                    ctx.remove("pin");
                    ctx.put("_wallet_id", Rows.str(wallet, "id"));
                    ctx.put("_currency", Rows.str(wallet, "currency"));
                    ctx.put("_msisdn", msisdn);
                    ctx.put("_channel", "SMS");
                    UssdEngine.Result r = actions.run(action, ctx, args.get("pin"), "sms-" + Rows.str(inbound, "id"));
                    reply = r.message();
                    txn = r.walletTransactionId();
                }
            }
        }
        if (txn != null) {
            rows.execute("update sms_message set wallet_transaction_id = ? where id = ?", txn, Rows.uuid(inbound, "id"));
        }
        ObjectNode out = send(msisdn, shortCode, reply, Rows.uuid(inbound, "id"), txn);
        return result(rows.get(TABLE, Rows.uuid(inbound, "id")), out, command.map(c -> Rows.str(c, "action_code")).orElse(null));
    }

    private String help(String shortCode, String unknown) {
        List<String> keywords = rows.scalar("""
                select string_agg(distinct upper(keyword), ', ' order by upper(keyword)) from sms_command
                where short_code = ? and is_active""", String.class, shortCode).map(List::of).orElse(List.of());
        String list = keywords.isEmpty() || keywords.getFirst() == null ? "" : " Commands: " + keywords.getFirst() + ".";
        return (unknown == null || unknown.isEmpty() ? "" : "Unknown command " + unknown.toUpperCase(Locale.ROOT) + ".") + list;
    }

    /** Logs an outbound reply and sends it through notification-service; failures are recorded on the row. */
    ObjectNode send(String msisdn, String shortCode, String text, UUID replyTo, UUID txn) {
        ObjectNode o = rows.object();
        o.put("direction", "OUTBOUND");
        o.put("msisdn", msisdn);
        o.put("short_code", shortCode);
        o.put("body", text);
        o.put("status", "QUEUED");
        o.put("provider", props.smsProvider());
        o.put("segments", Math.max(1, (text.length() + 152) / 153));
        if (replyTo != null) {
            o.put("reply_to_id", replyTo.toString());
        }
        if (txn != null) {
            o.put("wallet_transaction_id", txn.toString());
        }
        ObjectNode row = rows.insert(TABLE, o);
        UUID id = Rows.uuid(row, "id");
        ObjectNode n = rows.object();
        n.put("recipient_type", "CUSTOMER");
        n.put("channel", "SMS");
        n.put("destination", msisdn);
        n.put("category", "TRANSACTIONAL");
        n.put("body", text);
        n.put("source_event_type", "wallets.sms_message.received");
        n.put("source_event_id", replyTo == null ? id.toString() : replyTo.toString());
        try {
            JsonNode r = client.post("notification-service", "/v1/notifications", n, "sms-out-" + id);
            String nid = r.path("id").asString(null);
            rows.execute("""
                    update sms_message set status = 'SENT', sent_at = now(), notification_id = ?::uuid,
                           provider_message_id = ? where id = ?""", nid, nid, id);
        } catch (ApiException e) {
            log.warn("SMS reply {} could not be sent: {}", id, e.getMessage());
            rows.execute("update sms_message set status = 'FAILED', failure_reason = ? where id = ?",
                    trim(e.getMessage()), id);
        }
        return rows.get(TABLE, id);
    }

    private ObjectNode result(ObjectNode inbound, ObjectNode reply, String command) {
        ObjectNode out = rows.object();
        out.put("inbound_id", Rows.str(inbound, "id"));
        out.put("reply_id", reply == null ? null : Rows.str(reply, "id"));
        out.put("command", command != null ? command
                : inbound.hasNonNull("sms_command_id") ? "MATCHED" : "UNKNOWN");
        out.put("reply", reply == null ? null : Rows.str(reply, "body"));
        out.put("reply_status", reply == null ? null : Rows.str(reply, "status"));
        return out;
    }

    /** Gateway receipt: the status only moves forward (QUEUED -> SENT -> DELIVERED / FAILED). */
    @Transactional
    public ObjectNode deliveryReport(String providerMessageId, String status, String failureReason) {
        ObjectNode m = rows.queryOne("""
                select row_to_json(m)::text from sms_message m where provider_message_id = ? and direction = 'OUTBOUND'
                for update""", providerMessageId)
                .orElseThrow(() -> ApiException.notFound("sms message", providerMessageId));
        UUID id = Rows.uuid(m, "id");
        String current = Rows.str(m, "status");
        if (!RANK.containsKey(status) || "RECEIVED".equals(status)) {
            throw ApiException.unprocessable("invalid_status", "Delivery reports carry SENT, DELIVERED or FAILED");
        }
        boolean forward = RANK.get(status) > RANK.getOrDefault(current, 0);
        if (forward) {
            rows.execute("""
                    update sms_message set status = ?,
                           sent_at = coalesce(sent_at, now()),
                           delivered_at = case when ? = 'DELIVERED' then now() else delivered_at end,
                           failure_reason = case when ? = 'FAILED' then ? else failure_reason end
                    where id = ?""", status, status, status, trim(failureReason), id);
            events.publish("wallets.sms_message.delivery_updated", TABLE, rows.get(TABLE, id));
        }
        ObjectNode out = rows.object();
        out.put("sms_message_id", id.toString());
        out.put("status", forward ? status : current);
        out.put("applied", forward);
        return out;
    }

    private static String trim(String s) {
        return s == null ? null : s.length() > 255 ? s.substring(0, 255) : s;
    }
}
