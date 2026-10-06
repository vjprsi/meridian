package io.meridian.channel.application;

import io.meridian.channel.domain.UssdEngine;
import io.meridian.channel.infrastructure.Rows;
import io.meridian.channel.infrastructure.WalletActions;
import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * USSD menu versions: DRAFT menus are edited (menu and nodes), published by a checker after tree validation (the
 * previous published version of the short code and language is retired), cloned into the next draft version and
 * exercised in a simulator that runs the real session engine.
 */
@Service
public class MenuService {
    static final String MENU = "ussd_menu";
    static final String NODE = "ussd_menu_node";

    private final Rows rows;
    private final Events events;
    private final WalletActions actions;

    public MenuService(Rows rows, Events events, WalletActions actions) {
        this.rows = rows;
        this.events = events;
        this.actions = actions;
    }

    @Transactional
    public ObjectNode create(JsonNode req) {
        ObjectNode v = rows.columns(MENU, req);
        requireTimeout(v);
        v.put("version", nextVersion(Rows.str(v, "short_code"), Rows.str(v, "language")));
        v.put("status", "DRAFT");
        return rows.insert(MENU, v);
    }

    private int nextVersion(String shortCode, String language) {
        rows.scalar("select pg_advisory_xact_lock(hashtext(?))::text", String.class, "ussd:" + shortCode + ":" + language);
        return rows.scalar("select coalesce(max(version), 0) + 1 from ussd_menu where short_code = ? and language = ?",
                Integer.class, shortCode, language).orElse(1);
    }

    private static void requireTimeout(JsonNode v) {
        Integer t = Rows.integer(v, "session_timeout_seconds");
        if (t != null && (t < 20 || t > 600)) {
            throw ApiException.unprocessable("invalid_timeout", "session_timeout_seconds must be 20-600");
        }
    }

    /** The menu, which must still be a draft to be edited. */
    public ObjectNode requireDraft(UUID menuId) {
        ObjectNode m = rows.find(MENU, menuId).orElseThrow(() ->
                ApiException.unprocessable("menu_not_found", "USSD menu " + menuId + " does not exist"));
        if (!"DRAFT".equals(Rows.str(m, "status"))) {
            throw ApiException.conflict("menu_not_draft", "Only a DRAFT menu can be changed; clone it to make a new version");
        }
        return m;
    }

    public void validateMenuPatch(UUID id, JsonNode req) {
        requireDraft(id);
        requireTimeout(req);
    }

    @Transactional
    public ObjectNode createNode(JsonNode req) {
        ObjectNode v = rows.columns(NODE, req);
        UUID menuId = Rows.uuid(v, "ussd_menu_id");
        rows.lock(MENU, menuId);
        requireDraft(menuId);
        validateNode(v, null);
        if (rows.scalar("select count(*) from ussd_menu_node where ussd_menu_id = ? and node_key = ?", Long.class,
                menuId, Rows.str(v, "node_key")).orElse(0L) > 0) {
            throw ApiException.conflict("node_key_exists", "The menu already has a node " + Rows.str(v, "node_key"));
        }
        return rows.insert(NODE, v);
    }

    /** Validates a node change against the draft menu; returns the merged node. */
    @Transactional
    public void validateNodeUpdate(UUID nodeId, JsonNode req) {
        ObjectNode current = rows.get(NODE, nodeId);
        requireDraft(Rows.uuid(current, "ussd_menu_id"));
        if (req.hasNonNull("ussd_menu_id") && !Rows.str(req, "ussd_menu_id").equals(Rows.str(current, "ussd_menu_id"))) {
            throw ApiException.unprocessable("field_not_updatable", "A node cannot move to another menu");
        }
        ObjectNode merged = current.deepCopy();
        req.properties().forEach(e -> {
            if (!e.getValue().isNull()) {
                merged.set(e.getKey(), e.getValue());
            }
        });
        validateNode(merged, nodeId);
    }

    public void requireNodeDeletable(UUID nodeId) {
        ObjectNode current = rows.get(NODE, nodeId);
        requireDraft(Rows.uuid(current, "ussd_menu_id"));
    }

    private void validateNode(JsonNode n, UUID self) {
        for (String f : new String[] {"label", "prompt"}) {
            String s = Rows.str(n, f);
            if (s != null && s.length() > UssdEngine.MAX_TEXT) {
                throw ApiException.unprocessable("text_too_long", f + " exceeds the 182-character USSD limit");
            }
        }
        String regex = Rows.str(n, "input_validation");
        if (regex != null) {
            try {
                Pattern.compile(regex);
            } catch (PatternSyntaxException e) {
                throw ApiException.unprocessable("invalid_regex", "input_validation is not a valid regular expression");
            }
        }
        String type = Rows.str(n, "node_type");
        if ("ACTION".equals(type) && Rows.str(n, "action_code") == null) {
            throw ApiException.unprocessable("action_code_required", "An ACTION node needs action_code");
        }
        if ("INPUT".equals(type) && Rows.str(n, "next_node_key") == null) {
            throw ApiException.unprocessable("next_node_required", "An INPUT node needs next_node_key");
        }
        Integer option = Rows.integer(n, "option_number");
        if (option != null) {
            if (option < 1 || option > 99) {
                throw ApiException.unprocessable("invalid_option_number", "option_number must be 1-99 (0 and 00 are reserved)");
            }
            if (Rows.str(n, "parent_node_key") == null) {
                throw ApiException.unprocessable("parent_required", "An option needs parent_node_key");
            }
            if (rows.scalar("""
                    select count(*) from ussd_menu_node where ussd_menu_id = ? and parent_node_key = ? and option_number = ?
                      and (?::uuid is null or id <> ?::uuid)""", Long.class, Rows.uuid(n, "ussd_menu_id"),
                    Rows.str(n, "parent_node_key"), option, self, self).orElse(0L) > 0) {
                throw ApiException.conflict("option_number_taken", "Option " + option + " is already used under "
                        + Rows.str(n, "parent_node_key"));
            }
        }
    }

    public UssdEngine.Menu load(UUID menuId) {
        ObjectNode m = rows.get(MENU, menuId);
        List<UssdEngine.Node> nodes = new ArrayList<>();
        for (ObjectNode n : rows.query("select row_to_json(n)::text from ussd_menu_node n where ussd_menu_id = ?", menuId)) {
            nodes.add(new UssdEngine.Node(Rows.str(n, "node_key"), Rows.str(n, "parent_node_key"), Rows.str(n, "node_type"),
                    Rows.integer(n, "option_number"), Rows.str(n, "label"), Rows.str(n, "prompt"), Rows.str(n, "input_type"),
                    Rows.str(n, "input_validation"), Rows.str(n, "context_key"), Rows.str(n, "action_code"),
                    Rows.str(n, "next_node_key"), Rows.bool(n, "requires_pin"), Rows.integer(n, "min_kyc_tier"),
                    Rows.integer(n, "display_order")));
        }
        return new UssdEngine.Menu(Rows.str(m, "root_node_key"), nodes);
    }

    /** Publishes a draft after validation; the publisher must not be the maker. */
    @Transactional
    public ObjectNode publish(UUID id, String comment) {
        ObjectNode m = rows.lock(MENU, id);
        if ("PUBLISHED".equals(Rows.str(m, "status"))) {
            throw ApiException.conflict("invalid_transition", "The menu is already published");
        }
        requireDraft(id);
        if (TenantContext.principal().equals(Rows.str(m, "created_by"))) {
            throw ApiException.unprocessable("four_eyes_violation", "The menu must be published by a different user than its maker");
        }
        List<String> problems = UssdEngine.validate(load(id));
        if (!problems.isEmpty()) {
            throw ApiException.unprocessable("invalid_menu_tree", String.join("; ", problems));
        }
        rows.execute("""
                update ussd_menu set status = 'RETIRED', retired_at = now()
                where short_code = ? and language = ? and status = 'PUBLISHED' and id <> ?""",
                Rows.str(m, "short_code"), Rows.str(m, "language"), id);
        rows.execute("update ussd_menu set status = 'PUBLISHED', published_at = now(), published_by = ? where id = ?",
                TenantContext.principal(), id);
        ObjectNode after = rows.get(MENU, id);
        events.publish("wallets.ussd_menu.published", MENU, after);
        return after;
    }

    /** A new DRAFT version of the short code and language with copies of all nodes. */
    @Transactional
    public ObjectNode cloneMenu(UUID id) {
        ObjectNode src = rows.get(MENU, id);
        ObjectNode v = rows.object();
        for (String f : new String[] {"code", "name", "short_code", "language", "root_node_key", "session_timeout_seconds"}) {
            v.set(f, src.get(f));
        }
        v.put("version", nextVersion(Rows.str(src, "short_code"), Rows.str(src, "language")));
        v.put("status", "DRAFT");
        v.put("cloned_from_id", id.toString());
        UUID newId = Ids.newId();
        v.put("id", newId.toString());
        ObjectNode menu = rows.insert(MENU, v);
        for (ObjectNode n : rows.query("select row_to_json(n)::text from ussd_menu_node n where ussd_menu_id = ?", id)) {
            ObjectNode c = rows.object();
            for (String f : new String[] {"node_key", "parent_node_key", "node_type", "option_number", "label", "prompt",
                    "input_type", "input_validation", "context_key", "action_code", "next_node_key", "requires_pin",
                    "min_kyc_tier", "display_order"}) {
                if (n.hasNonNull(f)) {
                    c.set(f, n.get(f));
                }
            }
            c.put("ussd_menu_id", newId.toString());
            rows.insert(NODE, c);
        }
        return menu;
    }

    /** Runs the menu with scripted inputs (first screen is the dial); actions are dry-run unless requested. */
    public ObjectNode simulate(UUID id, String msisdn, List<String> inputs, boolean execute) {
        UssdEngine.Menu menu = load(id);
        Map<String, String> ctx = new LinkedHashMap<>();
        ctx.put("_msisdn", msisdn);
        ctx.put("_channel", "USSD");
        Integer tier = null;
        UssdEngine.ActionExecutor exec = WalletActions.dryRun();
        if (execute) {
            JsonNode w = actions.lookup(msisdn);
            if (w == null) {
                throw ApiException.unprocessable("subscriber_not_registered", "No wallet is registered for " + msisdn);
            }
            ctx.put("_wallet_id", Rows.str(w, "id"));
            ctx.put("_currency", Rows.str(w, "currency"));
            tier = Rows.integer(w, "kyc_tier");
            exec = actions.executor("ussd-sim-" + Ids.newId());
        }
        ObjectNode out = rows.object();
        ArrayNode screens = out.putArray("screens");
        UssdEngine.State state = UssdEngine.State.start(ctx);
        List<String> script = new ArrayList<>();
        script.add(null);
        script.addAll(inputs == null ? List.of() : inputs);
        UssdEngine.Screen last = null;
        int step = 0;
        for (String in : script) {
            last = UssdEngine.step(menu, state, in, tier, exec);
            state = last.state();
            ObjectNode s = screens.addObject();
            s.put("step", ++step);
            s.put("node_key", last.nodeKey());
            s.put("input", last.loggedInput());
            s.put("text", last.response());
            s.put("end", last.end());
            if (last.end()) {
                break;
            }
        }
        out.put("ended", last != null && last.end());
        out.put("final_node_key", last == null ? null : last.nodeKey());
        out.put("end_reason", last == null ? null : last.endReason());
        return out;
    }
}
