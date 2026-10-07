package io.meridian.web.ops;

import io.meridian.common.cell.CellRole;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxReplay;
import io.meridian.events.StandbyGate;
import io.meridian.web.security.RequiredScope;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * MR-GAME-DAY operator switches of every service (internal API, not routed by the edge gateway):
 * <ul>
 *   <li>{@code GET /v1/internal/ops/cell-role}: role, promotion state, held listeners, relay state;</li>
 *   <li>{@code POST /v1/internal/ops/cell-role/promote} {@code {reason}}: standby -> primary without a restart (waits
 *       for a writable database, optional replay, relay on, listeners on); {@code /demote} the reverse (failback
 *       preparation); {@code /retry} re-runs a failed promotion;</li>
 *   <li>{@code POST /v1/internal/ops/outbox/replay}: re-publishes relayed outbox events with their original ids
 *       ({@code from}, {@code to}, {@code basis} CREATED|PUBLISHED, {@code event_types} exact or {@code prefix.*},
 *       {@code tenant_id}, {@code dry_run}, {@code max_events}, {@code reason}).</li>
 * </ul>
 * Internal service tokens only: the calling service (proven by the token's client, GAP-16) must be one of
 * {@code meridian.ops.callers} (default {@code platform-ops} - the operator tooling identity used by
 * {@code tools/gameday} - and {@code tenant-service}); a user's token is refused even with {@code platform:operator}.
 */
@RestController
@RequestMapping("/v1/internal/ops")
public class CellOpsController {
    private static final Logger log = LoggerFactory.getLogger(CellOpsController.class);

    private final CellRole role;
    private final ObjectProvider<StandbyGate> gate;
    private final ObjectProvider<OutboxReplay> replay;
    private final ObjectProvider<io.meridian.events.OutboxRelay> relay;
    private final Set<String> callers;
    private final JsonMapper json;

    public CellOpsController(CellRole role, ObjectProvider<StandbyGate> gate, ObjectProvider<OutboxReplay> replay,
                             ObjectProvider<io.meridian.events.OutboxRelay> relay, Set<String> callers, JsonMapper json) {
        this.role = role;
        this.gate = gate;
        this.replay = replay;
        this.relay = relay;
        this.callers = Set.copyOf(callers);
        this.json = json;
    }

    @GetMapping("/cell-role")
    @RequiredScope("platform:operator")
    public ObjectNode status() {
        guard();
        return view();
    }

    @PostMapping("/cell-role/promote")
    @RequiredScope("platform:operator")
    public ObjectNode promote(@RequestBody(required = false) JsonNode body) {
        String caller = guard();
        String reason = reason(body);
        boolean changed = role.promote(reason, caller);
        log.warn("Cell {} promotion requested by {} ({}): {}", role.cellId(), caller, reason,
                changed ? "standby -> primary" : "already primary");
        ObjectNode out = view();
        out.put("changed", changed);
        return out;
    }

    @PostMapping("/cell-role/demote")
    @RequiredScope("platform:operator")
    public ObjectNode demote(@RequestBody(required = false) JsonNode body) {
        String caller = guard();
        String reason = reason(body);
        boolean changed = role.demote(reason, caller);
        log.warn("Cell {} demotion requested by {} ({}): {}", role.cellId(), caller, reason,
                changed ? "primary -> standby" : "already standby");
        ObjectNode out = view();
        out.put("changed", changed);
        return out;
    }

    @PostMapping("/cell-role/retry")
    @RequiredScope("platform:operator")
    public ObjectNode retry() {
        guard();
        StandbyGate g = gate.getIfAvailable();
        ObjectNode out = view();
        out.put("changed", g != null && g.retry());
        return out;
    }

    @PostMapping("/outbox/replay")
    @RequiredScope("platform:operator")
    public ObjectNode replay(@RequestBody JsonNode body) {
        String caller = guard();
        OutboxReplay r = replay.getIfAvailable();
        if (r == null) {
            throw new ApiException(409, "replay_unavailable", "This service has no outbox relay (no Kafka template)");
        }
        if (!role.active() && !body.path("dry_run").asBoolean(false)) {
            throw ApiException.conflict("cell_standby", "A standby cell does not publish events; promote it first "
                    + "or use dry_run");
        }
        OutboxReplay.Request req;
        try {
            List<String> types = new ArrayList<>();
            body.path("event_types").forEach(t -> types.add(t.asString()));
            String tenant = body.path("tenant_id").asString(null);
            req = new OutboxReplay.Request(time(body, "from"), time(body, "to"),
                    OutboxReplay.TimeBasis.valueOf(body.path("basis").asString("CREATED")), types,
                    tenant == null || tenant.isBlank() ? null : UUID.fromString(tenant),
                    body.path("dry_run").asBoolean(false), body.path("max_events").asInt(100_000), false,
                    reason(body) + " (by " + caller + ")");
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable("validation_failed", e.getMessage());
        }
        OutboxReplay.Result res = r.replay(req);
        log.warn("Outbox replay {} by {}: {} matched, {} re-published", res.replayId(), caller, res.matched(), res.published());
        ObjectNode out = json.createObjectNode();
        out.put("replay_id", res.replayId());
        out.put("matched", res.matched());
        out.put("published", res.published());
        out.put("dry_run", res.dryRun());
        out.put("truncated", res.truncated());
        if (res.first() != null) {
            out.put("first_event_time", res.first().toString());
            out.put("last_event_time", res.last().toString());
        }
        ObjectNode byType = out.putObject("by_type");
        res.byType().forEach(byType::put);
        return out;
    }

    private ObjectNode view() {
        ObjectNode out = json.createObjectNode();
        out.put("cell_id", role.cellId());
        out.put("role", role.role().name());
        CellRole.Change c = role.lastChange();
        if (c != null) {
            ObjectNode lc = out.putObject("last_change");
            lc.put("from", c.from().name());
            lc.put("to", c.to().name());
            lc.put("reason", c.reason());
            lc.put("actor", c.actor());
            lc.put("at", c.at().toString());
        }
        StandbyGate g = gate.getIfAvailable();
        if (g != null) {
            out.put("promotion_state", g.state().name());
            out.put("promotion_detail", g.detail());
            var held = out.putArray("held_listeners");
            g.heldListeners().forEach(held::add);
        }
        io.meridian.events.OutboxRelay rel = relay.getIfAvailable();
        if (rel != null) {
            out.put("relay_paused", rel.paused());
            out.put("outbox_retention", rel.retention().toString());
        }
        return out;
    }

    /** Internal service tokens of the listed callers only. */
    private String guard() {
        String caller = TenantContext.callingService().orElse(null);
        if (caller == null || !callers.contains(caller)) {
            throw new ApiException(403, "caller_not_allowed",
                    "Operator switches are reserved to the internal identities " + String.join(", ", callers.stream().sorted().toList()));
        }
        return caller;
    }

    private static String reason(JsonNode body) {
        String r = body == null ? null : body.path("reason").asString(null);
        if (r == null || r.isBlank()) {
            throw ApiException.unprocessable("validation_failed", "reason is required");
        }
        return r.length() > 255 ? r.substring(0, 255) : r;
    }

    private static OffsetDateTime time(JsonNode body, String f) {
        String v = body.path(f).asString(null);
        if (v == null) {
            throw new IllegalArgumentException(f + " is required (ISO-8601 timestamp with offset)");
        }
        try {
            return OffsetDateTime.parse(v);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(f + " must be an ISO-8601 timestamp with offset");
        }
    }
}
