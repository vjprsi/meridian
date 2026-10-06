package io.meridian.loan.config;

import io.meridian.loan.domain.BusinessCalendar;
import java.time.Clock;
import java.util.HashSet;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LoanProperties.class)
@EnableScheduling
public class LoanConfig {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    BusinessCalendar businessCalendar(LoanProperties props) {
        return new BusinessCalendar(new HashSet<>(props.holidays()));
    }
}
