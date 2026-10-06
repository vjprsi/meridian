package io.meridian.approvals;

import io.meridian.web.client.ServiceClient;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.json.JsonMapper;

/** The {@link Approvals} client for every service; the decision listener when the service registers handlers. */
@AutoConfiguration(afterName = "io.meridian.web.MeridianWebAutoConfiguration")
public class MeridianApprovalsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Approvals approvals(ServiceClient client, @Value("${spring.application.name}") String application,
                        @Value("${meridian.approvals.mode:local}") String mode) {
        return new Approvals(client, application, mode);
    }

    @Bean
    @ConditionalOnBean(ApprovalOutcomeHandler.class)
    @ConditionalOnProperty(name = "meridian.approvals.listener-enabled", havingValue = "true", matchIfMissing = true)
    ApprovalEventsDispatcher approvalEventsDispatcher(List<ApprovalOutcomeHandler> handlers, JsonMapper json) {
        return new ApprovalEventsDispatcher(handlers, json);
    }
}
