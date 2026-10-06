package io.meridian.collections.config;

import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * collections-service settings ({@code meridian.collections.*}).
 *
 * @param maxPromiseDays            latest promised date allowed, in days from today
 * @param nbaValidityHours          how long a next-best-action suggestion stays valid
 * @param defaultMaxContactsPerWeek contact cap when no strategy applies
 * @param defaultQuietStart         quiet hours start (HH:mm, UTC) when the strategy has none
 * @param defaultQuietEnd           quiet hours end (HH:mm, UTC)
 * @param syncPageLimit             page size when reading delinquent exposures from loan-service
 * @param batch                     fallback in-process scheduler (business-day-service normally drives the EOD run)
 */
@ConfigurationProperties(prefix = "meridian.collections")
public record CollectionsProperties(Integer maxPromiseDays, Integer nbaValidityHours, Integer defaultMaxContactsPerWeek,
                                    String defaultQuietStart, String defaultQuietEnd, Integer syncPageLimit, Batch batch) {

    public CollectionsProperties {
        maxPromiseDays = maxPromiseDays == null ? 30 : maxPromiseDays;
        nbaValidityHours = nbaValidityHours == null ? 48 : nbaValidityHours;
        defaultMaxContactsPerWeek = defaultMaxContactsPerWeek == null ? 3 : defaultMaxContactsPerWeek;
        defaultQuietStart = defaultQuietStart == null ? "21:00" : defaultQuietStart;
        defaultQuietEnd = defaultQuietEnd == null ? "08:00" : defaultQuietEnd;
        syncPageLimit = syncPageLimit == null ? 200 : syncPageLimit;
        batch = batch == null ? new Batch(false, List.of()) : batch;
    }

    /** @param tenants tenants processed by the fallback scheduler */
    public record Batch(boolean enabled, List<UUID> tenants) {
        public Batch {
            tenants = tenants == null ? List.of() : List.copyOf(tenants);
        }
    }
}
