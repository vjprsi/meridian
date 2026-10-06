package io.meridian.liquidity.application;

import io.meridian.approvals.ApprovalOutcomeHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** One {@link ApprovalOutcomeHandler} per ratio resource type (lcr_calculation, nsfr_calculation). */
@Configuration
public class RatioApprovalHandlers {

    @Bean
    ApprovalOutcomeHandler lcrApprovalHandler(RatioService ratios) {
        return new RatioApprovalHandler(ratios, RatioService.LCR_APPROVAL_RESOURCE);
    }

    @Bean
    ApprovalOutcomeHandler nsfrApprovalHandler(RatioService ratios) {
        return new RatioApprovalHandler(ratios, RatioService.NSFR_APPROVAL_RESOURCE);
    }
}
