package io.meridian.agentnetwork.application;

import io.meridian.agentnetwork.domain.Hierarchy;
import io.meridian.agentnetwork.infrastructure.Downstream;
import io.meridian.agentnetwork.infrastructure.Rows;
import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Agent onboarding and lifecycle, hierarchy, commission plan assignment and territories. */
@Service
public class AgentService {
    static final String TABLE = "agent";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Rows rows;
    private final Downstream downstream;
    private final Events events;

    public AgentService(Rows rows, Downstream downstream, Events events) {
        this.rows = rows;
        this.downstream = downstream;
        this.events = events;
    }

    @Transactional
    public ObjectNode create(JsonNode req) {
        ObjectNode v = rows.columns(TABLE, req);
        String level = Rows.str(v, "agent_level");
        UUID parentId = Rows.uuid(v, "parent_agent_id");
        ObjectNode parent = parentId == null ? null : rows.find(TABLE, parentId).orElseThrow(() ->
                ApiException.unprocessable("parent_not_found", "The parent agent does not exist"));
        UUID superAgent = Hierarchy.validateParent(level, parentId, parent == null ? null : Rows.str(parent, "agent_level"),
                parent == null ? null : Rows.str(parent, "status"), parent == null ? null : Rows.uuid(parent, "super_agent_id"));
        if (parent != null && !Rows.str(parent, "currency").equals(Rows.str(v, "currency"))) {
            throw ApiException.unprocessable("currency_mismatch", "An agent operates in the currency of its parent");
        }
        territory(v);
        if (v.hasNonNull("max_float_amount") && Rows.dec(v, "max_float_amount").signum() < 0) {
            throw ApiException.unprocessable("invalid_amount", "max_float_amount must not be negative");
        }
        if (Rows.date(v, "contract_start") != null && Rows.date(v, "contract_end") != null
                && Rows.date(v, "contract_end").isBefore(Rows.date(v, "contract_start"))) {
            throw ApiException.unprocessable("invalid_contract_dates", "contract_end must not be before contract_start");
        }
        if (superAgent != null) {
            v.put("super_agent_id", superAgent.toString());
        }
        v.put("agent_code", code("agent", "agent_code", prefix(level)));
        v.put("status", "ONBOARDING");
        v.put("kyc_status", "PENDING");
        return rows.insert(TABLE, v);
    }

    private void territory(ObjectNode v) {
        UUID t = Rows.uuid(v, "agent_territory_id");
        if (t != null && rows.find("agent_territory", t).isEmpty()) {
            throw ApiException.unprocessable("territory_not_found", "The territory does not exist");
        }
    }

    static String prefix(String level) {
        return switch (level) {
            case "SUPER_AGENT" -> "SA";
            case "SUB_AGENT" -> "SB";
            default -> "AG";
        };
    }

    /** A unique human-friendly code: prefix + 6 digits. */
    String code(String table, String column, String prefix) {
        String c;
        do {
            c = prefix + String.format("%06d", RANDOM.nextInt(1_000_000));
        } while (rows.scalar("select count(*) from " + table + " where " + column + " = ?", Long.class, c).orElse(0L) > 0);
        return c;
    }

    /**
     * Activates an onboarded agent (checker other than the creator, at least one verified unexpired KYB document) and
     * opens its float and commission wallets on first activation; from SUSPENDED it reactivates agent and wallets.
     */
    @Transactional
    public ObjectNode activate(UUID id, String comment) {
        ObjectNode a = rows.lock(TABLE, id);
        String from = Rows.str(a, "status");
        Hierarchy.requireAgentTransition(from, "ACTIVE");
        if (TenantContext.principal().equals(Rows.str(a, "created_by"))) {
            throw ApiException.unprocessable("four_eyes_violation", "The agent must be activated by a different user than its creator");
        }
        UUID parentId = Rows.uuid(a, "parent_agent_id");
        if (parentId != null && !"ACTIVE".equals(Rows.str(rows.get(TABLE, parentId), "status"))) {
            throw ApiException.unprocessable("parent_not_active", "The parent agent must be active first");
        }
        ObjectNode v = rows.object();
        if ("ONBOARDING".equals(from)) {
            long docs = rows.scalar("""
                    select count(*) from agent_kyc_document where agent_id = ? and verified
                      and (expires_at is null or expires_at >= ?)""", Long.class, id, rows.today()).orElse(0L);
            if (docs == 0) {
                throw ApiException.unprocessable("kyc_incomplete", "At least one verified, unexpired KYB document is required");
            }
            String name = Rows.str(a, "trading_name") != null ? Rows.str(a, "trading_name") : Rows.str(a, "legal_name");
            String currency = Rows.str(a, "currency");
            UUID floatWallet = Rows.uuid(a, "float_wallet_id");
            if (floatWallet == null) {
                String type = "SUPER_AGENT".equals(Rows.str(a, "agent_level")) ? "SUPER_AGENT" : "AGENT";
                floatWallet = downstream.openActiveWallet("agt-" + id + "-float", type, id, name + " float", currency);
                v.put("float_wallet_id", floatWallet.toString());
            }
            if (Rows.uuid(a, "commission_wallet_id") == null) {
                UUID commission = downstream.openActiveWallet("agt-" + id + "-commission", "COMMISSION", id,
                        name + " commission", currency);
                v.put("commission_wallet_id", commission.toString());
            }
            if (rows.scalar("select count(*) from agent_float where wallet_id = ?", Long.class, floatWallet).orElse(0L) == 0) {
                ObjectNode f = rows.object();
                f.put("agent_id", id.toString());
                f.put("wallet_id", floatWallet.toString());
                f.put("currency", currency);
                if (a.hasNonNull("max_float_amount")) {
                    f.set("max_balance", a.get("max_float_amount"));
                }
                if (Rows.uuid(a, "parent_agent_id") != null) {
                    f.put("rebalance_source_agent_id", Rows.str(a, "parent_agent_id"));
                }
                rows.insert("agent_float", f);
            }
            v.put("kyc_status", "VERIFIED");
        } else {
            downstream.setWalletState(Rows.uuid(a, "float_wallet_id"), true, comment, "agt-" + id + "-react-float-" + Ids.newId());
            downstream.setWalletState(Rows.uuid(a, "commission_wallet_id"), true, comment, "agt-" + id + "-react-comm-" + Ids.newId());
        }
        v.put("status", "ACTIVE");
        v.put("status_reason", comment);
        rows.update(TABLE, id, v);
        if ("ONBOARDING".equals(from)) {
            rows.execute("update agent set onboarded_at = now() where id = ?", id);
        }
        ObjectNode row = rows.get(TABLE, id);
        events.publish("ONBOARDING".equals(from) ? "wallets.agent.onboarded" : "wallets.agent.status_changed", TABLE, row);
        return row;
    }

    @Transactional
    public ObjectNode suspend(UUID id, String reason) {
        ObjectNode a = rows.lock(TABLE, id);
        Hierarchy.requireAgentTransition(Rows.str(a, "status"), "SUSPENDED");
        downstream.setWalletState(Rows.uuid(a, "float_wallet_id"), false, reason, "agt-" + id + "-susp-float-" + Ids.newId());
        downstream.setWalletState(Rows.uuid(a, "commission_wallet_id"), false, reason, "agt-" + id + "-susp-comm-" + Ids.newId());
        ObjectNode v = rows.object();
        v.put("status", "SUSPENDED");
        v.put("status_reason", reason);
        ObjectNode row = rows.update(TABLE, id, v);
        events.publish("wallets.agent.status_changed", TABLE, row);
        return row;
    }

    /** Terminates the contract: no active sub-agents and an empty float; outlets are closed. */
    @Transactional
    public ObjectNode terminate(UUID id, String reason, LocalDate effective) {
        ObjectNode a = rows.lock(TABLE, id);
        Hierarchy.requireAgentTransition(Rows.str(a, "status"), "TERMINATED");
        long children = rows.scalar("select count(*) from agent where parent_agent_id = ? and status <> 'TERMINATED'",
                Long.class, id).orElse(0L);
        if (children > 0) {
            throw ApiException.unprocessable("active_sub_agents", "Terminate or move the " + children + " sub-agent(s) first");
        }
        if (rows.scalar("""
                select count(*) from agent_till t join agent_outlet o on o.id = t.agent_outlet_id
                where o.agent_id = ? and t.status = 'OPEN'""", Long.class, id).orElse(0L) > 0) {
            throw ApiException.unprocessable("open_tills", "Close the agent's open tills first");
        }
        UUID floatWallet = Rows.uuid(a, "float_wallet_id");
        if (floatWallet != null) {
            BigDecimal balance = downstream.postedBalance(floatWallet);
            if (balance.signum() != 0) {
                throw ApiException.unprocessable("float_not_zero",
                        "The float wallet still holds " + balance.toPlainString() + "; withdraw it first");
            }
            downstream.setWalletState(floatWallet, false, reason, "agt-" + id + "-term-float");
        }
        for (ObjectNode o : rows.query("""
                select row_to_json(o)::text from agent_outlet o where o.agent_id = ? and o.status <> 'CLOSED' for update""", id)) {
            ObjectNode c = rows.object();
            c.put("status", "CLOSED");
            c.put("closed_reason", "Agent terminated");
            events.publish("wallets.agent_outlet.closed", "agent_outlet", rows.update("agent_outlet", Rows.uuid(o, "id"), c));
        }
        ObjectNode v = rows.object();
        v.put("status", "TERMINATED");
        v.put("status_reason", reason);
        v.put("contract_end", (effective == null ? rows.today() : effective).toString());
        rows.update(TABLE, id, v);
        rows.execute("update agent set terminated_at = now() where id = ?", id);
        ObjectNode row = rows.get(TABLE, id);
        events.publish("wallets.agent.status_changed", TABLE, row);
        return row;
    }

    @Transactional
    public ObjectNode assignCommissionPlan(UUID id, UUID planId, LocalDate effectiveFrom) {
        ObjectNode a = rows.lock(TABLE, id);
        if ("TERMINATED".equals(Rows.str(a, "status"))) {
            throw ApiException.conflict("invalid_transition", "A terminated agent cannot get a commission plan");
        }
        JsonNode plan = downstream.commissionPlan(planId);
        if (!"ACTIVE".equals(plan.path("status").asString())) {
            throw ApiException.unprocessable("plan_not_active", "Commission plan " + planId + " is not active");
        }
        if (!Rows.str(a, "currency").equals(plan.path("currency").asString())) {
            throw ApiException.unprocessable("currency_mismatch", "The plan is in " + plan.path("currency").asString());
        }
        String appliesTo = plan.path("applies_to").asString("ALL");
        if (!"AGENT".equals(appliesTo) && !"ALL".equals(appliesTo)) {
            throw ApiException.unprocessable("plan_not_applicable", "The plan applies to " + appliesTo + ", not agents");
        }
        ObjectNode v = rows.object();
        v.put("commission_plan_id", planId.toString());
        v.put("commission_plan_effective_from", effectiveFrom.toString());
        ObjectNode row = rows.update(TABLE, id, v);
        events.publish("wallets.agent.commission_plan_assigned", TABLE, row);
        return row;
    }

    /** Upline (parent chain up to the super-agent) and the downline tree. */
    public ObjectNode hierarchy(UUID id) {
        ObjectNode agent = rows.get(TABLE, id);
        List<ObjectNode> upline = rows.query("""
                with recursive up as (
                  select a.*, 1 as depth from agent a where a.id = (select parent_agent_id from agent where id = ?)
                  union all
                  select p.*, up.depth + 1 from agent p join up on p.id = up.parent_agent_id where up.depth < 10)
                select row_to_json(x)::text from (select * from up order by depth) x""", id);
        List<ObjectNode> down = rows.query("""
                with recursive down as (
                  select a.*, 1 as depth from agent a where a.parent_agent_id = ?
                  union all
                  select c.*, down.depth + 1 from agent c join down on c.parent_agent_id = down.id where down.depth < 10)
                select row_to_json(x)::text from (select * from down order by depth, agent_code) x""", id);
        Map<String, List<ObjectNode>> byParent = new HashMap<>();
        for (ObjectNode d : down) {
            byParent.computeIfAbsent(Rows.str(d, "parent_agent_id"), k -> new ArrayList<>()).add(d);
        }
        ObjectNode out = rows.object();
        out.set("agent", strip(agent));
        ArrayNode up = out.putArray("upline");
        upline.forEach(u -> {
            ObjectNode s = strip(u);
            s.remove("depth");
            up.add(s);
        });
        out.set("downline", tree(id.toString(), byParent));
        out.put("downline_count", down.size());
        return out;
    }

    private ArrayNode tree(String parent, Map<String, List<ObjectNode>> byParent) {
        ArrayNode arr = rows.json().createArrayNode();
        for (ObjectNode c : byParent.getOrDefault(parent, List.of())) {
            ObjectNode n = arr.addObject();
            ObjectNode s = strip(c);
            s.remove("depth");
            n.set("agent", s);
            n.set("children", tree(Rows.str(c, "id"), byParent));
        }
        return arr;
    }

    private static ObjectNode strip(ObjectNode row) {
        ObjectNode s = row.deepCopy();
        s.remove("tenant_id");
        return s;
    }

    // ------------------------------------------------------------------ territories

    @Transactional
    public ObjectNode createTerritory(JsonNode req) {
        ObjectNode v = rows.columns("agent_territory", req);
        UUID parent = Rows.uuid(v, "parent_id");
        if (parent != null) {
            ObjectNode p = rows.find("agent_territory", parent).orElseThrow(() ->
                    ApiException.unprocessable("parent_not_found", "The parent territory does not exist"));
            if (!Rows.str(p, "country").equals(Rows.str(v, "country"))) {
                throw ApiException.unprocessable("country_mismatch", "A territory must be in its parent's country");
            }
        }
        if (rows.scalar("select count(*) from agent_territory where code = ?", Long.class, Rows.str(v, "code")).orElse(0L) > 0) {
            throw ApiException.conflict("duplicate_code", "Territory code " + Rows.str(v, "code") + " already exists");
        }
        return rows.insert("agent_territory", v);
    }

    /** Rejects a parent change that would create a cycle. */
    public void checkTerritoryParent(UUID id, UUID newParent) {
        if (newParent == null) {
            return;
        }
        if (newParent.equals(id) || rows.scalar("""
                with recursive up as (select id, parent_id, 1 as d from agent_territory where id = ?
                  union all select t.id, t.parent_id, up.d + 1 from agent_territory t join up on t.id = up.parent_id where up.d < 50)
                select count(*) from up where id = ?""", Long.class, newParent, id).orElse(0L) > 0) {
            throw ApiException.unprocessable("territory_cycle", "A territory cannot be placed under itself or its descendants");
        }
    }
}
