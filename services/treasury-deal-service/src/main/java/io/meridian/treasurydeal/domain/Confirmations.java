package io.meridian.treasurydeal.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * SWIFT-style deal confirmations: builds MT320 (money market) and MT300 (FX) message text, parses the tag blocks of
 * an inbound message into normalised fields, and matches inbound fields (counterparty's perspective) against the
 * fields expected from our deal (our perspective).
 *
 * <p>Normalised field names - money market: party_role (LENDER/BORROWER), trade_date, value_date, maturity_date,
 * currency, principal, interest_rate, interest_amount, day_count; FX: trade_date, value_date, rate, buy_currency,
 * buy_amount, sell_currency, sell_amount and for swaps far_value_date, far_rate.
 */
public final class Confirmations {
    static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;
    static final BigDecimal AMOUNT_TOLERANCE = new BigDecimal("0.01");
    static final BigDecimal RATE_TOLERANCE = new BigDecimal("0.0000001");
    static final Set<String> AMOUNTS = Set.of("principal", "interest_amount", "buy_amount", "sell_amount");
    static final Set<String> RATES = Set.of("interest_rate", "rate", "far_rate");
    /** Fields compared only when the inbound confirmation carries them. */
    static final Set<String> OPTIONAL = Set.of("interest_amount", "day_count", "far_value_date", "far_rate");

    private Confirmations() {}

    public record MoneyMarket(String reference, String ourBic, String theirBic, boolean weLend, LocalDate tradeDate,
                              LocalDate valueDate, LocalDate maturityDate, String currency, BigDecimal principal,
                              BigDecimal interest, BigDecimal rate, String dayCount) {}

    /** One FX leg from our perspective (what we buy and what we sell). */
    public record FxLeg(String legType, LocalDate valueDate, BigDecimal rate, String buyCurrency, BigDecimal buyAmount,
                        String sellCurrency, BigDecimal sellAmount) {}

    public record Fx(String reference, String ourBic, String theirBic, LocalDate tradeDate, List<FxLeg> legs,
                     boolean nonDeliverable, String settlementCurrency, LocalDate fixingDate) {}

    public record Mismatch(String field, String ours, String theirs) {}

    // ------------------------------------------------------------------ outbound text

    public static String mt320(MoneyMarket m) {
        StringBuilder b = new StringBuilder();
        b.append("{1:F01").append(pad(m.ourBic())).append("}{2:I320").append(pad(m.theirBic())).append("N}{4:\n");
        tag(b, "15A", "");
        tag(b, "20", m.reference());
        tag(b, "22A", "NEWT");
        tag(b, "22B", "CONF");
        tag(b, "82A", m.ourBic());
        tag(b, "87A", m.theirBic());
        tag(b, "15B", "");
        tag(b, "17R", m.weLend() ? "L" : "B");
        tag(b, "30T", m.tradeDate().format(YMD));
        tag(b, "30V", m.valueDate().format(YMD));
        if (m.maturityDate() != null) {
            tag(b, "30P", m.maturityDate().format(YMD));
        }
        tag(b, "32B", m.currency() + amount(m.principal()));
        if (m.maturityDate() != null) {
            tag(b, "30X", m.maturityDate().format(YMD));
        }
        if (m.interest() != null) {
            // 34E: interest amount; "N" when the sender (we) pays the interest (we borrow)
            tag(b, "34E", (m.weLend() ? "" : "N") + m.currency() + amount(m.interest()));
        }
        tag(b, "37G", amount(m.rate().multiply(BigDecimal.valueOf(100))));
        tag(b, "14D", dayCountText(m.dayCount()));
        b.append("-}");
        return b.toString();
    }

    public static String mt300(Fx f) {
        StringBuilder b = new StringBuilder();
        b.append("{1:F01").append(pad(f.ourBic())).append("}{2:I300").append(pad(f.theirBic())).append("N}{4:\n");
        tag(b, "15A", "");
        tag(b, "20", f.reference());
        tag(b, "22A", "NEWT");
        tag(b, "94A", "BILA");
        tag(b, "82A", f.ourBic());
        tag(b, "87A", f.theirBic());
        if (f.nonDeliverable()) {
            tag(b, "17O", "Y");
            if (f.settlementCurrency() != null) {
                tag(b, "32E", f.settlementCurrency());
            }
            if (f.fixingDate() != null) {
                tag(b, "30F", f.fixingDate().format(YMD));
            }
        }
        for (FxLeg leg : f.legs()) {
            tag(b, "15B", "");
            tag(b, "30T", f.tradeDate().format(YMD));
            tag(b, "30V", leg.valueDate().format(YMD));
            tag(b, "36", amount(leg.rate()));
            tag(b, "32B", leg.buyCurrency() + amount(leg.buyAmount()));
            tag(b, "33B", leg.sellCurrency() + amount(leg.sellAmount()));
        }
        b.append("-}");
        return b.toString();
    }

    // ------------------------------------------------------------------ expected fields (our perspective)

    public static Map<String, String> expected(MoneyMarket m) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("party_role", m.weLend() ? "LENDER" : "BORROWER");
        f.put("trade_date", m.tradeDate().toString());
        f.put("value_date", m.valueDate().toString());
        if (m.maturityDate() != null) {
            f.put("maturity_date", m.maturityDate().toString());
        }
        f.put("currency", m.currency());
        f.put("principal", m.principal().toPlainString());
        f.put("interest_rate", m.rate().toPlainString());
        if (m.interest() != null) {
            f.put("interest_amount", m.interest().toPlainString());
        }
        f.put("day_count", m.dayCount());
        return f;
    }

    public static Map<String, String> expected(Fx fx) {
        Map<String, String> f = new LinkedHashMap<>();
        FxLeg near = fx.legs().getFirst();
        f.put("trade_date", fx.tradeDate().toString());
        f.put("value_date", near.valueDate().toString());
        f.put("rate", near.rate().toPlainString());
        f.put("buy_currency", near.buyCurrency());
        f.put("buy_amount", near.buyAmount().toPlainString());
        f.put("sell_currency", near.sellCurrency());
        f.put("sell_amount", near.sellAmount().toPlainString());
        if (fx.legs().size() > 1) {
            FxLeg far = fx.legs().get(1);
            f.put("far_value_date", far.valueDate().toString());
            f.put("far_rate", far.rate().toPlainString());
        }
        return f;
    }

    // ------------------------------------------------------------------ inbound

    /** Tag -> values (in order of appearance) of an MT message body. */
    public static Map<String, List<String>> tags(String text) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (text == null) {
            return out;
        }
        String tag = null;
        StringBuilder value = new StringBuilder();
        for (String raw : text.replace("\r", "").split("\n")) {
            String line = raw.strip();
            if (line.startsWith(":") && line.indexOf(':', 1) > 1) {
                if (tag != null) {
                    out.computeIfAbsent(tag, k -> new ArrayList<>()).add(value.toString().strip());
                }
                int end = line.indexOf(':', 1);
                tag = line.substring(1, end);
                value = new StringBuilder(line.substring(end + 1));
            } else if (tag != null && !line.startsWith("-}")) {
                value.append('\n').append(line);
            }
        }
        if (tag != null) {
            out.computeIfAbsent(tag, k -> new ArrayList<>()).add(value.toString().strip());
        }
        return out;
    }

    /** Parses an inbound MT320 / MT300 into normalised fields from the sender's (counterparty's) perspective. */
    public static Map<String, String> parse(String messageType, String text) {
        Map<String, List<String>> t = tags(text);
        Map<String, String> f = new LinkedHashMap<>();
        if ("MT320".equals(messageType)) {
            first(t, "17R").ifPresent(v -> f.put("party_role", v.startsWith("L") ? "LENDER" : "BORROWER"));
            first(t, "30T").ifPresent(v -> f.put("trade_date", date(v)));
            first(t, "30V").ifPresent(v -> f.put("value_date", date(v)));
            first(t, "30P").ifPresent(v -> f.put("maturity_date", date(v)));
            first(t, "32B").ifPresent(v -> {
                f.put("currency", v.substring(0, 3));
                f.put("principal", number(v.substring(3)));
            });
            first(t, "34E").ifPresent(v -> {
                String s = v.startsWith("N") ? v.substring(1) : v;
                f.put("interest_amount", number(s.substring(3)));
            });
            first(t, "37G").ifPresent(v -> f.put("interest_rate",
                    new BigDecimal(number(v.startsWith("N") ? v.substring(1) : v)).movePointLeft(2).toPlainString()));
            first(t, "14D").ifPresent(v -> f.put("day_count", dayCountEnum(v)));
        } else if ("MT300".equals(messageType)) {
            first(t, "30T").ifPresent(v -> f.put("trade_date", date(v)));
            List<String> vds = t.getOrDefault("30V", List.of());
            List<String> rates = t.getOrDefault("36", List.of());
            List<String> bought = t.getOrDefault("32B", List.of());
            List<String> sold = t.getOrDefault("33B", List.of());
            if (!vds.isEmpty()) {
                f.put("value_date", date(vds.getFirst()));
            }
            if (!rates.isEmpty()) {
                f.put("rate", number(rates.getFirst()));
            }
            if (!bought.isEmpty()) {
                f.put("buy_currency", bought.getFirst().substring(0, 3));
                f.put("buy_amount", number(bought.getFirst().substring(3)));
            }
            if (!sold.isEmpty()) {
                f.put("sell_currency", sold.getFirst().substring(0, 3));
                f.put("sell_amount", number(sold.getFirst().substring(3)));
            }
            if (vds.size() > 1) {
                f.put("far_value_date", date(vds.get(1)));
            }
            if (rates.size() > 1) {
                f.put("far_rate", number(rates.get(1)));
            }
        } else {
            throw new IllegalArgumentException("unsupported message type " + messageType);
        }
        return f;
    }

    /** Converts the counterparty's view to ours: their LENDER is our BORROWER, what they buy we sell. */
    public static Map<String, String> toOurPerspective(Map<String, String> theirs) {
        Map<String, String> ours = new LinkedHashMap<>(theirs);
        if (theirs.containsKey("party_role")) {
            ours.put("party_role", "LENDER".equalsIgnoreCase(theirs.get("party_role")) ? "BORROWER" : "LENDER");
        }
        swap(ours, theirs, "buy_currency", "sell_currency");
        swap(ours, theirs, "buy_amount", "sell_amount");
        return ours;
    }

    /** Compares expected (ours) with inbound (already in our perspective); empty = matched. */
    public static List<Mismatch> match(Map<String, String> expected, Map<String, String> inbound) {
        List<Mismatch> out = new ArrayList<>();
        expected.forEach((field, ours) -> {
            String theirs = inbound.get(field);
            if (theirs == null || theirs.isBlank()) {
                if (!OPTIONAL.contains(field)) {
                    out.add(new Mismatch(field, ours, null));
                }
                return;
            }
            if (!same(field, ours, theirs.strip())) {
                out.add(new Mismatch(field, ours, theirs));
            }
        });
        return out;
    }

    static boolean same(String field, String ours, String theirs) {
        try {
            if (AMOUNTS.contains(field)) {
                return new BigDecimal(ours).subtract(new BigDecimal(theirs)).abs().compareTo(AMOUNT_TOLERANCE) < 0;
            }
            if (RATES.contains(field)) {
                return new BigDecimal(ours).subtract(new BigDecimal(theirs)).abs().compareTo(RATE_TOLERANCE) <= 0;
            }
        } catch (NumberFormatException e) {
            return false;
        }
        return ours.equalsIgnoreCase(theirs);
    }

    // ------------------------------------------------------------------ helpers

    static String dayCountText(String dayCount) {
        return switch (dayCount == null ? "ACT_360" : dayCount) {
            case "ACT_365" -> "ACT/365";
            case "ACT_ACT_ISDA" -> "ACT/ACT";
            case "THIRTY_360" -> "360/360";
            case "THIRTY_E_360" -> "30E/360";
            case "ACT_365L" -> "AFI/365";
            default -> "ACT/360";
        };
    }

    static String dayCountEnum(String text) {
        return switch (text.toUpperCase(Locale.ROOT)) {
            case "ACT/365" -> "ACT_365";
            case "ACT/ACT" -> "ACT_ACT_ISDA";
            case "360/360" -> "THIRTY_360";
            case "30E/360" -> "THIRTY_E_360";
            case "AFI/365" -> "ACT_365L";
            default -> "ACT_360";
        };
    }

    /** SWIFT amount: comma as decimal separator, no thousands separators, at least the comma. */
    static String amount(BigDecimal v) {
        String s = v.stripTrailingZeros().toPlainString();
        if (!s.contains(".")) {
            return s + ",";
        }
        return s.replace('.', ',');
    }

    static String number(String swiftAmount) {
        String s = swiftAmount.strip().replace(',', '.');
        if (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        return new BigDecimal(s).toPlainString();
    }

    static String date(String yyyymmdd) {
        return LocalDate.parse(yyyymmdd.strip(), YMD).toString();
    }

    private static java.util.Optional<String> first(Map<String, List<String>> t, String tag) {
        List<String> v = t.get(tag);
        return v == null || v.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(v.getFirst());
    }

    private static void swap(Map<String, String> ours, Map<String, String> theirs, String a, String b) {
        ours.remove(a);
        ours.remove(b);
        if (theirs.containsKey(a)) {
            ours.put(b, theirs.get(a));
        }
        if (theirs.containsKey(b)) {
            ours.put(a, theirs.get(b));
        }
    }

    private static String pad(String bic) {
        String b = bic == null ? "XXXXXXXXXXXX" : bic;
        return (b + "XXXXXXXXXXXX").substring(0, 12);
    }

    private static void tag(StringBuilder b, String tag, String value) {
        b.append(':').append(tag).append(':').append(value).append('\n');
    }
}
