package io.meridian.agentchannel.application;

import static io.meridian.agentchannel.application.Ownership.AGENTS;
import static io.meridian.agentchannel.application.Ownership.me;
import static io.meridian.agentchannel.application.Ownership.mine;

import io.meridian.agentchannel.application.Downstream.Query;
import io.meridian.agentchannel.config.AgentChannelProperties;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** The signed-in agent: profile, outlets, floats and wallets, KPIs, sub-agents (read-only) and commissions. */
@Service
public class ProfileService {
    private static final String WALLETS = "wallet-service";
    private static final String COMMISSIONS = "commission-service";

    private final Downstream down;
    private final Ownership own;
    private final AgentChannelProperties props;

    public ProfileService(Downstream down, Ownership own, AgentChannelProperties props) {
        this.down = down;
        this.own = own;
        this.props = props;
    }

    public JsonNode agent() {
        return own.agent();
    }

    public JsonNode outlets() {
        return down.page(own.outlets());
    }

    public JsonNode floats(Integer limit, String cursor) {
        return down.page(down.get(AGENTS, "/v1/agents/" + me() + "/float", Query.none().paging(limit, cursor)),
                r -> mine(r, "agent_id"));
    }

    public JsonNode floatWallet() {
        return down.get(WALLETS, "/v1/wallets/" + own.floatWallet());
    }

    public JsonNode floatBalance() {
        return down.get(WALLETS, "/v1/wallets/" + own.floatWallet() + "/balance");
    }

    public JsonNode commissionBalance() {
        return down.get(WALLETS, "/v1/wallets/" + own.commissionWallet() + "/balance");
    }

    /** The agent's KPIs, or a direct sub-agent's (read-only) when the hierarchy allows it. */
    public JsonNode performance(LocalDate from, LocalDate to, UUID subAgentId, Integer limit, String cursor) {
        UUID agent = me();
        if (subAgentId != null && !subAgentId.equals(agent)) {
            own.subAgent(subAgentId, "agentMePerformance");
            agent = subAgentId;
        }
        String id = agent.toString();
        return down.page(down.get(AGENTS, "/v1/agents/" + agent + "/performance",
                        Query.of("from", from).with("to", to).paging(limit, cursor)),
                r -> id.equals(r.path("agent_id").asString(null)));
    }

    public JsonNode subAgents(Integer limit, String cursor) {
        if (!props.hierarchyRead()) {
            return down.page(List.of());
        }
        return down.page(down.get(AGENTS, "/v1/agents/" + me() + "/sub-agents", Query.none().paging(limit, cursor)),
                r -> mine(r, "parent_agent_id"));
    }

    // ------------------------------------------------------------------ commissions

    public JsonNode commissions(LocalDate from, LocalDate to, Integer limit, String cursor) {
        return down.page(down.get(COMMISSIONS, "/v1/commission-earnings", Query.of("beneficiary_agent_id", me())
                        .with("created_after", from == null ? null : from.atStartOfDay().atOffset(ZoneOffset.UTC))
                        .with("created_before", to == null ? null : to.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC))
                        .paging(limit, cursor)),
                r -> mine(r, "beneficiary_agent_id"));
    }

    public JsonNode commissionSummary(LocalDate from, LocalDate to) {
        return down.get(COMMISSIONS, "/v1/commission-earnings/summary",
                Query.of("beneficiary_agent_id", me()).with("from", from).with("to", to));
    }
}
