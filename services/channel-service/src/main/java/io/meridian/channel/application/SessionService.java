package io.meridian.channel.application;

import io.meridian.channel.domain.UssdEngine;
import io.meridian.channel.infrastructure.Rows;
import io.meridian.channel.infrastructure.WalletActions;
import io.meridian.common.error.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * USSD sessions driven by the MNO gateway callback. One row per gateway session id; each request appends an
 * immutable step. Sessions time out by the database clock (last_activity_at + the menu's timeout). The context keeps
 * only non-secret inputs; PINs pass through memory to the action that needs them and are masked in steps.
 */
@Service
public class SessionService {
    static final String TABLE = "ussd_session";

    private final Rows rows;
    private final MenuService menus;
    private final WalletActions actions;
    private final Events events;

    public SessionService(Rows rows, MenuService menus, WalletActions actions, Events events) {
        this.rows = rows;
        this.menus = menus;
        this.actions = actions;
        this.events = events;
    }

    /** Processes one gateway request and returns the "CON ..." / "END ..." response. */
    @Transactional
    public String handle(String sessionId, String msisdn, String shortCode, String input, String mno) {
        long started = System.nanoTime();
        Optional<ObjectNode> existing = rows.queryOne(
                "select row_to_json(s)::text from ussd_session s where session_id = ? for update", sessionId);
        ObjectNode session;
        if (existing.isPresent()) {
            session = existing.get();
            if (!msisdn.equals(Rows.str(session, "msisdn"))) {
                throw ApiException.unprocessable("session_mismatch", "The session belongs to another subscriber");
            }
            if (!"ACTIVE".equals(Rows.str(session, "status"))) {
                // replay after the end: answer with the last screen, never run anything again
                String last = rows.scalar("""
                        select response_text from ussd_session_step where ussd_session_id = ? order by step_no desc limit 1""",
                        String.class, Rows.uuid(session, "id")).orElse("Session ended.");
                return "END " + last;
            }
            boolean timedOut = rows.scalar("""
                    select s.last_activity_at + make_interval(secs => coalesce(m.session_timeout_seconds, 120)) < now()
                    from ussd_session s left join ussd_menu m on m.id = s.ussd_menu_id where s.id = ?""",
                    Boolean.class, Rows.uuid(session, "id")).orElse(false);
            if (timedOut) {
                finish(Rows.uuid(session, "id"), "TIMED_OUT", "TIMEOUT");
                return "END Your session timed out. Please dial " + shortCode + " again.";
            }
        } else {
            session = open(sessionId, msisdn, shortCode, mno);
            if (!"ACTIVE".equals(Rows.str(session, "status"))) {
                String text = Rows.str(session, "end_reason").equals("UNREGISTERED")
                        ? "You are not registered for mobile money. Visit an agent to open a wallet."
                        : "This service is not available. Please try later.";
                step(session, 1, "-", input, text, started);
                return "END " + text;
            }
            input = null; // the dial request shows the first screen
        }
        UUID id = Rows.uuid(session, "id");
        UssdEngine.Menu menu = menus.load(Rows.uuid(session, "ussd_menu_id"));
        UssdEngine.State state = readState(session.get("context"));
        int stepNo = Rows.integer(session, "step_count") + 1;
        Integer tier = state.context().containsKey("_kyc_tier") ? Integer.valueOf(state.context().get("_kyc_tier")) : null;
        UssdEngine.Screen screen = UssdEngine.step(menu, state, input, tier,
                actions.executor("ussd-" + sessionId + "-" + stepNo));
        step(session, stepNo, screen.nodeKey(), screen.loggedInput(), screen.text(), started);
        ObjectNode v = rows.object();
        v.put("current_node_key", screen.nodeKey());
        v.put("step_count", stepNo);
        v.set("context", writeState(screen.state()));
        if (screen.action() != null && screen.action().walletTransactionId() != null) {
            v.put("wallet_transaction_id", screen.action().walletTransactionId().toString());
        }
        rows.update(TABLE, id, v);
        rows.execute("update ussd_session set last_activity_at = now() where id = ?", id);
        if (screen.end()) {
            finish(id, "CANCELLED".equals(screen.endReason()) ? "ABORTED"
                    : "ACTION_FAILED".equals(screen.endReason()) ? "FAILED" : "COMPLETED", screen.endReason());
        }
        return screen.response();
    }

    private ObjectNode open(String sessionId, String msisdn, String shortCode, String mno) {
        ObjectNode v = rows.object();
        v.put("session_id", sessionId);
        v.put("msisdn", msisdn);
        v.put("short_code", shortCode);
        if (mno != null) {
            v.put("mno", mno);
        }
        v.put("step_count", 0);
        JsonNode wallet = actions.lookup(msisdn);
        String language = wallet == null ? null : Rows.str(wallet, "language");
        Optional<ObjectNode> menu = rows.queryOne("""
                select row_to_json(m)::text from ussd_menu m where m.short_code = ? and m.status = 'PUBLISHED'
                order by (m.language = coalesce(?, 'en')) desc, (m.language = 'en') desc, m.published_at desc limit 1""",
                shortCode, language);
        Map<String, String> ctx = new LinkedHashMap<>();
        ctx.put("_msisdn", msisdn);
        ctx.put("_channel", "USSD");
        if (wallet != null) {
            v.put("wallet_id", Rows.str(wallet, "id"));
            ctx.put("_wallet_id", Rows.str(wallet, "id"));
            ctx.put("_currency", Rows.str(wallet, "currency"));
            ctx.put("_kyc_tier", String.valueOf(Rows.integer(wallet, "kyc_tier") == null ? 0 : Rows.integer(wallet, "kyc_tier")));
            if (Rows.str(wallet, "display_name") != null) {
                ctx.put("name", Rows.str(wallet, "display_name"));
            }
        }
        menu.ifPresent(m -> {
            v.put("ussd_menu_id", Rows.str(m, "id"));
            v.put("language", Rows.str(m, "language"));
        });
        v.set("context", writeState(UssdEngine.State.start(ctx)));
        if (wallet == null || menu.isEmpty()) {
            v.put("status", "COMPLETED");
            v.put("end_reason", wallet == null ? "UNREGISTERED" : "NO_MENU");
        } else {
            v.put("status", "ACTIVE");
        }
        ObjectNode row = rows.insert(TABLE, v);
        rows.execute("update ussd_session set last_activity_at = now(), ended_at = case when status <> 'ACTIVE' then now() end where id = ?",
                Rows.uuid(row, "id"));
        row = rows.get(TABLE, Rows.uuid(row, "id"));
        if (!"ACTIVE".equals(Rows.str(row, "status"))) {
            events.publish("wallets.ussd_session.completed", TABLE, row);
        }
        return row;
    }

    private void step(ObjectNode session, int no, String nodeKey, String input, String text, long started) {
        ObjectNode s = rows.object();
        s.put("ussd_session_id", Rows.str(session, "id"));
        s.put("step_no", no);
        s.put("node_key", nodeKey == null ? "-" : nodeKey);
        if (input != null && !input.isEmpty()) {
            s.put("user_input", input.length() > 182 ? input.substring(0, 182) : input);
        }
        s.put("response_text", text.length() > 182 ? text.substring(0, 182) : text);
        s.put("latency_ms", (int) ((System.nanoTime() - started) / 1_000_000));
        rows.insert("ussd_session_step", s);
    }

    private void finish(UUID id, String status, String reason) {
        ObjectNode v = rows.object();
        v.put("status", status);
        v.put("end_reason", reason == null ? status : reason);
        rows.update(TABLE, id, v);
        rows.execute("update ussd_session set ended_at = now() where id = ?", id);
        events.publish("wallets.ussd_session.completed", TABLE, rows.get(TABLE, id));
    }

    /** Times out every active session idle longer than its menu timeout (database clock). */
    @Transactional
    public ObjectNode expireIdle() {
        List<ObjectNode> due = rows.query("""
                select row_to_json(s)::text from ussd_session s left join ussd_menu m on m.id = s.ussd_menu_id
                where s.status = 'ACTIVE'
                  and s.last_activity_at + make_interval(secs => coalesce(m.session_timeout_seconds, 120)) < now()
                for update of s""");
        for (ObjectNode s : due) {
            finish(Rows.uuid(s, "id"), "TIMED_OUT", "TIMEOUT");
        }
        ObjectNode out = rows.object();
        out.put("timed_out", due.size());
        return out;
    }

    // ------------------------------------------------------------------ state <-> json

    ObjectNode writeState(UssdEngine.State s) {
        ObjectNode o = rows.object();
        if (s.currentKey() != null) {
            o.put("current", s.currentKey());
        }
        ObjectNode values = o.putObject("values");
        s.context().forEach(values::put);
        ArrayNode stack = o.putArray("stack");
        s.stack().forEach(stack::add);
        o.put("invalid", s.invalid());
        o.put("awaiting_pin", s.awaitingPin());
        return o;
    }

    static UssdEngine.State readState(JsonNode o) {
        Map<String, String> ctx = new LinkedHashMap<>();
        List<String> stack = new ArrayList<>();
        if (o == null || o.isNull()) {
            return new UssdEngine.State(null, ctx, stack, 0, false);
        }
        JsonNode values = o.path("values");
        values.properties().forEach(e -> ctx.put(e.getKey(), e.getValue().asString()));
        o.path("stack").forEach(x -> stack.add(x.asString()));
        String current = o.hasNonNull("current") ? o.get("current").asString() : null;
        return new UssdEngine.State(current, ctx, stack, o.path("invalid").asInt(0), o.path("awaiting_pin").asBoolean(false));
    }
}
