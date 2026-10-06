package io.meridian.rewards.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Pure FIFO lot accounting over an append-only points ledger.
 *
 * <p>Every confirmed (non-pending) credit is a lot (EARN, BONUS, positive ADJUST / REVERSE, TRANSFER_IN) dated by its
 * earn date. Debits consume lots oldest earn date first (ties in ledger order); an EXPIRE entry that names a lot
 * consumes exactly that lot. Pending entries never touch lots. The sum of lot remainders equals the available
 * balance, and no remainder is ever negative.
 */
public final class PointsLots {
    private PointsLots() {}

    private static final Set<String> CREDIT_TYPES = Set.of("EARN", "BONUS", "ADJUST", "REVERSE", "TRANSFER_IN");

    /** One ledger entry in ledger (chronological) order. */
    public record Entry(UUID id, String type, long points, boolean pending, LocalDate earnDate, LocalDate expiresOn,
                        UUID lotId) {}

    public static final class Lot {
        private final UUID id;
        private final LocalDate earnDate;
        private final LocalDate expiresOn;
        private final long original;
        private final int seq;
        private long remaining;

        Lot(UUID id, LocalDate earnDate, LocalDate expiresOn, long original, int seq) {
            this.id = id;
            this.earnDate = earnDate;
            this.expiresOn = expiresOn;
            this.original = original;
            this.remaining = original;
            this.seq = seq;
        }

        public UUID id() {
            return id;
        }

        public LocalDate earnDate() {
            return earnDate;
        }

        public LocalDate expiresOn() {
            return expiresOn;
        }

        public long original() {
            return original;
        }

        public long remaining() {
            return remaining;
        }

        int seq() {
            return seq;
        }
    }

    /** Lots in FIFO order with their remainders; {@code unallocated} counts debits no lot could cover. */
    public record State(List<Lot> lots, long unallocated) {
        public long balance() {
            return lots.stream().mapToLong(Lot::remaining).sum();
        }

        public List<Lot> open() {
            return lots.stream().filter(l -> l.remaining() > 0).toList();
        }

        /** Open lots whose expiry date is on or before {@code date}. */
        public List<Lot> expiredBy(LocalDate date) {
            return open().stream().filter(l -> l.expiresOn() != null && !l.expiresOn().isAfter(date)).toList();
        }

        /** Points that expire in [from, to]. */
        public long expiringBetween(LocalDate from, LocalDate to) {
            return open().stream().filter(l -> l.expiresOn() != null && !l.expiresOn().isBefore(from)
                    && !l.expiresOn().isAfter(to)).mapToLong(Lot::remaining).sum();
        }

        public LocalDate nextExpiry(LocalDate from) {
            return open().stream().map(Lot::expiresOn).filter(d -> d != null && !d.isBefore(from))
                    .min(Comparator.naturalOrder()).orElse(null);
        }
    }

    public static State replay(List<Entry> entries) {
        List<Lot> lots = new ArrayList<>();
        long unallocated = 0;
        int seq = 0;
        for (Entry e : entries) {
            if (e.pending() || e.points() == 0) {
                continue;
            }
            if (e.points() > 0) {
                if (CREDIT_TYPES.contains(e.type())) {
                    lots.add(new Lot(e.id(), e.earnDate(), e.expiresOn(), e.points(), seq++));
                    lots.sort(Comparator.comparing(Lot::earnDate).thenComparingInt(Lot::seq));
                }
                continue;
            }
            long need = -e.points();
            if ("EXPIRE".equals(e.type()) && e.lotId() != null) {
                for (Lot l : lots) {
                    if (l.id().equals(e.lotId())) {
                        long take = Math.min(need, l.remaining);
                        l.remaining -= take;
                        need -= take;
                        break;
                    }
                }
            }
            for (Lot l : lots) {
                if (need == 0) {
                    break;
                }
                long take = Math.min(need, l.remaining);
                l.remaining -= take;
                need -= take;
            }
            unallocated += need;
        }
        return new State(List.copyOf(lots), unallocated);
    }

    public static Map<UUID, Lot> byId(State s) {
        return s.lots().stream().collect(Collectors.toMap(Lot::id, Function.identity()));
    }
}
