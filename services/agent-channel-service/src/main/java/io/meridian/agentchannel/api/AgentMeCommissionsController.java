package io.meridian.agentchannel.api;

import io.meridian.agentchannel.api.generated.AgentMeCommissionsApi;
import io.meridian.agentchannel.api.generated.model.CommissionEarningPage;
import io.meridian.agentchannel.application.ProfileService;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Commissions earned by the agent. */
@RestController
public class AgentMeCommissionsController implements AgentMeCommissionsApi {
    private final ProfileService profile;
    private final Http http;

    public AgentMeCommissionsController(ProfileService profile, Http http) {
        this.profile = profile;
        this.http = http;
    }

    @Override
    public ResponseEntity<CommissionEarningPage> agentMeCommissionsList(LocalDate from, LocalDate to, Integer limit,
                                                                        String cursor) {
        return http.ok(profile.commissions(from, to, limit, cursor), CommissionEarningPage.class);
    }

    @Override
    public ResponseEntity<Map<String, Object>> agentMeCommissionsSummary(LocalDate from, LocalDate to) {
        return http.object(profile.commissionSummary(from, to));
    }
}
