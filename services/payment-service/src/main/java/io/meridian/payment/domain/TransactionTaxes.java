package io.meridian.payment.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.JsonNode;

/**
 * Transaction taxes of a payment, from the tenant's {@code taxes} settings (payment-service settings in config-service,
 * namespace {@code ledger}). Pure and deterministic; the processor supplies the facts it needs.
 *
 * <pre>
 * taxes:
 *   scale: 0                       # rounding of each tax line (COP: whole pesos), HALF_UP
 *   gmf:                           # Colombia Gravamen a los Movimientos Financieros (Estatuto Tributario art. 871)
 *     rate: "0.004"                # 4 x 1000 on the debit
 *     directions: [OUTBOUND, INTERNAL]
 *     exempt_same_holder: true     # art. 879 num. 14: transfers between accounts of the same holder at the same bank
 *     exempt_monthly_amount: "18330900"  # art. 879 num. 1: the holder's designated exempt account, 350 UVT a month
 *   fee_vat:                       # IVA on the financial-service fee
 *     rate: "0.19"
 * </pre>
 * A debtor account is the designated exempt account when its {@code metadata.gmf_exempt} is {@code true}. No
 * {@code taxes} settings = no taxes (every other country keeps its behaviour).
 */
public final class TransactionTaxes {
    private TransactionTaxes() {
    }

    /** Facts about the payment the taxes depend on. */
    public record Facts(String direction, String paymentType, BigDecimal amount, BigDecimal fee, boolean sameHolder,
                        boolean debtorExemptAccount, BigDecimal exemptUsedThisMonth) {}

    /** One tax line. {@code exemptAmount} is the part of the base relieved by an exemption. */
    public record Line(String taxType, String name, BigDecimal rate, BigDecimal base, BigDecimal exemptAmount,
                       BigDecimal amount, String reason) {}

    public static boolean configured(JsonNode cfg) {
        return cfg != null && cfg.isObject() && (cfg.has("gmf") || cfg.has("fee_vat"));
    }

    /** True when GMF applies to this direction (so the processor knows whether to load exemption facts). */
    public static boolean gmfApplies(JsonNode cfg, String direction, String paymentType) {
        if (cfg == null || !cfg.path("gmf").isObject()) {
            return false;
        }
        JsonNode g = cfg.get("gmf");
        return contains(g.path("directions"), direction, List.of("OUTBOUND", "INTERNAL"))
                && contains(g.path("payment_types"), paymentType, null);
    }

    public static List<Line> compute(JsonNode cfg, Facts f) {
        List<Line> out = new ArrayList<>();
        if (!configured(cfg)) {
            return out;
        }
        int scale = cfg.path("scale").isNumber() ? cfg.get("scale").asInt() : 2;
        if (gmfApplies(cfg, f.direction(), f.paymentType()) && f.amount().signum() > 0) {
            JsonNode g = cfg.get("gmf");
            BigDecimal rate = dec(g, "rate", "0.004");
            String name = g.path("name").asString("GMF 4x1000");
            if (f.sameHolder() && g.path("exempt_same_holder").asBoolean(true)) {
                out.add(new Line("FINANCIAL_TRANSACTION_TAX", name, rate, f.amount(), f.amount(), BigDecimal.ZERO.setScale(scale),
                        "Exempt: transfer between accounts of the same holder (E.T. art. 879 num. 14)"));
            } else {
                BigDecimal exempt = BigDecimal.ZERO;
                String reason = null;
                BigDecimal limit = dec(g, "exempt_monthly_amount", null);
                if (f.debtorExemptAccount() && limit != null) {
                    BigDecimal left = limit.subtract(f.exemptUsedThisMonth() == null ? BigDecimal.ZERO : f.exemptUsedThisMonth()).max(BigDecimal.ZERO);
                    exempt = left.min(f.amount());
                    reason = exempt.compareTo(f.amount()) == 0
                            ? "Exempt account: within the monthly exempt amount (E.T. art. 879 num. 1)"
                            : exempt.signum() > 0 ? "Exempt account: monthly exempt amount partly used (E.T. art. 879 num. 1)"
                            : "Exempt account: monthly exempt amount exhausted";
                }
                BigDecimal base = f.amount().subtract(exempt);
                out.add(new Line("FINANCIAL_TRANSACTION_TAX", name, rate, f.amount(), exempt,
                        base.multiply(rate).setScale(scale, RoundingMode.HALF_UP), reason));
            }
        }
        if (cfg.path("fee_vat").isObject() && f.fee() != null && f.fee().signum() > 0) {
            JsonNode v = cfg.get("fee_vat");
            BigDecimal rate = dec(v, "rate", "0.19");
            out.add(new Line("VAT", v.path("name").asString("IVA"), rate, f.fee(), BigDecimal.ZERO,
                    f.fee().multiply(rate).setScale(scale, RoundingMode.HALF_UP), null));
        }
        return out;
    }

    public static BigDecimal total(List<Line> lines) {
        return lines.stream().map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static boolean contains(JsonNode list, String value, List<String> dflt) {
        if (list == null || !list.isArray() || list.isEmpty()) {
            return dflt == null || dflt.contains(value);
        }
        for (JsonNode n : list) {
            if (n.asString("").toUpperCase(Locale.ROOT).equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static BigDecimal dec(JsonNode n, String field, String dflt) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return dflt == null ? null : new BigDecimal(dflt);
        }
        return new BigDecimal(v.asString());
    }
}
