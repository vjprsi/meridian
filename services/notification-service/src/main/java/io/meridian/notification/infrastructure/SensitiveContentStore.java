package io.meridian.notification.infrastructure;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * In-memory, time-limited holder of the clear subject / body of notifications with sensitive content (SEC-10).
 * Nothing here is ever written to the database, the outbox, logs or API responses; entries are removed once the
 * message was handed to a provider (or cancelled / expired) and evicted after {@code ttl} at the latest. A process
 * restart (or a retry picked up by another replica) therefore cannot send the message: it fails with
 * {@code sensitive_content_unavailable} and the caller issues a fresh code, which is the safe outcome for OTPs.
 */
@Component
public class SensitiveContentStore {
    /** Clear content of one notification. */
    public record Clear(String subject, String body) {}

    private record Entry(Clear clear, Instant expires) {}

    private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final Clock clock;

    @Autowired
    public SensitiveContentStore(@Value("${meridian.notifications.sensitive-ttl-seconds:900}") long ttlSeconds) {
        this(Duration.ofSeconds(ttlSeconds), Clock.systemUTC());
    }

    public SensitiveContentStore(Duration ttl, Clock clock) {
        this.ttl = ttl;
        this.clock = clock;
    }

    public void put(UUID notificationId, Clear clear) {
        entries.put(notificationId, new Entry(clear, clock.instant().plus(ttl)));
    }

    public Optional<Clear> get(UUID notificationId) {
        Entry e = entries.get(notificationId);
        if (e == null) {
            return Optional.empty();
        }
        if (e.expires().isBefore(clock.instant())) {
            entries.remove(notificationId, e);
            return Optional.empty();
        }
        return Optional.of(e.clear());
    }

    public void remove(UUID notificationId) {
        entries.remove(notificationId);
    }

    /** Drops expired entries; returns how many were removed. */
    public int purgeExpired() {
        Instant now = clock.instant();
        int[] n = {0};
        entries.entrySet().removeIf(en -> {
            boolean expired = en.getValue().expires().isBefore(now);
            n[0] += expired ? 1 : 0;
            return expired;
        });
        return n[0];
    }

    public int size() {
        return entries.size();
    }
}
