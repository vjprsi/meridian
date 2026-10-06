package io.meridian.cardauthorization.domain;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Fluent builder of decision-engine contexts for unit tests (an approvable baseline by default). */
final class Ctx {
    OffsetDateTime now = OffsetDateTime.parse("2026-10-06T12:00:00Z");
    String cardStatus = "ACTIVE";
    int expMonth = 12;
    int expYear = 2029;
    String programStatus = "ACTIVE";
    String fraudPolicy = "FAIL_OPEN";
    int fraudDeclineScore = 800;
    String country = "US";
    AuthContext.ProductSnap product = new AuthContext.ProductSnap("ACTIVE", "REQUIRED", true, true, true, true);
    final List<AuthContext.Control> controls = new ArrayList<>();
    final List<AuthContext.Limit> limits = new ArrayList<>();
    final Map<String, AuthContext.Usage> usage = new HashMap<>();
    String channel = "POS";
    String pos = "CHIP";
    BigDecimal amount = new BigDecimal("25.00");
    String currency = "USD";
    String mcc = "5411";
    String merchantId = "M1";
    String merchantCountry = "US";
    boolean international;
    String eci;
    boolean cvv;
    boolean expiry;
    boolean pin;

    static Ctx base() {
        return new Ctx();
    }

    Ctx status(String s) {
        cardStatus = s;
        return this;
    }

    Ctx amount(String a) {
        amount = new BigDecimal(a);
        return this;
    }

    Ctx channel(String c) {
        channel = c;
        return this;
    }

    Ctx mcc(String m) {
        mcc = m;
        return this;
    }

    Ctx control(String type, String... values) {
        controls.add(new AuthContext.Control(UUID.randomUUID(), type, "ANY", Set.of(values), null, null, Set.of(), null,
                null, null, true, null, null));
        return this;
    }

    Ctx amountMax(String max) {
        controls.add(new AuthContext.Control(UUID.randomUUID(), "AMOUNT_MAX", "ANY", Set.of(), new BigDecimal(max), "USD",
                Set.of(), null, null, null, true, null, null));
        return this;
    }

    Ctx window(Set<DayOfWeek> days, String start, String end, String zone) {
        controls.add(new AuthContext.Control(UUID.randomUUID(), "TIME_WINDOW", "ANY", Set.of(), null, null, days,
                LocalTime.parse(start), LocalTime.parse(end), ZoneId.of(zone), true, null, null));
        return this;
    }

    Ctx limit(String id, String period, String max, Integer count, String usedAmount, int usedCount) {
        limits.add(new AuthContext.Limit(id, period, "ANY", max == null ? null : new BigDecimal(max), count, "USD", true));
        if (usedAmount != null) {
            usage.put(AuthContext.usageKey(id, Periods.start(period, now)), new AuthContext.Usage(new BigDecimal(usedAmount), usedCount));
        }
        return this;
    }

    AuthContext build() {
        return new AuthContext(now,
                new AuthContext.CardSnap(UUID.randomUUID(), cardStatus, expMonth, expYear, true),
                new AuthContext.ProgramSnap(UUID.randomUUID(), programStatus, "DEBIT", "USD", country, fraudPolicy,
                        fraudDeclineScore, true, new BigDecimal("100")),
                product, controls, limits, usage,
                new AuthContext.Request("AUTHORIZATION", channel, pos, amount, currency, amount, currency, mcc, merchantId,
                        merchantCountry, !"ECOM".equals(channel), international, eci, cvv, expiry, pin, false));
    }
}
