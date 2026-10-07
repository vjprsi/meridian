package io.meridian.webhook.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.common.error.ApiException;
import io.meridian.web.outbound.OutboundUrlPolicy;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** SEC-09: endpoint URLs are checked against the shared outbound policy at create / update time. */
class EndpointDestinationTest {
    static final Map<String, String> DNS = Map.of("hooks.partner.com", "93.184.216.34",
            "internal.partner.com", "10.0.0.7", "k8s.partner.com", "fd00::10");

    final EndpointService endpoints = new EndpointService(null, null, null, null, null, true,
            new OutboundUrlPolicy(OutboundUrlPolicy.Settings.strict(), false, host -> {
                String ip = DNS.get(host);
                if (ip == null) {
                    throw new UnknownHostException(host);
                }
                return new InetAddress[] {InetAddress.getByName(ip)};
            }));

    static void refused(Runnable r, String code) {
        assertThatThrownBy(r::run).isInstanceOf(ApiException.class)
                .satisfies(e -> org.assertj.core.api.Assertions.assertThat(((ApiException) e).code()).isEqualTo(code));
    }

    @Test
    void internalTargetsAreRefusedEvenWithAllowInsecureLocalhostOutsideDevMode() {
        refused(() -> endpoints.checkDestination("https://169.254.169.254/latest/meta-data/"), "url_not_allowed");
        refused(() -> endpoints.checkDestination("https://127.0.0.1/hook"), "url_not_allowed");
        refused(() -> endpoints.checkDestination("https://ledger-service/v1/accounts"), "url_not_allowed");
        refused(() -> endpoints.checkDestination("https://kubernetes.default.svc/api"), "url_not_allowed");
        refused(() -> endpoints.checkDestination("https://internal.partner.com/hook"), "url_not_allowed");
        refused(() -> endpoints.checkDestination("https://k8s.partner.com/hook"), "url_not_allowed");
        refused(() -> endpoints.checkDestination("http://localhost:8080/hook"), "https_required");
    }

    @Test
    void publicOrNotYetResolvableNamesAreAccepted() {
        assertThatCode(() -> endpoints.checkDestination("https://hooks.partner.com/meridian")).doesNotThrowAnyException();
        // DNS may be created later: every delivery re-validates the resolved addresses at connect time
        assertThatCode(() -> endpoints.checkDestination("https://future.partner.com/meridian")).doesNotThrowAnyException();
    }
}
