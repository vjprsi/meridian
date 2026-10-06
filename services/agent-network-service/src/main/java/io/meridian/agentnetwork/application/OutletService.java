package io.meridian.agentnetwork.application;

import io.meridian.agentnetwork.domain.Geo;
import io.meridian.agentnetwork.domain.Hierarchy;
import io.meridian.agentnetwork.domain.TillCount;
import io.meridian.agentnetwork.infrastructure.Downstream;
import io.meridian.agentnetwork.infrastructure.Rows;
import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Outlets (with geo search), tills with shift cash counts, and agent devices. */
@Service
public class OutletService {
    private final Rows rows;
    private final AgentService agents;
    private final Downstream downstream;
    private final Events events;

    public OutletService(Rows rows, AgentService agents, Downstream downstream, Events events) {
        this.rows = rows;
        this.agents = agents;
        this.downstream = downstream;
        this.events = events;
    }

    // ------------------------------------------------------------------ outlets

    @Transactional
    public ObjectNode createOutlet(JsonNode req) {
        ObjectNode v = rows.columns("agent_outlet", req);
        ObjectNode agent = rows.find("agent", Rows.uuid(v, "agent_id")).orElseThrow(() ->
                ApiException.unprocessable("agent_not_found", "The agent does not exist"));
        if ("TERMINATED".equals(Rows.str(agent, "status"))) {
            throw ApiException.unprocessable("agent_terminated", "A terminated agent cannot open outlets");
        }
        Geo.validate(Rows.dec(v, "latitude"), Rows.dec(v, "longitude"));
        UUID territory = Rows.uuid(v, "agent_territory_id");
        if (territory != null && rows.find("agent_territory", territory).isEmpty()) {
            throw ApiException.unprocessable("territory_not_found", "The territory does not exist");
        }
        if (!v.hasNonNull("agent_territory_id") && agent.hasNonNull("agent_territory_id")) {
            v.set("agent_territory_id", agent.get("agent_territory_id"));
        }
        v.put("outlet_code", agents.code("agent_outlet", "outlet_code", "OT"));
        v.put("status", "ACTIVE");
        return rows.insert("agent_outlet", v);
    }

    public void validateLocationUpdate(UUID id, JsonNode req) {
        ObjectNode cur = rows.get("agent_outlet", id);
        BigDecimal lat = req.hasNonNull("latitude") ? Rows.dec(req, "latitude") : Rows.dec(cur, "latitude");
        BigDecimal lon = req.hasNonNull("longitude") ? Rows.dec(req, "longitude") : Rows.dec(cur, "longitude");
        Geo.validate(lat, lon);
        if ("CLOSED".equals(Rows.str(cur, "status"))) {
            throw ApiException.conflict("invalid_transition", "A closed outlet cannot be changed");
        }
    }

    @Transactional
    public ObjectNode closeOutlet(UUID id, String reason) {
        ObjectNode o = rows.lock("agent_outlet", id);
        if ("CLOSED".equals(Rows.str(o, "status"))) {
            throw ApiException.conflict("invalid_transition", "The outlet is already closed");
        }
        if (rows.scalar("select count(*) from agent_till where agent_outlet_id = ? and status = 'OPEN'", Long.class, id)
                .orElse(0L) > 0) {
            throw ApiException.unprocessable("open_tills", "Close the outlet's open tills first");
        }
        ObjectNode v = rows.object();
        v.put("status", "CLOSED");
        v.put("closed_reason", reason);
        ObjectNode row = rows.update("agent_outlet", id, v);
        events.publish("wallets.agent_outlet.closed", "agent_outlet", row);
        return row;
    }

    /**
     * Active outlets of active agents within the radius, nearest first: a bounding box narrows the candidates, the
     * Haversine distance decides. Paged by offset (opaque cursor).
     */
    public String nearby(BigDecimal lat, BigDecimal lon, Integer radiusM, String service, Integer limit, String cursor) {
        Geo.validate(lat, lon);
        if (lat == null) {
            throw ApiException.badRequest("missing_parameter", "lat and lon are required");
        }
        int radius = Geo.radius(radiusM);
        int n = limit == null ? 50 : Math.max(1, Math.min(200, limit));
        int offset = decodeOffset(cursor);
        double la = lat.doubleValue();
        double lo = lon.doubleValue();
        double[] box = Geo.boundingBox(la, lo, radius);
        List<ObjectNode> found = rows.query("""
                select row_to_json(x)::text from (
                  select o.*, round(? * 2 * asin(least(1, sqrt(
                      power(sin(radians((o.latitude::float8 - ?) / 2)), 2)
                      + cos(radians(?)) * cos(radians(o.latitude::float8))
                        * power(sin(radians((o.longitude::float8 - ?) / 2)), 2)))))::int as distance_m
                  from agent_outlet o join agent a on a.id = o.agent_id
                  where o.status = 'ACTIVE' and a.status = 'ACTIVE'
                    and o.latitude between ? and ? and o.longitude between ? and ?
                    and (?::text is null or coalesce(o.services, '[]'::jsonb) @> jsonb_build_array(?::text))) x
                where x.distance_m <= ?
                order by x.distance_m, x.id offset ? limit ?""",
                Geo.EARTH_RADIUS_M, la, la, lo, box[0], box[1], box[2], box[3], service, service, radius, offset, n + 1);
        boolean more = found.size() > n;
        ObjectNode page = rows.object();
        ArrayNode data = page.putArray("data");
        (more ? found.subList(0, n) : found).forEach(r -> {
            r.remove("tenant_id");
            data.add(r);
        });
        page.put("has_more", more);
        if (more) {
            page.put("next_cursor", Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(("o:" + (offset + n)).getBytes(StandardCharsets.UTF_8)));
        } else {
            page.putNull("next_cursor");
        }
        return page.toString();
    }

    static int decodeOffset(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            String s = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (s.startsWith("o:")) {
                return Math.max(0, Integer.parseInt(s.substring(2)));
            }
        } catch (IllegalArgumentException e) {
            // fall through
        }
        throw ApiException.badRequest("invalid_cursor", "The cursor is not valid for this search");
    }

    // ------------------------------------------------------------------ tills

    @Transactional
    public ObjectNode createTill(JsonNode req) {
        ObjectNode v = rows.columns("agent_till", req);
        ObjectNode outlet = rows.find("agent_outlet", Rows.uuid(v, "agent_outlet_id")).orElseThrow(() ->
                ApiException.unprocessable("outlet_not_found", "The outlet does not exist"));
        if ("CLOSED".equals(Rows.str(outlet, "status"))) {
            throw ApiException.unprocessable("outlet_closed", "The outlet is closed");
        }
        if (rows.scalar("select count(*) from agent_till where till_number = ?", Long.class, Rows.str(v, "till_number"))
                .orElse(0L) > 0) {
            throw ApiException.conflict("duplicate_till_number", "Till number " + Rows.str(v, "till_number") + " exists");
        }
        v.put("status", "CLOSED");
        return rows.insert("agent_till", v);
    }

    @Transactional
    public ObjectNode openTill(UUID id, UUID operator, BigDecimal openingCash, JsonNode denominations) {
        ObjectNode t = rows.lock("agent_till", id);
        if (!"CLOSED".equals(Rows.str(t, "status"))) {
            throw ApiException.conflict("invalid_transition", "Only a closed till can be opened (it is " + Rows.str(t, "status") + ")");
        }
        ObjectNode outlet = rows.get("agent_outlet", Rows.uuid(t, "agent_outlet_id"));
        if (!"ACTIVE".equals(Rows.str(outlet, "status"))) {
            throw ApiException.unprocessable("outlet_not_active", "The outlet is " + Rows.str(outlet, "status"));
        }
        TillCount.validateCount(openingCash, map(denominations));
        rows.execute("""
                update agent_till set status = 'OPEN', operator_user_id = ?, opening_cash = ?, opened_at = now(),
                  closed_at = null, cash_in_total = null, cash_out_total = null, expected_cash = null, declared_cash = null,
                  variance = null, variance_status = null, denominations = ?::jsonb
                where id = ?""", operator, openingCash, denominations == null ? null : denominations.toString(), id);
        ObjectNode row = rows.get("agent_till", id);
        events.publish("wallets.agent_till.opened", "agent_till", row);
        return row;
    }

    /** Closes the shift: expected cash from the outlet's completed cash-in and cash-out since opening. */
    @Transactional
    public ObjectNode closeTill(UUID id, BigDecimal declared, JsonNode denominations) {
        ObjectNode t = rows.lock("agent_till", id);
        if (!"OPEN".equals(Rows.str(t, "status"))) {
            throw ApiException.conflict("invalid_transition", "Only an open till can be closed");
        }
        TillCount.validateCount(declared, map(denominations));
        OffsetDateTime openedAt = Rows.ts(t, "opened_at");
        OffsetDateTime now = OffsetDateTime.parse(rows.scalar("select to_char(now() at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"')",
                String.class).orElseThrow());
        JsonNode summary = downstream.summary(null, Rows.uuid(t, "agent_outlet_id"), openedAt, now.plusNanos(1000));
        BigDecimal cashIn = sum(summary, "CASH_IN", Rows.str(t, "currency"));
        BigDecimal cashOut = sum(summary, "CASH_OUT", Rows.str(t, "currency"));
        TillCount.Result r = TillCount.close(Rows.dec(t, "opening_cash"), cashIn, cashOut, declared);
        rows.execute("""
                update agent_till set status = 'CLOSED', closed_at = now(), cash_in_total = ?, cash_out_total = ?,
                  expected_cash = ?, declared_cash = ?, variance = ?, variance_status = ?,
                  denominations = coalesce(?::jsonb, denominations)
                where id = ?""", cashIn, cashOut, r.expected(), declared, r.variance(), r.status(),
                denominations == null ? null : denominations.toString(), id);
        ObjectNode row = rows.get("agent_till", id);
        events.publish("wallets.agent_till.closed", "agent_till", row);
        return row;
    }

    /** Sum of completed amounts of a transaction type in a summary response. */
    static BigDecimal sum(JsonNode summary, String txnType, String currency) {
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode g : summary.path("groups")) {
            if (txnType.equals(g.path("txn_type").asString()) && "COMPLETED".equals(g.path("status").asString("COMPLETED"))
                    && (currency == null || currency.equals(g.path("currency").asString(currency)))) {
                total = total.add(new BigDecimal(g.path("amount").asString("0")));
            }
        }
        return total;
    }

    static int count(JsonNode summary, String txnType, String field) {
        int total = 0;
        for (JsonNode g : summary.path("groups")) {
            if (txnType.equals(g.path("txn_type").asString())) {
                total += g.path(field).asInt(0);
            }
        }
        return total;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (!n.isObject()) {
            throw ApiException.unprocessable("invalid_denominations", "denominations must be an object");
        }
        return rows.json().treeToValue(n, Map.class);
    }

    // ------------------------------------------------------------------ devices

    @Transactional
    public ObjectNode createDevice(JsonNode req) {
        ObjectNode v = rows.columns("agent_device", req);
        ObjectNode agent = rows.find("agent", Rows.uuid(v, "agent_id")).orElseThrow(() ->
                ApiException.unprocessable("agent_not_found", "The agent does not exist"));
        if ("TERMINATED".equals(Rows.str(agent, "status"))) {
            throw ApiException.unprocessable("agent_terminated", "A terminated agent cannot get devices");
        }
        checkOutlet(Rows.uuid(v, "agent_outlet_id"), Rows.uuid(v, "agent_id"));
        if (rows.scalar("select count(*) from agent_device where serial_number = ?", Long.class,
                Rows.str(v, "serial_number")).orElse(0L) > 0) {
            throw ApiException.conflict("duplicate_serial_number", "A device with this serial number exists");
        }
        v.put("status", "ACTIVE");
        if (!v.hasNonNull("assigned_at")) {
            v.put("assigned_at", rows.scalar("select now()::text", String.class).orElseThrow());
        }
        return rows.insert("agent_device", v);
    }

    public void checkOutlet(UUID outlet, UUID agent) {
        if (outlet == null) {
            return;
        }
        ObjectNode o = rows.find("agent_outlet", outlet).orElseThrow(() ->
                ApiException.unprocessable("outlet_not_found", "The outlet does not exist"));
        if (!Rows.str(o, "agent_id").equals(agent.toString())) {
            throw ApiException.unprocessable("outlet_mismatch", "The outlet belongs to another agent");
        }
    }

    @Transactional
    public ObjectNode heartbeat(UUID id, BigDecimal lat, BigDecimal lon, String appVersion) {
        ObjectNode d = rows.lock("agent_device", id);
        if (!"ACTIVE".equals(Rows.str(d, "status"))) {
            throw ApiException.conflict("device_not_active", "The device is " + Rows.str(d, "status"));
        }
        Geo.validate(lat, lon);
        rows.execute("""
                update agent_device set last_seen_at = now(), last_latitude = coalesce(?, last_latitude),
                  last_longitude = coalesce(?, last_longitude), app_version = coalesce(?, app_version) where id = ?""",
                lat, lon, appVersion, id);
        return rows.get("agent_device", id);
    }

    @Transactional
    public ObjectNode deviceStatus(UUID id, String to, String reason) {
        ObjectNode d = rows.lock("agent_device", id);
        Hierarchy.requireDeviceTransition(Rows.str(d, "status"), to);
        ObjectNode v = rows.object();
        v.put("status", to);
        v.put("status_reason", reason == null ? to.toLowerCase() : reason);
        ObjectNode row = rows.update("agent_device", id, v);
        if ("LOST".equals(to)) {
            events.publish("wallets.agent_device.lost", "agent_device", row);
        }
        return row;
    }
}
