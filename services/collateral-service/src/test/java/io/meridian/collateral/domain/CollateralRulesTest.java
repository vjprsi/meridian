package io.meridian.collateral.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CollateralRulesTest {

    private static List<String> fields(List<CollateralRules.Violation> v) {
        return v.stream().map(CollateralRules.Violation::field).toList();
    }

    @Test
    void propertyNeedsTitleNumber() {
        assertThat(fields(CollateralRules.validateAssetDetails("PROPERTY", Map.of(), false)))
                .containsExactly("asset_details.title_number");
        assertThat(CollateralRules.validateAssetDetails("PROPERTY", Map.of("title_number", "TN-1"), false)).isEmpty();
    }

    @Test
    void vehicleNeedsValidVinAndRegistration() {
        assertThat(CollateralRules.validateAssetDetails("VEHICLE",
                Map.of("vin", "1HGCM82633A004352", "registration_number", "AB12CDE"), false)).isEmpty();
        List<CollateralRules.Violation> bad = CollateralRules.validateAssetDetails("VEHICLE",
                Map.of("vin", "1HGCM82633A00435O"), false); // contains O and missing registration
        assertThat(fields(bad)).containsExactly("asset_details.vin", "asset_details.registration_number");
        assertThat(bad.getFirst().code()).isEqualTo("invalid_vin");
        assertThat(fields(CollateralRules.validateAssetDetails("VEHICLE",
                Map.of("vin", "SHORT", "registration_number", "X"), false))).containsExactly("asset_details.vin");
    }

    @Test
    void goldCaratRangeAndPositiveWeight() {
        assertThat(CollateralRules.validateAssetDetails("GOLD", Map.of("carat", 22, "weight_grams", "50.5"), false)).isEmpty();
        assertThat(fields(CollateralRules.validateAssetDetails("GOLD", Map.of("carat", 25, "weight_grams", 0), false)))
                .containsExactly("asset_details.carat", "asset_details.weight_grams");
        assertThat(CollateralRules.validateAssetDetails("GOLD", Map.of("carat", "x", "weight_grams", 1), false)
                .getFirst().code()).isEqualTo("not_a_number");
    }

    @Test
    void securitiesNeedValidIsin() {
        assertThat(CollateralRules.isValidIsin("US0378331005")).isTrue();  // Apple
        assertThat(CollateralRules.isValidIsin("DE0007164600")).isTrue();  // SAP
        assertThat(CollateralRules.isValidIsin("US0378331006")).isFalse();
        assertThat(CollateralRules.isValidIsin("us0378331005")).isFalse();
        assertThat(CollateralRules.validateAssetDetails("SECURITIES", Map.of("isin", "US0378331005", "quantity", 10), false)).isEmpty();
        assertThat(fields(CollateralRules.validateAssetDetails("SECURITIES", Map.of("isin", "US0378331006"), false)))
                .containsExactly("asset_details.isin", "asset_details.quantity");
    }

    @Test
    void depositAndGuaranteeRules() {
        assertThat(fields(CollateralRules.validateAssetDetails("DEPOSIT", null, false))).containsExactly("deposit_account_id");
        assertThat(CollateralRules.validateAssetDetails("DEPOSIT", null, true)).isEmpty();
        assertThat(fields(CollateralRules.validateAssetDetails("GUARANTEE", Map.of("guarantor_name", " "), false)))
                .containsExactly("asset_details.guarantor_name");
        assertThat(CollateralRules.validateAssetDetails("EQUIPMENT", null, false)).isEmpty();
    }

    @Test
    void haircutsLendingValueAndRevaluation() {
        assertThat(CollateralRules.defaultHaircut("PROPERTY")).isEqualByComparingTo("0.30");
        assertThat(CollateralRules.defaultHaircut("DEPOSIT")).isEqualByComparingTo("0");
        assertThat(CollateralRules.lendingValue(new BigDecimal("250000.00"), new BigDecimal("0.30"), "EUR"))
                .isEqualByComparingTo("175000.00");
        assertThat(CollateralRules.lendingValue(new BigDecimal("1001"), new BigDecimal("0.333"), "JPY").scale()).isZero();
        assertThat(CollateralRules.validateHaircut(BigDecimal.ONE)).hasSize(1);
        assertThat(CollateralRules.validateHaircut(new BigDecimal("-0.1"))).hasSize(1);
        assertThat(CollateralRules.revaluationMonths("VEHICLE")).isEqualTo(6);
        assertThat(CollateralRules.revaluationMonths("SECURITIES")).isEqualTo(1);
        assertThat(CollateralRules.revaluationMonths("PROPERTY")).isEqualTo(12);
    }

    @Test
    void lifecycle() {
        assertThat(CollateralRules.canTransition("PROPOSED", "VERIFIED")).isTrue();
        assertThat(CollateralRules.canTransition("PROPOSED", "PLEDGED")).isFalse();
        assertThat(CollateralRules.canTransition("VERIFIED", "PLEDGED")).isTrue();
        assertThat(CollateralRules.canTransition("PLEDGED", "LIQUIDATED")).isTrue();
        assertThat(CollateralRules.canTransition("VERIFIED", "LIQUIDATED")).isFalse();
        assertThat(CollateralRules.canTransition("RELEASED", "PLEDGED")).isFalse();
        assertThat(CollateralRules.linkable("PLEDGED")).isTrue();
        assertThat(CollateralRules.linkable("PROPOSED")).isFalse();
    }
}
