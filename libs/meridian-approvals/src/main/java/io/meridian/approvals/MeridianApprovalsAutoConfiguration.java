package io.meridian.approvals;

import io.meridian.web.client.ServiceClient;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
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
    ApprovalsListenerSwitch approvalsListenerSwitch(List<ApprovalOutcomeHandler> handlers,
            @Value("${meridian.approvals.listener-enabled:true}") boolean listenerEnabled,
            @Value("${meridian.approvals.mode:local}") String mode) {
        return new ApprovalsListenerSwitch(listenerEnabled, mode, handlers);
    }

    /** Always registered with handlers (tests dispatch directly); its Kafka listener starts per the switch. */
    @Bean
    @ConditionalOnBean(ApprovalOutcomeHandler.class)
    ApprovalEventsDispatcher approvalEventsDispatcher(List<ApprovalOutcomeHandler> handlers, JsonMapper json,
                                                      @Value("${spring.application.name}") String application) {
        return new ApprovalEventsDispatcher(handlers, json, application);
    }
}
