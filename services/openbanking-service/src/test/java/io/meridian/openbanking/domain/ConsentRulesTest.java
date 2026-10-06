package io.meridian.openbanking.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConsentRulesTest {

    @Test
    void aisPermissionsMustBeKnownAndTransactionsCombined() {
        assertThat(ConsentRules.permissionErrors("AIS", List.of("ReadAccountsDetail", "ReadBalances"))).isEmpty();
        assertThat(ConsentRules.permissionErrors("AIS", List.of("ReadTransactionsDetail", "ReadTransactionsCredits"))).isEmpty();
        assertThat(ConsentRules.permissionErrors("AIS", List.of("ReadTransactionsDetail"))).hasSize(1);
        assertThat(ConsentRules.permissionErrors("AIS", List.of("ReadTransactionsDebits"))).hasSize(1);
        assertThat(ConsentRules.permissionErrors("AIS", List.of())).isNotEmpty();
        assertThat(ConsentRules.permissionErrors("AIS", List.of("CreatePayment"))).isNotEmpty();
    }

    @Test
    void otherTypesUseTheirOwnPermissionSets() {
        assertThat(ConsentRules.permissionErrors("PIS", List.of())).isEmpty();
        assertThat(ConsentRules.permissionErrors("PIS", List.of("ReadBalances"))).isNotEmpty();
        assertThat(ConsentRules.permissionErrors("CBPII", List.of("ReadFundsConfirmation"))).isEmpty();
        assertThat(ConsentRules.permissionErrors("FDX_DATA", List.of())).isNotEmpty();
        assertThat(ConsentRules.REQUIRED_ROLE).containsEntry("PIS", "PISP").containsEntry("CBPII", "CBPII");
    }

    @Test
    void pisInitiationIsCompleteAndMatchedExactly() {
        Map<String, Object> init = Map.of("instructed_amount", "100.00", "currency", "GBP",
                "creditor_name", "Bob Smith", "creditor_account", "GB82 WEST 1234 5698 7654 32");
        assertThat(ConsentRules.initiationErrors(init)).isEmpty();
        assertThat(ConsentRules.initiationErrors(Map.of("currency", "gbp"))).hasSize(4);
        assertThat(ConsentRules.initiationMismatches(init, new BigDecimal("100"), "GBP", "bob smith",
                "GB82WEST12345698765432")).isEmpty();
        assertThat(ConsentRules.initiationMismatches(init, new BigDecimal("100.01"), "EUR", "Bob", "GB00"))
                .containsExactly("amount", "currency", "creditor_name", "creditor_account");
    }

    @Test
    void mapsCorePaymentStatusAndRecognisesIbans() {
        assertThat(ConsentRules.initiationStatus("PENDING_APPROVAL")).isEqualTo("PENDING");
        assertThat(ConsentRules.initiationStatus("SUBMITTED")).isEqualTo("ACCEPTED");
        assertThat(ConsentRules.initiationStatus("SETTLED")).isEqualTo("EXECUTED");
        assertThat(ConsentRules.initiationStatus("RETURNED")).isEqualTo("REJECTED");
        assertThat(ConsentRules.initiationStatus("CANCELLED")).isEqualTo("CANCELLED");
        assertThat(ConsentRules.isIban("GB82 WEST 1234 5698 7654 32")).isTrue();
        assertThat(ConsentRules.isIban("GB82WEST12345698765431")).isFalse();
        assertThat(ConsentRules.isIban("12345678")).isFalse();
    }
}
