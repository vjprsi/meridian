package io.meridian.tenant.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A template bank used by provision-from-template: which modules start enabled and the default tenant settings
 * and feature flags seeded for the new tenant.
 *
 * @param settings key -> (value_type, value as JSON text, category)
 */
public record TenantTemplate(String code, String description, List<String> modules,
                             Map<String, Setting> settings, Map<String, Boolean> featureFlags) {

    public record Setting(String valueType, String jsonValue, String category, String description) {}

    private static final Map<String, TenantTemplate> CATALOG = new LinkedHashMap<>();

    static {
        register(new TenantTemplate("RETAIL_BANK", "Full-service retail bank",
                List.of("LEDGER", "DEPOSITS", "PAYMENTS", "CARDS", "LENDING", "RISK", "REPORTING"),
                common(Map.of(
                        "deposits.interest_accrual_basis", new Setting("STRING", "\"ACT/365\"", "deposits", "Day-count basis"),
                        "lending.max_dti_ratio", new Setting("NUMBER", "0.45", "lending", "Maximum debt-to-income ratio"))),
                Map.of("cards.virtual_cards", true, "payments.instant_payments", true, "ai.assistant", false)));
        register(new TenantTemplate("DIGITAL_BANK", "Mobile-first neobank",
                List.of("LEDGER", "DEPOSITS", "PAYMENTS", "CARDS", "RISK", "OPEN_BANKING", "ANALYTICS"),
                common(Map.of(
                        "onboarding.remote_kyc", new Setting("BOOLEAN", "true", "onboarding", "Fully remote onboarding"))),
                Map.of("cards.virtual_cards", true, "payments.instant_payments", true, "savings.pots", true)));
        register(new TenantTemplate("WALLET_PROVIDER", "E-money / mobile wallet provider",
                List.of("LEDGER", "WALLETS", "PAYMENTS", "RISK", "REPORTING"),
                common(Map.of(
                        "wallets.max_balance", new Setting("AMOUNT", "{\"amount\":\"1000.00\",\"currency\":\"${currency}\"}", "wallets",
                                "Maximum wallet balance (tier 1)"))),
                Map.of("wallets.agent_cash_in", true, "payments.instant_payments", false)));
        register(new TenantTemplate("MICROFINANCE", "Microfinance institution",
                List.of("LEDGER", "DEPOSITS", "LENDING", "REPORTING"),
                common(Map.of(
                        "lending.group_lending", new Setting("BOOLEAN", "true", "lending", "Group lending enabled"))),
                Map.of("lending.group_loans", true)));
    }

    private static Map<String, Setting> common(Map<String, Setting> extra) {
        Map<String, Setting> m = new LinkedHashMap<>();
        m.put("security.session_timeout", new Setting("DURATION", "\"PT30M\"", "security", "Console session idle timeout"));
        m.put("security.password_min_length", new Setting("NUMBER", "12", "security", "Minimum password length"));
        m.put("approvals.four_eyes_enabled", new Setting("BOOLEAN", "true", "approvals", "Maker-checker on sensitive operations"));
        m.putAll(extra);
        return m;
    }

    private static void register(TenantTemplate t) {
        CATALOG.put(t.code(), t);
    }

    public static Optional<TenantTemplate> find(String code) {
        return Optional.ofNullable(code == null ? null : CATALOG.get(code.toUpperCase(java.util.Locale.ROOT)));
    }

    public static List<String> codes() {
        return List.copyOf(CATALOG.keySet());
    }
}
