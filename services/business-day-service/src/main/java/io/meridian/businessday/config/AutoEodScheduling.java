package io.meridian.businessday.config;

import io.meridian.businessday.application.EodTriggerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Platform scheduler of the cross-tenant EOD trigger. Disabled by default
 * ({@code meridian.business-day.auto-eod.enabled=true} turns it on); every tick claims the current time slot, so
 * with several instances only one sweeps per slot.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "meridian.business-day.auto-eod.enabled", havingValue = "true")
public class AutoEodScheduling {
    private static final Logger log = LoggerFactory.getLogger(AutoEodScheduling.class);

    private final EodTriggerService trigger;

    public AutoEodScheduling(EodTriggerService trigger) {
        this.trigger = trigger;
    }

    @Scheduled(fixedDelayString = "${meridian.business-day.auto-eod.interval:PT5M}",
            initialDelayString = "${meridian.business-day.auto-eod.initial-delay:PT1M}")
    public void tick() {
        try {
            trigger.tick();
        } catch (RuntimeException e) {
            log.error("Automatic EOD sweep failed", e);
        }
    }
}
