package io.meridian.deposit.config;

import io.meridian.deposit.domain.Schedule;
import java.time.Clock;
import java.util.HashSet;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DepositProperties.class)
@EnableScheduling
public class DepositConfig {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    Schedule.BusinessCalendar businessCalendar(DepositProperties props) {
        return new Schedule.BusinessCalendar(new HashSet<>(props.holidays()));
    }
}
