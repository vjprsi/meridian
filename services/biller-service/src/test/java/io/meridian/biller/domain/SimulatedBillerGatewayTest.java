package io.meridian.biller.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.biller.application.BillerGateway;
import io.meridian.biller.infrastructure.SimulatedBillerGateway;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class SimulatedBillerGatewayTest {
    final JsonMapper json = JsonMapper.builder().build();
    final SimulatedBillerGateway gw = new SimulatedBillerGateway();

    private ObjectNode biller(String category) {
        return json.createObjectNode().put("biller_code", "KPLC").put("category", category);
    }

    @Test
    void inquiryIsDeterministicAndUnknownAccountsAreReported() {
        BillerGateway.Inquiry a = gw.inquire(biller("ELECTRICITY"), "12345678");
        BillerGateway.Inquiry b = gw.inquire(biller("ELECTRICITY"), "12345678");
        assertThat(a).isEqualTo(b);
        assertThat(a.found()).isTrue();
        assertThat(a.amountDue()).isBetween(new BigDecimal("100"), new BigDecimal("9099"));
        assertThat(a.amountDue().scale()).isEqualTo(2);
        assertThat(a.customerName()).contains(" ");
        BillerGateway.Inquiry none = gw.inquire(biller("ELECTRICITY"), "12340000");
        assertThat(none.found()).isFalse();
        assertThat(none.responseCode()).isEqualTo("14");
    }

    @Test
    void paymentOutcomesFollowTheReference() {
        ObjectNode ok = json.createObjectNode().put("id", "p1").put("account_reference", "12345678");
        BillerGateway.Result r = gw.pay(biller("ELECTRICITY"), ok, 1);
        assertThat(r.outcome()).isEqualTo(BillerGateway.Outcome.SUCCESS);
        assertThat(r.token()).matches("\\d{20}");
        assertThat(r.providerReference()).startsWith("KPLC-");
        assertThat(gw.pay(biller("WATER"), ok, 1).token()).isNull();

        ObjectNode slow = json.createObjectNode().put("id", "p2").put("account_reference", "1239999");
        assertThat(gw.pay(biller("WATER"), slow, 1).outcome()).isEqualTo(BillerGateway.Outcome.TIMEOUT);
        assertThat(gw.pay(biller("WATER"), slow, 2).outcome()).isEqualTo(BillerGateway.Outcome.SUCCESS);
        ObjectNode closed = json.createObjectNode().put("id", "p3").put("account_reference", "1238888");
        assertThat(gw.pay(biller("WATER"), closed, 3).outcome()).isEqualTo(BillerGateway.Outcome.REJECTED);
    }

    @Test
    void topupOutcomesFollowTheNumber() {
        ObjectNode t = json.createObjectNode().put("id", "t1").put("target_msisdn", "+254711000001");
        assertThat(gw.topup(biller("AIRTIME"), t, 1).outcome()).isEqualTo(BillerGateway.Outcome.SUCCESS);
        t.put("target_msisdn", "+254711000000");
        assertThat(gw.topup(biller("AIRTIME"), t, 1).outcome()).isEqualTo(BillerGateway.Outcome.REJECTED);
        t.put("target_msisdn", "+254711009999");
        assertThat(gw.topup(biller("AIRTIME"), t, 1).outcome()).isEqualTo(BillerGateway.Outcome.TIMEOUT);
        assertThat(gw.topup(biller("AIRTIME"), t, 2).outcome()).isEqualTo(BillerGateway.Outcome.SUCCESS);
    }
}
