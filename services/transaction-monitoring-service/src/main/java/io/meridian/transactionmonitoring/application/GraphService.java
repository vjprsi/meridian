package io.meridian.transactionmonitoring.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.page.Cursor;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.transactionmonitoring.infrastructure.Db;
import io.meridian.transactionmonitoring.infrastructure.TmStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Risk graph: observed links, edges of a node, and breadth-first network extraction for investigation. */
@Service
public class GraphService {
    private final Db db;
    private final TmStore store;
    private final JsonMapper json;

    public GraphService(Db db, TmStore store, JsonMapper json) {
        this.db = db;
        this.store = store;
        this.json = json;
    }

    @Transactional
    public String observe(JsonNode body) {
        String fromType = Db.str(body, "from_type");
        String fromRef = Db.str(body, "from_ref").trim();
        String toType = Db.str(body, "to_type");
        String toRef = Db.str(body, "to_ref").trim();
        if (fromType.equals(toType) && fromRef.equals(toRef)) {
            throw ApiException.unprocessable("self_link", "A node cannot be linked to itself");
        }
        BigDecimal amount = Db.dec(body, "amount");
        if (amount != null && amount.signum() < 0) {
            throw ApiException.unprocessable("invalid_amount", "amount must not be negative");
        }
        String now = Instant.now().toString();
        ArrayNode entities = json.createArrayNode();
        entities.add(entity(fromType, fromRef, Db.str(body, "from_customer_id"), now));
        entities.add(entity(toType, toRef, Db.str(body, "to_customer_id"), now));
        ArrayNode links = json.createArrayNode();
        ObjectNode l = links.addObject();
        l.put("from_type", fromType);
        l.put("from_ref", fromRef);
        l.put("to_type", toType);
        l.put("to_ref", toRef);
        l.put("link_type", Db.str(body, "link_type").toUpperCase(java.util.Locale.ROOT));
        l.put("cnt", amount == null ? 0 : 1);
        l.put("amount", amount == null ? "0" : amount.toPlainString());
        l.put("first", now);
        l.put("last", now);
        return db.text(store.upsertGraph(entities, links, TenantContext.principal()).getFirst());
    }

    private ObjectNode entity(String type, String ref, String customer, String seen) {
        ObjectNode n = json.createObjectNode();
        n.put("entity_type", type);
        n.put("entity_ref", ref);
        if (customer != null) {
            n.put("customer_id", customer);
        }
        n.put("last_seen_at", seen);
        return n;
    }

    /** Links where the node is either end, newest first, cursor paged. */
    public String links(UUID id, Integer limitParam, String cursor) {
        db.require("risk_graph_entity", id, false);
        int limit = Cursor.limit(limitParam);
        List<Object> args = new ArrayList<>(List.of(id, id));
        String sql = "select row_to_json(l)::text from risk_graph_link l where (from_entity_id = ? or to_entity_id = ?)";
        if (cursor != null && !cursor.isBlank()) {
            sql += " and l.id < ?";
            args.add(Cursor.decode(cursor));
        }
        sql += " order by l.id desc limit ?";
        args.add(limit + 1);
        List<String> rows = db.jdbc().sql(sql).params(args).query(String.class).list();
        boolean more = rows.size() > limit;
        List<String> data = more ? rows.subList(0, limit) : rows;
        String next = more ? Cursor.encode(UUID.fromString(json.readTree(data.getLast()).get("id").asString())) : null;
        return "{\"data\":[" + String.join(",", data) + "],\"has_more\":" + more + ",\"next_cursor\":"
                + (next == null ? "null" : "\"" + next + "\"") + "}";
    }

    /** Breadth-first subgraph around a node: depth 1..3, at most max_nodes nodes (default 200, up to 1000). */
    public Map<String, Object> network(UUID root, Integer depthParam, Integer maxParam) {
        int depth = depthParam == null ? 2 : depthParam;
        if (depth < 1 || depth > 3) {
            throw ApiException.unprocessable("invalid_depth", "depth must be between 1 and 3");
        }
        int max = maxParam == null ? 200 : Math.max(1, Math.min(1000, maxParam));
        db.require("risk_graph_entity", root, false);
        Map<UUID, Integer> level = new LinkedHashMap<>();
        level.put(root, 0);
        Map<UUID, JsonNode> edges = new LinkedHashMap<>();
        Set<UUID> frontier = new LinkedHashSet<>(Set.of(root));
        boolean truncated = false;
        for (int d = 1; d <= depth && !frontier.isEmpty() && !truncated; d++) {
            List<JsonNode> links = db.list("""
                    select row_to_json(l)::text from risk_graph_link l
                    where from_entity_id = any(cast(? as uuid[])) or to_entity_id = any(cast(? as uuid[]))
                    order by l.total_amount desc, l.id""", Db.array(frontier), Db.array(frontier));
            Set<UUID> next = new LinkedHashSet<>();
            for (JsonNode l : links) {
                UUID from = UUID.fromString(l.get("from_entity_id").asString());
                UUID to = UUID.fromString(l.get("to_entity_id").asString());
                for (UUID n : List.of(from, to)) {
                    if (!level.containsKey(n)) {
                        if (level.size() >= max) {
                            truncated = true;
                            continue;
                        }
                        level.put(n, d);
                        next.add(n);
                    }
                }
                if (level.containsKey(from) && level.containsKey(to)) {
                    edges.put(UUID.fromString(l.get("id").asString()), l);
                }
            }
            frontier = next;
        }
        List<Map<String, Object>> nodes = new ArrayList<>();
        int mules = 0;
        for (JsonNode n : db.list("select row_to_json(e)::text from risk_graph_entity e where id = any(cast(? as uuid[]))",
                Db.array(level.keySet()))) {
            Map<String, Object> m = new LinkedHashMap<>();
            UUID id = UUID.fromString(n.get("id").asString());
            m.put("id", id.toString());
            m.put("entity_type", n.get("entity_type").asString());
            m.put("entity_ref", n.get("entity_ref").asString());
            m.put("customer_id", Db.str(n, "customer_id"));
            m.put("risk_score", n.get("risk_score").asInt());
            m.put("is_mule_suspect", n.get("is_mule_suspect").asBoolean());
            m.put("depth", level.get(id));
            if (n.get("is_mule_suspect").asBoolean()) {
                mules++;
            }
            nodes.add(m);
        }
        nodes.sort((a, b) -> Integer.compare((Integer) a.get("depth"), (Integer) b.get("depth")));
        List<Map<String, Object>> edgeList = new ArrayList<>();
        for (JsonNode l : edges.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", l.get("id").asString());
            m.put("from", l.get("from_entity_id").asString());
            m.put("to", l.get("to_entity_id").asString());
            m.put("link_type", l.get("link_type").asString());
            m.put("transaction_count", l.get("transaction_count").asInt());
            m.put("total_amount", l.get("total_amount").asString());
            m.put("weight", l.get("weight").asString());
            edgeList.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("root", root.toString());
        out.put("depth", depth);
        out.put("node_count", nodes.size());
        out.put("edge_count", edgeList.size());
        out.put("mule_suspects", mules);
        out.put("truncated", truncated);
        out.put("nodes", nodes);
        out.put("edges", edgeList);
        return out;
    }
}
