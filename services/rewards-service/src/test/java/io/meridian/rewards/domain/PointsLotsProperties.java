package io.meridian.rewards.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/** FIFO lot accounting invariants over random ledgers (credits and debits never exceeding the balance). */
class PointsLotsProperties {
    static final LocalDate START = LocalDate.of(2026, 1, 1);

    record Op(boolean credit, int amount, int dayOffset, boolean expireLot) {}

    @Provide
    Arbitrary<List<Op>> ledgers() {
        Arbitrary<Op> op = Combinators.combine(Arbitraries.of(true, true, false), Arbitraries.integers().between(1, 500),
                Arbitraries.integers().between(0, 400), Arbitraries.of(false, false, true)).as(Op::new);
        return op.list().ofMinSize(1).ofMaxSize(40);
    }

    /** Builds a valid ledger: debits are capped at the running balance; optional lot-specific expiries. */
    static List<PointsLots.Entry> build(List<Op> ops, boolean allowExpiry) {
        List<PointsLots.Entry> entries = new ArrayList<>();
        long balance = 0;
        LocalDate d = START;
        for (Op op : ops) {
            d = d.plusDays(op.dayOffset() % 10); // ledger order is chronological
            if (op.credit()) {
                entries.add(new PointsLots.Entry(UUID.randomUUID(), "EARN", op.amount(), false, d, d.plusMonths(12), null));
                balance += op.amount();
            } else if (balance > 0) {
                if (allowExpiry && op.expireLot()) {
                    PointsLots.State s = PointsLots.replay(entries);
                    PointsLots.Lot lot = s.open().getLast();
                    entries.add(new PointsLots.Entry(UUID.randomUUID(), "EXPIRE", -lot.remaining(), false, d, null, lot.id()));
                    balance -= lot.remaining();
                } else {
                    long take = Math.min(balance, op.amount());
                    entries.add(new PointsLots.Entry(UUID.randomUUID(), "REDEEM", -take, false, d, null, null));
                    balance -= take;
                }
            }
        }
        return entries;
    }

    @Property
    void lotsSumToNetBalanceAndNeverGoNegative(@ForAll("ledgers") List<Op> ops) {
        List<PointsLots.Entry> entries = build(ops, true);
        long net = entries.stream().mapToLong(PointsLots.Entry::points).sum();
        PointsLots.State s = PointsLots.replay(entries);
        assertThat(s.balance()).isEqualTo(net);
        assertThat(s.unallocated()).isZero();
        assertThat(s.lots()).allSatisfy(l -> assertThat(l.remaining()).isBetween(0L, l.original()));
    }

    @Property
    void withoutExpiriesDebitsConsumeOldestFirst(@ForAll("ledgers") List<Op> ops) {
        PointsLots.State s = PointsLots.replay(build(ops, false));
        // FIFO: once a lot has points left, every younger lot is untouched.
        boolean seenOpen = false;
        for (PointsLots.Lot l : s.lots()) {
            if (seenOpen) {
                assertThat(l.remaining()).isEqualTo(l.original());
            }
            if (l.remaining() > 0) {
                seenOpen = true;
            }
        }
    }

    @Property
    void pendingEntriesNeverTouchLots(@ForAll("ledgers") List<Op> ops) {
        List<PointsLots.Entry> entries = new ArrayList<>(build(ops, true));
        long before = PointsLots.replay(entries).balance();
        entries.add(new PointsLots.Entry(UUID.randomUUID(), "EARN", 999, true, START, null, null));
        entries.add(new PointsLots.Entry(UUID.randomUUID(), "REVERSE", -999, true, START, null, null));
        assertThat(PointsLots.replay(entries).balance()).isEqualTo(before);
    }

    @Example
    void expiryOfANamedLotLeavesOlderLotsAlone() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        List<PointsLots.Entry> e = List.of(
                new PointsLots.Entry(a, "EARN", 100, false, START, START.plusMonths(24), null),
                new PointsLots.Entry(b, "EARN", 50, false, START.plusDays(1), START.plusMonths(1), null),
                new PointsLots.Entry(UUID.randomUUID(), "REDEEM", -30, false, START.plusDays(2), null, null),
                new PointsLots.Entry(UUID.randomUUID(), "EXPIRE", -50, false, START.plusMonths(1), null, b));
        PointsLots.State s = PointsLots.replay(e);
        assertThat(s.balance()).isEqualTo(70);
        assertThat(PointsLots.byId(s).get(a).remaining()).isEqualTo(70);
        assertThat(PointsLots.byId(s).get(b).remaining()).isZero();
        assertThat(s.expiredBy(START.plusMonths(1))).isEmpty();
    }

    @Example
    void expiredByAndExpiringWindow() {
        UUID a = UUID.randomUUID();
        List<PointsLots.Entry> e = List.of(
                new PointsLots.Entry(a, "EARN", 100, false, START, START.plusDays(10), null),
                new PointsLots.Entry(UUID.randomUUID(), "EARN", 40, false, START.plusDays(5), START.plusDays(40), null),
                new PointsLots.Entry(UUID.randomUUID(), "REDEEM", -60, false, START.plusDays(6), null, null));
        PointsLots.State s = PointsLots.replay(e);
        assertThat(s.expiredBy(START.plusDays(10))).singleElement().satisfies(l -> assertThat(l.remaining()).isEqualTo(40));
        assertThat(s.expiringBetween(START, START.plusDays(30))).isEqualTo(40);
        assertThat(s.nextExpiry(START)).isEqualTo(START.plusDays(10));
    }
}
