package io.meridian.channel.application;

import io.meridian.channel.domain.UssdEngine;
import io.meridian.channel.infrastructure.Rows;
import io.meridian.channel.infrastructure.WalletActions;
import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.common.time.Clocks;
import java.time.OffsetDateTime;
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

    /** Approval resource type for USSD menu publication submissions in workflow-service (G11). */
    public static final String APPROVAL_RESOURCE = "ussd_menu";

    private final Rows rows;
    private final Events events;
    private final WalletActions actions;
    private final Approvals approvals;

    public MenuService(Rows rows, Events events, WalletActions actions, Approvals approvals) {
        this.rows = rows;
        this.events = events;
        this.actions = actions;
        this.approvals = approvals;
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

    /**
     * Maker step: submits a valid DRAFT (menu and tree) for publication. In workflow mode this opens an approval
     * request whose payload carries a checksum of the menu and its nodes; {@link UssdMenuApprovalHandler} publishes or
     * rejects. A new submission replaces a pending one.
     */
    @Transactional
    public ObjectNode submit(UUID id, String comment) {
        ObjectNode m = rows.lock(MENU, id);
        if ("PUBLISHED".equals(Rows.str(m, "status"))) {
            throw ApiException.conflict("invalid_transition", "The menu is already published");
        }
        requireDraft(id);
        List<String> problems = UssdEngine.validate(load(id));
        if (!problems.isEmpty()) {
            throw ApiException.unprocessable("invalid_menu_tree", String.join("; ", problems));
        }
        OffsetDateTime at = Clocks.now();
        rows.execute("""
                update ussd_menu set submitted_by = ?, submitted_at = ?, rejected_by = null, rejection_reason = null
                where id = ?""", TenantContext.principal(), at, id);
        int nodes = rows.scalar("select count(*)::int from ussd_menu_node where ussd_menu_id = ?", Integer.class, id).orElse(0);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", Rows.str(m, "code"));
        payload.put("short_code", Rows.str(m, "short_code"));
        payload.put("language", Rows.str(m, "language"));
        payload.put("version", Rows.integer(m, "version"));
        payload.put("node_count", nodes);
        payload.put("checksum", checksum(id));
        if (comment != null) {
            payload.put("comment", comment);
        }
        UUID requestId = Approvals.idOf(approvals.request(ApprovalSpec.of(APPROVAL_RESOURCE, id, "publish",
                "USSD menu " + Rows.str(m, "short_code") + " " + Rows.str(m, "language") + " v" + Rows.integer(m, "version"),
                payload), "ussd-menu:" + id + ":" + at));
        if (requestId != null) {
            approvals.cancelOpenExcept(APPROVAL_RESOURCE, id, null, requestId, "superseded");
        }
        ObjectNode after = rows.get(MENU, id);
        events.publish("wallets.ussd_menu.submitted", MENU, after);
        return after;
    }

    /** True while the DRAFT is submitted with the submitted content (checksum of menu and nodes) unchanged. */
    @Transactional(readOnly = true)
    public boolean awaitsApproval(UUID id, String checksum) {
        return rows.find(MENU, id)
                .filter(m -> "DRAFT".equals(Rows.str(m, "status")) && m.hasNonNull("submitted_at"))
                .filter(m -> checksum != null && checksum.equals(checksum(id)))
                .isPresent();
    }

    /** Publishes a draft after validation; the publisher must not be the maker or the submitter. */
    @Transactional
    public ObjectNode publish(UUID id, String comment) {
        ObjectNode m = rows.lock(MENU, id);
        if ("PUBLISHED".equals(Rows.str(m, "status"))) {
            throw ApiException.conflict("invalid_transition", "The menu is already published");
        }
        requireDraft(id);
        if (TenantContext.principal().equals(Rows.str(m, "created_by"))
                || TenantContext.principal().equals(Rows.str(m, "submitted_by"))) {
            throw ApiException.unprocessable("four_eyes_violation",
                    "The menu must be published by a different user than its maker and submitter");
        }
        List<String> problems = UssdEngine.validate(load(id));
        if (!problems.isEmpty()) {
            throw ApiException.unprocessable("invalid_menu_tree", String.join("; ", problems));
        }
        rows.execute("""
                update ussd_menu set status = 'RETIRED', retired_at = now()
                where short_code = ? and language = ? and status = 'PUBLISHED' and id <> ?""",
                Rows.str(m, "short_code"), Rows.str(m, "language"), id);
        rows.execute("""
                update ussd_menu set status = 'PUBLISHED', published_at = now(), published_by = ?, submitted_by = null,
                submitted_at = null where id = ?""", TenantContext.principal(), id);
        ObjectNode after = rows.get(MENU, id);
        events.publish("wallets.ussd_menu.published", MENU, after);
        approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "published directly");
        return after;
    }

    /** Checker step: rejects the submitted draft; it stays DRAFT (editable) for rework. */
    @Transactional
    public ObjectNode reject(UUID id, String reason) {
        ObjectNode m = rows.lock(MENU, id);
        if (!"DRAFT".equals(Rows.str(m, "status")) || !m.hasNonNull("submitted_at")) {
            throw ApiException.conflict("no_pending_submission", "The menu has no pending publication submission");
        }
        String checker = TenantContext.principal();
        if (checker.equals(Rows.str(m, "submitted_by"))) {
            throw ApiException.unprocessable("four_eyes_violation", "The submitter cannot reject their own submission");
        }
        rows.execute("""
                update ussd_menu set rejected_by = ?, rejection_reason = ?, submitted_by = null, submitted_at = null
                where id = ?""", checker, reason, id);
        ObjectNode after = rows.get(MENU, id);
        events.publish("wallets.ussd_menu.rejected", MENU, after);
        approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "rejected directly");
        return after;
    }

    /** SHA-256 over the menu's served fields and every node (without audit columns), to detect edits after submission. */
    private String checksum(UUID id) {
        StringBuilder sb = new StringBuilder();
        ObjectNode m = rows.get(MENU, id);
        sb.append(Rows.str(m, "root_node_key")).append('|').append(Rows.integer(m, "session_timeout_seconds")).append('\n');
        for (ObjectNode n : rows.query("select row_to_json(t)::text from ussd_menu_node t where ussd_menu_id = ? order by id", id)) {
            for (String f : new String[] {"tenant_id", "created_at", "created_by", "updated_at", "updated_by", "row_version"}) {
                n.remove(f);
            }
            sb.append(n).append('\n');
        }
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
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
