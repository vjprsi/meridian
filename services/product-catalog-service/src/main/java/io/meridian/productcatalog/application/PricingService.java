package io.meridian.productcatalog.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.productcatalog.domain.eligibility.EligibilityEvaluator;
import io.meridian.productcatalog.domain.fees.FeeCalculator;
import io.meridian.productcatalog.domain.rates.RateCalculator;
import io.meridian.productcatalog.domain.simulation.FinanceMath;
import io.meridian.productcatalog.infrastructure.CatalogStore;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** Read-only pricing: effective rates, fee dry runs, eligibility checks and customer quotes. */
@Service
public class PricingService {
    private static final Logger log = LoggerFactory.getLogger(PricingService.class);
    private static final MathContext MC = MathContext.DECIMAL128;
    static final Set<String> LENDING = Set.of("INSTALMENT_LOAN", "CREDIT_LINE", "BNPL", "OVERDRAFT", "MORTGAGE",
            "NANO_LOAN", "MERCHANT_FINANCING", "CREDIT_CARD", "CORPORATE_CARD");
    static final List<String> LENDING_KINDS = List.of("DEBIT_INTEREST", "PURCHASE_APR", "OVERDRAFT_INTEREST");
    static final List<String> DEPOSIT_KINDS = List.of("CREDIT_INTEREST", "BONUS_INTEREST");
    static final Set<String> UPFRONT = Set.of("ACCOUNT_OPENING", "LOAN_ORIGINATION", "CARD_ISSUANCE");
    static final Set<String> RECURRING = Set.of("MONTHLY_MAINTENANCE", "ANNUAL");
    static final int QUOTE_VALIDITY_DAYS = 7;

    private final CatalogStore store;
    private final PricingInputs inputs;
    private final ServiceClient services;

    public PricingService(CatalogStore store, PricingInputs inputs, ServiceClient services) {
        this.store = store;
        this.inputs = inputs;
        this.services = services;
    }

    // ------------------------------------------------------------------ effective rate

    public Map<String, Object> effectiveRate(UUID configId, BigDecimal balance, LocalDate asOf) {
        JsonNode cfg = store.require("interest_rate_config", configId);
        LocalDate date = asOf == null ? LocalDate.now() : asOf;
        RateCalculator.Config c = inputs.rateConfig(cfg);
        BigDecimal index = c.isFloating() ? inputs.indexRate(cfg, date).orElse(null) : null;
        RateCalculator.Result r = RateCalculator.compute(c, balance, date, index);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("interest_rate_config_id", configId.toString());
        out.put("rate_kind", cfg.get("rate_kind").asString());
        out.put("rate_type", c.rateType());
        out.put("balance", balance);
        out.put("as_of", date.toString());
        out.put("currency", cfg.get("currency").asString().trim());
        out.put("effective_rate", r.effectiveRate());
        out.put("base_rate", r.baseRate());
        if (r.indexRate() != null) {
            out.put("index_rate", r.indexRate());
        }
        if (r.spread() != null) {
            out.put("spread", r.spread());
        }
        out.put("floor_applied", r.floorApplied());
        out.put("cap_applied", r.capApplied());
        out.put("below_minimum_balance", r.belowMinimumBalance());
        if (r.appliedTier() != null) {
            out.put("applied_tier", r.appliedTier());
        }
        List<Object> bands = new ArrayList<>();
        for (RateCalculator.Band b : r.bands()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tier_no", b.tierNo());
            m.put("from_amount", b.from());
            if (b.to() != null) {
                m.put("to_amount", b.to());
            }
            m.put("portion", b.portion());
            m.put("rate", b.rate());
            m.put("annual_interest", b.annualInterest());
            bands.add(m);
        }
        if (!bands.isEmpty()) {
            out.put("bands", bands);
        }
        out.put("day_count", c.dayCount().name());
        out.put("annual_interest", r.annualInterest());
        out.put("daily_interest", r.dailyInterest());
        return out;
    }

    // ------------------------------------------------------------------ fees

    public Map<String, Object> calculateFee(UUID feeId, BigDecimal baseAmount, Integer occurrenceNo, UUID customerId,
                                            Map<String, Object> context) {
        JsonNode f = store.require("fee_definition", feeId);
        LocalDate asOf = context.get("as_of") != null ? LocalDate.parse(context.get("as_of").toString()) : LocalDate.now();
        FeeCalculator.Context ctx = new FeeCalculator.Context(asOf, occurrenceNo, dec(context, "balance"),
                dec(context, "average_balance"), dec(context, "salary_credit"), integer(context, "transaction_count"),
                str(context, "segment"), bool(context, "bundle_member"), str(context, "promotion_code"),
                bool(context, "manual_waiver"), dec(context, "charged_in_period"));
        FeeCalculator.Result r = FeeCalculator.calculate(inputs.fee(f), baseAmount, ctx, inputs.tax(f, asOf).orElse(null));
        Map<String, Object> out = feeResult(f, r);
        out.put("as_of", asOf.toString());
        if (occurrenceNo != null) {
            out.put("occurrence_no", occurrenceNo);
        }
        if (customerId != null) {
            out.put("customer_id", customerId.toString());
        }
        return out;
    }

    private static Map<String, Object> feeResult(JsonNode f, FeeCalculator.Result r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fee_definition_id", f.get("id").asString());
        out.put("code", f.get("code").asString());
        out.put("trigger", f.get("trigger").asString());
        out.put("calculation_method", f.get("calculation_method").asString());
        out.put("currency", r.currency());
        out.put("base_amount", r.baseAmount());
        out.put("gross_amount", r.grossAmount());
        out.put("min_applied", r.minApplied());
        out.put("max_applied", r.maxApplied());
        if (r.tierNo() != null) {
            out.put("tier_no", r.tierNo());
        }
        out.put("free_allowance_applied", r.freeAllowanceApplied());
        if (r.waiverApplied() != null) {
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("fee_waiver_rule_id", r.waiverApplied().id() == null ? null : r.waiverApplied().id().toString());
            w.put("condition", r.waiverApplied().condition());
            w.put("discount_percentage", r.waiverApplied().discountPercentage());
            out.put("waiver", w);
        }
        out.put("discount_amount", r.discountAmount());
        out.put("cap_applied", r.capApplied());
        out.put("net_amount", r.netAmount());
        if (r.tax() != null) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("tax_rule_id", r.tax().taxRuleId().toString());
            t.put("code", r.tax().code());
            t.put("rate", r.tax().rate());
            t.put("amount", r.tax().amount());
            out.put("tax", t);
        }
        out.put("total_amount", r.totalAmount());
        out.put("notes", r.notes());
        return out;
    }

    // ------------------------------------------------------------------ eligibility

    public Map<String, Object> eligibility(UUID productId, UUID customerId, Map<String, Object> profile, String channel) {
        JsonNode product = store.require("product", productId);
        JsonNode version = publishedVersion(product);
        LocalDate asOf = profile.get("as_of") != null ? LocalDate.parse(profile.get("as_of").toString()) : LocalDate.now();
        List<EligibilityEvaluator.Rule> rules = new ArrayList<>();
        for (JsonNode r : store.children("eligibility_rule", "product_version_id", ProductService.id(version), "id")) {
            UUID dt = r.hasNonNull("decision_table_id") ? UUID.fromString(r.get("decision_table_id").asString()) : null;
            rules.add(new EligibilityEvaluator.Rule(ProductService.id(r), r.get("rule_type").asString(),
                    r.get("operator").asString(), EligibilityEvaluator.normalizeValue(CatalogStore.toJava(r.get("value"))),
                    PricingInputs.text(r, "expression"), r.get("is_hard").asBoolean(), PricingInputs.text(r, "rejection_code"),
                    PricingInputs.text(r, "rejection_message"), dt, dt == null ? null : decisionTable(dt, customerId, profile)));
        }
        EligibilityEvaluator.Evaluation e;
        try {
            e = EligibilityEvaluator.evaluate(rules, profile, asOf);
        } catch (IllegalArgumentException | java.time.DateTimeException ex) {
            throw ApiException.unprocessable("invalid_eligibility_input", ex.getMessage());
        }
        List<String> reasons = new ArrayList<>(e.reasons());
        EligibilityEvaluator.Outcome outcome = e.outcome();
        if (!"ACTIVE".equals(product.get("status").asString()) || !product.get("is_sellable").asBoolean()) {
            reasons.addFirst("PRODUCT_NOT_SELLABLE");
            outcome = EligibilityEvaluator.Outcome.INELIGIBLE;
        }
        if (channel != null && !channelAvailable(product, channel)) {
            reasons.add("CHANNEL_NOT_AVAILABLE");
            outcome = EligibilityEvaluator.Outcome.INELIGIBLE;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("product_id", productId.toString());
        out.put("product_version_id", version.get("id").asString());
        if (customerId != null) {
            out.put("customer_id", customerId.toString());
        }
        out.put("as_of", asOf.toString());
        out.put("outcome", outcome.name());
        out.put("eligible", outcome == EligibilityEvaluator.Outcome.ELIGIBLE);
        out.put("reasons", reasons);
        List<Object> details = new ArrayList<>();
        for (EligibilityEvaluator.RuleEvaluation re : e.rules()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("eligibility_rule_id", re.rule().id().toString());
            m.put("rule_type", re.rule().ruleType());
            m.put("operator", re.rule().operator());
            m.put("expected", re.rule().value());
            m.put("actual", re.actual());
            m.put("result", re.result().name());
            m.put("is_hard", re.rule().hard());
            if (re.result() != EligibilityEvaluator.RuleResult.PASS) {
                m.put("rejection_code", re.rule().rejectionCode());
                m.put("rejection_message", re.rule().rejectionMessage());
            }
            if (re.reason() != null) {
                m.put("detail", re.reason());
            }
            details.add(m);
        }
        out.put("rules", details);
        return out;
    }

    /** Evaluates a delegated decision table in pricing-rules-service; expects a boolean output named eligible. */
    private Boolean decisionTable(UUID tableId, UUID customerId, Map<String, Object> profile) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("inputs", CatalogStore.toMap(store.toNumericNode(profile)));
            body.put("subject_type", "ELIGIBILITY_CHECK");
            if (customerId != null) {
                body.put("subject_id", customerId.toString());
            }
            JsonNode result = services.post("pricing-rules-service", "/v1/decision-tables/" + tableId + "/evaluate", body,
                    "elig-" + Ids.newId());
            JsonNode eligible = result == null ? null : result.path("outputs").get("eligible");
            return eligible == null || eligible.isNull() ? null : Boolean.valueOf(eligible.asString());
        } catch (RuntimeException e) {
            log.warn("decision table {} could not be evaluated: {}", tableId, e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------ quote

    public record QuoteRequest(UUID customerId, BigDecimal amount, Integer termMonths, String channel,
                               Map<String, Object> applicant) {}

    public Map<String, Object> quote(UUID productId, QuoteRequest q) {
        JsonNode product = store.require("product", productId);
        JsonNode version = publishedVersion(product);
        if (!"ACTIVE".equals(product.get("status").asString()) || !product.get("is_sellable").asBoolean()) {
            throw ApiException.conflict("product_not_sellable", "Product " + product.get("code").asString() + " is "
                    + product.get("status").asString() + " and not open for new sales");
        }
        if (q.channel() != null && !channelAvailable(product, q.channel())) {
            throw ApiException.unprocessable("channel_not_available", "The product is not sold through " + q.channel());
        }
        if (q.amount() != null && q.amount().signum() < 0) {
            throw ApiException.unprocessable("invalid_amount", "amount cannot be negative");
        }
        if (q.termMonths() != null && (q.termMonths() < 1 || q.termMonths() > 600)) {
            throw ApiException.unprocessable("invalid_term", "term_months must be 1..600");
        }
        boolean lending = LENDING.contains(product.get("family").asString());
        if (lending && (q.amount() == null || q.amount().signum() <= 0 || q.termMonths() == null)) {
            throw ApiException.unprocessable("amount_and_term_required", "Lending quotes need a positive amount and term_months");
        }
        String currency = product.get("currency").asString().trim();
        int minor = FeeCalculator.minorUnits(currency);
        Map<String, Object> applicant = q.applicant() == null ? Map.of() : q.applicant();
        LocalDate today = LocalDate.now();
        OffsetDateTime now = OffsetDateTime.now();
        BigDecimal amount = q.amount() == null ? BigDecimal.ZERO : q.amount();
        int term = q.termMonths() == null ? 12 : q.termMonths();
        UUID versionId = ProductService.id(version);

        // 1. base rate from the primary interest configuration effective today
        List<Object> components = new ArrayList<>();
        JsonNode cfg = primaryRateConfig(versionId, lending ? LENDING_KINDS : DEPOSIT_KINDS, today);
        BigDecimal rate = BigDecimal.ZERO;
        RateCalculator.Config c = null;
        String rateKind = null;
        if (cfg != null) {
            c = inputs.rateConfig(cfg);
            rateKind = cfg.get("rate_kind").asString();
            BigDecimal index = c.isFloating() ? inputs.indexRate(cfg, today).orElse(null) : null;
            rate = RateCalculator.compute(c, amount, today, index).effectiveRate();
            components.add(component("BASE_RATE", rate, rateKind + " " + c.rateType()));
        }
        // 2. promotion (best for the customer)
        BigDecimal feeDiscount = BigDecimal.ZERO;
        Map<String, Object> promotion = null;
        JsonNode bestPromo = null;
        BigDecimal bestRate = null;
        for (JsonNode p : store.children("promotional_rate", "product_id", productId, "id")) {
            if (!promotionApplies(p, now, rateKind, applicant)) {
                continue;
            }
            BigDecimal promoRate = rate;
            if (p.hasNonNull("override_rate")) {
                promoRate = PricingInputs.dec(p, "override_rate", null);
            } else if (p.hasNonNull("bonus_rate")) {
                promoRate = rate.add(PricingInputs.dec(p, "bonus_rate", null));
            }
            BigDecimal cap = PricingInputs.dec(p, "max_balance", null);
            if (cap != null && amount.compareTo(cap) > 0 && amount.signum() > 0) {
                promoRate = cap.multiply(promoRate, MC).add(amount.subtract(cap).multiply(rate, MC), MC).divide(amount, MC);
            }
            boolean better = bestPromo == null || (lending ? promoRate.compareTo(bestRate) < 0 : promoRate.compareTo(bestRate) > 0)
                    || promoRate.compareTo(bestRate) == 0 && PricingInputs.dec(p, "fee_discount_percentage", BigDecimal.ZERO)
                    .compareTo(PricingInputs.dec(bestPromo, "fee_discount_percentage", BigDecimal.ZERO)) > 0;
            if (better) {
                bestPromo = p;
                bestRate = promoRate;
            }
        }
        if (bestPromo != null) {
            BigDecimal delta = bestRate.subtract(rate);
            rate = bestRate;
            feeDiscount = feeDiscount.max(PricingInputs.dec(bestPromo, "fee_discount_percentage", BigDecimal.ZERO));
            promotion = new LinkedHashMap<>();
            promotion.put("promotional_rate_id", bestPromo.get("id").asString());
            promotion.put("code", bestPromo.get("code").asString());
            promotion.put("rate_delta", delta.setScale(RateCalculator.RATE_SCALE, RoundingMode.HALF_EVEN));
            if (bestPromo.hasNonNull("applies_for_days")) {
                promotion.put("applies_for_days", bestPromo.get("applies_for_days").asInt());
            }
            components.add(component("PROMOTION", delta, bestPromo.get("code").asString()));
        }
        // 3. relationship pricing (lowest priority value wins)
        Map<String, Object> relationship = null;
        JsonNode rel = relationshipRule(productId, rateKind, applicant, today);
        if (rel != null) {
            BigDecimal adj = PricingInputs.dec(rel, "rate_adjustment", BigDecimal.ZERO);
            rate = rate.add(adj);
            feeDiscount = feeDiscount.max(PricingInputs.dec(rel, "fee_discount_percentage", BigDecimal.ZERO));
            relationship = new LinkedHashMap<>();
            relationship.put("relationship_pricing_id", rel.get("id").asString());
            relationship.put("name", rel.get("name").asString());
            relationship.put("rate_adjustment", adj);
            components.add(component("RELATIONSHIP", adj, rel.get("name").asString()));
        }
        if (c != null && c.floorRate() != null && rate.compareTo(c.floorRate()) < 0) {
            rate = c.floorRate();
            components.add(component("FLOOR", c.floorRate(), "floor applied"));
        }
        if (c != null && c.capRate() != null && rate.compareTo(c.capRate()) > 0) {
            rate = c.capRate();
            components.add(component("CAP", c.capRate(), "cap applied"));
        }
        rate = rate.setScale(RateCalculator.RATE_SCALE, RoundingMode.HALF_EVEN);

        // 4. fees (upfront and recurring), with the best fee discount on top of waivers
        List<Object> fees = new ArrayList<>();
        BigDecimal upfront = BigDecimal.ZERO;
        BigDecimal monthly = BigDecimal.ZERO;
        BigDecimal annual = BigDecimal.ZERO;
        for (JsonNode f : store.children("fee_definition", "product_version_id", versionId, "code")) {
            String trigger = f.get("trigger").asString();
            if (!UPFRONT.contains(trigger) && !RECURRING.contains(trigger)) {
                continue;
            }
            FeeCalculator.Context ctx = new FeeCalculator.Context(today, 1, amount, amount, dec(applicant, "salary_credit"),
                    integer(applicant, "transaction_count"), str(applicant, "segment"), bool(applicant, "bundle_member"),
                    str(applicant, "promotion_code"), null, null);
            FeeCalculator.Result r;
            try {
                r = FeeCalculator.calculate(inputs.fee(f), amount, ctx, inputs.tax(f, today).orElse(null));
            } catch (ApiException e) {
                if ("fee_not_effective".equals(e.code())) {
                    continue;
                }
                throw e;
            }
            Map<String, Object> m = feeResult(f, r);
            BigDecimal total = r.totalAmount();
            if (feeDiscount.signum() > 0) {
                BigDecimal disc = total.multiply(feeDiscount.min(BigDecimal.ONE), MC).setScale(minor, RoundingMode.HALF_EVEN);
                total = total.subtract(disc);
                m.put("pricing_discount", disc);
            }
            m.put("quoted_amount", total);
            fees.add(m);
            if (UPFRONT.contains(trigger)) {
                upfront = upfront.add(total);
            } else if ("ANNUAL".equals(trigger)) {
                annual = annual.add(total);
            } else {
                monthly = monthly.add(total);
            }
        }
        BigDecimal recurringTotal = monthly.multiply(BigDecimal.valueOf(term)).add(annual.multiply(BigDecimal.valueOf((term + 11) / 12)));
        BigDecimal totalFees = upfront.add(recurringTotal).setScale(minor, RoundingMode.HALF_EVEN);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("product_id", productId.toString());
        out.put("product_version_id", versionId.toString());
        if (q.customerId() != null) {
            out.put("customer_id", q.customerId().toString());
        }
        out.put("currency", currency);
        out.put("amount", amount.setScale(minor, RoundingMode.HALF_EVEN));
        out.put("term_months", term);
        if (q.channel() != null) {
            out.put("channel", q.channel());
        }
        out.put("pricing_type", lending ? "LENDING" : "DEPOSIT");
        if (rateKind != null) {
            out.put("rate_kind", rateKind);
        }
        out.put("interest_rate", rate);
        out.put("rate_components", components);
        if (promotion != null) {
            out.put("promotion", promotion);
        }
        if (relationship != null) {
            out.put("relationship_pricing", relationship);
        }
        out.put("fees", fees);
        out.put("total_fees", totalFees);
        if (lending) {
            BigDecimal monthlyRate = rate.divide(BigDecimal.valueOf(12), MC);
            BigDecimal payment = FinanceMath.annuityPayment(amount, monthlyRate, term).setScale(minor, RoundingMode.HALF_EVEN);
            BigDecimal totalInterest = payment.multiply(BigDecimal.valueOf(term)).subtract(amount).max(BigDecimal.ZERO);
            List<BigDecimal> outflows = new ArrayList<>();
            for (int m = 1; m <= term; m++) {
                BigDecimal fee = monthly.add((m - 1) % 12 == 0 ? annual : BigDecimal.ZERO);
                outflows.add(payment.add(fee));
            }
            BigDecimal apr = FinanceMath.monthlyApr(amount.subtract(upfront), outflows);
            out.put("monthly_payment", payment);
            out.put("total_interest", totalInterest.setScale(minor, RoundingMode.HALF_EVEN));
            out.put("total_cost", totalInterest.add(totalFees).setScale(minor, RoundingMode.HALF_EVEN));
            if (apr != null) {
                out.put("apr", apr);
            }
        } else {
            BigDecimal growth = BigDecimal.ONE.add(rate.divide(BigDecimal.valueOf(12), MC), MC).pow(term, MC);
            BigDecimal interest = amount.multiply(growth.subtract(BigDecimal.ONE, MC), MC).setScale(minor, RoundingMode.HALF_EVEN);
            out.put("total_interest", interest);
            out.put("total_cost", interest);
            out.put("aer", FinanceMath.aer(rate, 12).setScale(RateCalculator.RATE_SCALE, RoundingMode.HALF_EVEN));
            out.put("net_return", interest.subtract(totalFees));
        }
        out.put("quoted_at", now.toString());
        out.put("valid_until", now.plusDays(QUOTE_VALIDITY_DAYS).toString());
        return out;
    }

    // ------------------------------------------------------------------ helpers

    JsonNode publishedVersion(JsonNode product) {
        if (!product.hasNonNull("current_version_id")) {
            throw ApiException.conflict("no_published_version", "Product " + product.get("code").asString()
                    + " has no published version");
        }
        return store.require("product_version", UUID.fromString(product.get("current_version_id").asString()));
    }

    private JsonNode primaryRateConfig(UUID versionId, List<String> kinds, LocalDate today) {
        List<JsonNode> configs = store.children("interest_rate_config", "product_version_id", versionId, "rate_kind, effective_from");
        for (String kind : kinds) {
            Optional<JsonNode> match = configs.stream()
                    .filter(c -> kind.equals(c.get("rate_kind").asString()))
                    .filter(c -> {
                        LocalDate from = PricingInputs.date(c, "effective_from");
                        LocalDate to = PricingInputs.date(c, "effective_to");
                        return (from == null || !today.isBefore(from)) && (to == null || !today.isAfter(to));
                    })
                    .reduce((a, b) -> b);
            if (match.isPresent()) {
                return match.get();
            }
        }
        return null;
    }

    private static boolean promotionApplies(JsonNode p, OffsetDateTime now, String rateKind, Map<String, Object> applicant) {
        String status = p.get("status").asString();
        if (!"SCHEDULED".equals(status) && !"ACTIVE".equals(status)) {
            return false;
        }
        if (OffsetDateTime.parse(p.get("starts_at").asString()).isAfter(now)
                || !OffsetDateTime.parse(p.get("ends_at").asString()).isAfter(now)) {
            return false;
        }
        if (p.hasNonNull("rate_kind") && !p.get("rate_kind").asString().equals(rateKind)
                && (p.hasNonNull("bonus_rate") || p.hasNonNull("override_rate"))) {
            return false;
        }
        return !p.path("new_customers_only").asBoolean(false) || Boolean.TRUE.equals(bool(applicant, "new_customer"));
    }

    private JsonNode relationshipRule(UUID productId, String rateKind, Map<String, Object> applicant, LocalDate today) {
        BigDecimal balance = dec(applicant, "relationship_balance");
        Integer held = integer(applicant, "products_held");
        String segment = str(applicant, "segment");
        for (JsonNode r : store.children("relationship_pricing", "product_id", productId, "priority, id")) {
            LocalDate from = PricingInputs.date(r, "valid_from");
            LocalDate to = PricingInputs.date(r, "valid_to");
            if (from != null && today.isBefore(from) || to != null && today.isAfter(to)) {
                continue;
            }
            if (r.hasNonNull("segment") && !r.get("segment").asString().equalsIgnoreCase(String.valueOf(segment))) {
                continue;
            }
            if (r.hasNonNull("min_relationship_balance")
                    && (balance == null || balance.compareTo(PricingInputs.dec(r, "min_relationship_balance", null)) < 0)) {
                continue;
            }
            if (r.hasNonNull("min_products") && (held == null || held < r.get("min_products").asInt())) {
                continue;
            }
            if (r.hasNonNull("rate_kind") && rateKind != null && !r.get("rate_kind").asString().equals(rateKind)) {
                continue;
            }
            return r;
        }
        return null;
    }

    /** Channel availability: product.available_channels (if set) and product_availability rows (if any). */
    boolean channelAvailable(JsonNode product, String channel) {
        JsonNode chans = product.get("available_channels");
        if (chans != null && chans.isObject() && !chans.isEmpty()) {
            JsonNode list = chans.has("channels") ? chans.get("channels") : chans.get("values");
            boolean listed = false;
            if (list != null && list.isArray()) {
                for (JsonNode c : list) {
                    listed |= channel.equals(c.asString());
                }
            } else {
                listed = chans.path(channel).asBoolean(false);
            }
            if (!listed) {
                return false;
            }
        }
        List<JsonNode> rows = store.children("product_availability", "product_id", ProductService.id(product), "id");
        List<JsonNode> forChannel = rows.stream().filter(r -> channel.equals(r.get("channel").asString())).toList();
        if (forChannel.isEmpty()) {
            return rows.isEmpty();
        }
        OffsetDateTime now = OffsetDateTime.now();
        return forChannel.stream().anyMatch(r -> r.get("is_available").asBoolean()
                && (!r.hasNonNull("available_from") || !OffsetDateTime.parse(r.get("available_from").asString()).isAfter(now))
                && (!r.hasNonNull("available_to") || OffsetDateTime.parse(r.get("available_to").asString()).isAfter(now)));
    }

    private static Map<String, Object> component(String type, BigDecimal value, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("value", value.setScale(RateCalculator.RATE_SCALE, RoundingMode.HALF_EVEN));
        m.put("detail", detail);
        return m;
    }

    static BigDecimal dec(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) {
            return null;
        }
        try {
            return v instanceof BigDecimal b ? b : new BigDecimal(v.toString().trim());
        } catch (NumberFormatException e) {
            throw ApiException.unprocessable("invalid_value", k + " must be a decimal number");
        }
    }

    static Integer integer(Map<String, Object> m, String k) {
        BigDecimal b = dec(m, k);
        return b == null ? null : b.intValue();
    }

    static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }

    static Boolean bool(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : Boolean.valueOf(v.toString());
    }
}
