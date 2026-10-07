package io.meridian.agentchannel.api;

import io.meridian.agentchannel.api.generated.AgentMeApi;
import io.meridian.agentchannel.api.generated.model.AccountBalance;
import io.meridian.agentchannel.api.generated.model.Agent;
import io.meridian.agentchannel.api.generated.model.AgentActionPage;
import io.meridian.agentchannel.api.generated.model.AgentFloatPage;
import io.meridian.agentchannel.api.generated.model.AgentOutletPage;
import io.meridian.agentchannel.api.generated.model.AgentPage;
import io.meridian.agentchannel.api.generated.model.AgentPerformanceDailyPage;
import io.meridian.agentchannel.api.generated.model.Wallet;
import io.meridian.agentchannel.application.ActionAudit;
import io.meridian.agentchannel.application.ProfileService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.security.AgentContext;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in agent: profile, outlets, floats, wallets and balances, KPIs, sub-agents and channel activity. */
@RestController
public class AgentMeController implements AgentMeApi {
    private final ProfileService profile;
    private final CrudEngine crud;
    private final Http http;

    public AgentMeController(ProfileService profile, CrudEngine crud, Http http) {
        this.profile = profile;
        this.crud = crud;
        this.http = http;
    }

    @Override
    public ResponseEntity<Agent> agentMeGet() {
        return http.ok(profile.agent(), Agent.class);
    }

    @Override
    public ResponseEntity<AgentOutletPage> agentMeOutlets(Integer limit, String cursor) {
        return http.ok(profile.outlets(), AgentOutletPage.class);
    }

    @Override
    public ResponseEntity<AgentFloatPage> agentMeFloats(Integer limit, String cursor) {
        return http.ok(profile.floats(limit, cursor), AgentFloatPage.class);
    }

    @Override
    public ResponseEntity<Wallet> agentMeFloatWallet() {
        return http.ok(profile.floatWallet(), Wallet.class);
    }

    @Override
    public ResponseEntity<AccountBalance> agentMeFloatBalance() {
        return http.ok(profile.floatBalance(), AccountBalance.class);
    }

    @Override
    public ResponseEntity<AccountBalance> agentMeCommissionBalance() {
        return http.ok(profile.commissionBalance(), AccountBalance.class);
    }

    @Override
    public ResponseEntity<AgentPerformanceDailyPage> agentMePerformance(LocalDate from, LocalDate to, UUID subAgentId,
                                                                        Integer limit, String cursor) {
        return http.ok(profile.performance(from, to, subAgentId, limit, cursor), AgentPerformanceDailyPage.class);
    }

    @Override
    public ResponseEntity<AgentPage> agentMeSubAgents(Integer limit, String cursor) {
        return http.ok(profile.subAgents(limit, cursor), AgentPage.class);
    }

    /** The agent's own channel activity (always filtered by the agent of the token). */
    @Override
    public ResponseEntity<AgentActionPage> agentMeActivity(Integer limit, String cursor) {
        return crud.list(ActionAudit.TABLE, AgentActionPage.class, Crud.params()
                .with("agent_id", AgentContext.agentId()).with("limit", limit).with("cursor", cursor));
    }
}
