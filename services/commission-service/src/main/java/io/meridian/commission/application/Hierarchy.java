package io.meridian.commission.application;

import io.meridian.commission.domain.CommissionEngine;
import io.meridian.commission.infrastructure.Downstream;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Resolves the transacting agent's hierarchy and maps a rule's beneficiary type to an agent / merchant and wallet. */
@Component
public class Hierarchy {
    private final Downstream downstream;

    public Hierarchy(Downstream downstream) {
        this.downstream = downstream;
    }

    /** The transacting agent and its upline (first = the agent itself). Empty when there is no agent. */
    public record Context(List<JsonNode> chain) {
        public static final Context NONE = new Context(List.of());

        JsonNode agent() {
            return chain.isEmpty() ? null : chain.getFirst();
        }

        public String agentLevel() {
            return agent() == null ? null : agent().path("agent_level").asString(null);
        }

        public UUID territoryId() {
            return agent() == null ? null : uuid(agent(), "agent_territory_id");
        }

        public UUID commissionPlanId() {
            return agent() == null ? null : uuid(agent(), "commission_plan_id");
        }

        /** The top of the hierarchy: the first SUPER_AGENT in the chain, else the last element. */
        JsonNode top() {
            for (JsonNode a : chain) {
                if ("SUPER_AGENT".equals(a.path("agent_level").asString())) {
                    return a;
                }
            }
            return chain.isEmpty() ? null : chain.getLast();
        }
    }

    public record Beneficiary(UUID agentId, UUID merchantId, UUID walletId) {}

    public Context resolve(UUID agentId) {
        return agentId == null ? Context.NONE : new Context(downstream.upline(agentId));
    }

    /** Who earns under a rule; null when the beneficiary does not exist for this transaction. */
    public Beneficiary beneficiary(CommissionEngine.Rule rule, Context ctx, UUID merchantId) {
        return switch (rule.beneficiary()) {
            case "AGENT" -> agent(ctx.agent());
            case "SUB_AGENT" -> "SUB_AGENT".equals(ctx.agentLevel()) ? agent(ctx.agent()) : null;
            case "SUPER_AGENT" -> agent(ctx.top());
            case "MERCHANT" -> merchantId == null || rule.beneficiaryWalletId() == null ? null
                    : new Beneficiary(null, merchantId, rule.beneficiaryWalletId());
            default -> rule.beneficiaryWalletId() == null ? null : new Beneficiary(null, null, rule.beneficiaryWalletId());
        };
    }

    private static Beneficiary agent(JsonNode a) {
        if (a == null) {
            return null;
        }
        UUID wallet = uuid(a, "commission_wallet_id");
        return wallet == null ? null : new Beneficiary(uuid(a, "id"), null, wallet);
    }

    static UUID uuid(JsonNode n, String f) {
        JsonNode v = n.get(f);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asString();
        return s.isBlank() ? null : UUID.fromString(s);
    }
}
