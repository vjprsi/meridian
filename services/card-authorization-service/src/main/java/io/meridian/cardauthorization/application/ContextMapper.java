package io.meridian.cardauthorization.application;

import io.meridian.cardauthorization.domain.AuthContext;
import io.meridian.cardauthorization.domain.Periods;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Maps card-service's auth-context document and the stored counters into the engine's immutable snapshot. */
public final class ContextMapper {
    private ContextMapper() {}

    public static AuthContext.CardSnap card(JsonNode ctx) {
        JsonNode c = ctx.path("card");
        return new AuthContext.CardSnap(uuid(c, "id"), text(c, "status"), c.path("expiry_month").asInt(12),
                c.path("expiry_year").asInt(9999), c.path("pin_set").asBoolean(false));
    }

    public static AuthContext.ProgramSnap program(JsonNode ctx) {
        JsonNode p = ctx.path("program");
        if (p.isMissingNode() || p.isNull()) {
            return null;
        }
        return new AuthContext.ProgramSnap(uuid(p, "id"), text(p, "status"), text(p, "funding_model"),
                text(p, "currency"), text(p, "country"), text(p, "fraud_policy") == null ? "FAIL_OPEN" : text(p, "fraud_policy"),
                p.hasNonNull("fraud_decline_score") ? p.get("fraud_decline_score").asInt() : 800,
                !p.has("stand_in_enabled") || p.get("stand_in_enabled").asBoolean(true), dec(p, "stand_in_max_amount"));
    }

    public static AuthContext.ProductSnap product(JsonNode ctx) {
        JsonNode p = ctx.path("product");
        if (p.isMissingNode() || p.isNull()) {
            return null;
        }
        return new AuthContext.ProductSnap(text(p, "status"), text(p, "pin_policy"), flag(p, "contactless_enabled"),
                flag(p, "atm_enabled"), flag(p, "ecom_enabled"), flag(p, "international_enabled"));
    }

    public static List<AuthContext.Control> controls(JsonNode ctx) {
        List<AuthContext.Control> out = new ArrayList<>();
        for (JsonNode k : ctx.path("controls")) {
            Set<String> values = new LinkedHashSet<>();
            for (JsonNode v : k.path("values")) {
                values.add(v.asString().trim().toUpperCase(Locale.ROOT));
            }
            JsonNode tw = k.path("time_window");
            Set<DayOfWeek> days = new LinkedHashSet<>();
            for (JsonNode d : tw.path("days")) {
                days.add(day(d.asString()));
            }
            LocalTime start = tw.hasNonNull("start") ? LocalTime.parse(tw.get("start").asString()) : null;
            LocalTime end = tw.hasNonNull("end") ? LocalTime.parse(tw.get("end").asString()) : null;
            String tz = text(k, "timezone") != null ? text(k, "timezone") : text(tw, "timezone");
            out.add(new AuthContext.Control(uuid(k, "id"), text(k, "control_type"),
                    text(k, "channel") == null ? "ANY" : text(k, "channel"), values, dec(k, "max_amount"),
                    text(k, "currency"), days, start, end, tz == null ? null : ZoneId.of(tz),
                    !k.has("is_active") || k.get("is_active").asBoolean(true), ts(k, "valid_from"), ts(k, "valid_to")));
        }
        return out;
    }

    public static List<AuthContext.Limit> limits(JsonNode ctx) {
        List<AuthContext.Limit> out = new ArrayList<>();
        for (JsonNode l : ctx.path("limits")) {
            out.add(new AuthContext.Limit(text(l, "id"), text(l, "period"),
                    text(l, "channel") == null ? "ANY" : text(l, "channel"), dec(l, "max_amount"),
                    l.hasNonNull("max_count") ? l.get("max_count").asInt() : null, text(l, "currency"),
                    !l.has("is_active") || l.get("is_active").asBoolean(true)));
        }
        return out;
    }

    /** Current-period usage keyed by {@link AuthContext#usageKey}. */
    public static Map<String, AuthContext.Usage> usage(List<? extends JsonNode> counters, OffsetDateTime now) {
        Map<String, AuthContext.Usage> out = new HashMap<>();
        for (JsonNode c : counters) {
            OffsetDateTime start = OffsetDateTime.parse(c.get("period_start").asString());
            if (!start.toInstant().equals(Periods.start(c.get("period").asString(), now).toInstant())) {
                continue;
            }
            out.put(AuthContext.usageKey(c.get("limit_ref").asString(), start),
                    new AuthContext.Usage(new BigDecimal(c.get("used_amount").asString()), c.get("used_count").asInt()));
        }
        return out;
    }

    static DayOfWeek day(String s) {
        String u = s.trim().toUpperCase(Locale.ROOT);
        for (DayOfWeek d : DayOfWeek.values()) {
            if (d.name().startsWith(u.length() >= 3 ? u.substring(0, 3) : u)) {
                return d;
            }
        }
        throw new IllegalArgumentException("unknown day " + s);
    }

    private static boolean flag(JsonNode n, String f) {
        return !n.has(f) || n.get(f).isNull() || n.get(f).asBoolean(true);
    }

    static String text(JsonNode n, String f) {
        JsonNode v = n == null ? null : n.get(f);
        return v == null || v.isNull() ? null : v.asString();
    }

    static UUID uuid(JsonNode n, String f) {
        String s = text(n, f);
        return s == null ? null : UUID.fromString(s);
    }

    static BigDecimal dec(JsonNode n, String f) {
        String s = text(n, f);
        return s == null ? null : new BigDecimal(s);
    }

    static OffsetDateTime ts(JsonNode n, String f) {
        String s = text(n, f);
        return s == null ? null : OffsetDateTime.parse(s);
    }
}
