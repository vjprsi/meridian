package io.meridian.web.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class OutboundUrlPolicyTest {

    static OutboundUrlPolicy strict(Map<String, String> dns) {
        return new OutboundUrlPolicy(OutboundUrlPolicy.Settings.strict(), false, host -> {
            String ip = dns.get(host);
            if (ip == null) {
                throw new UnknownHostException(host);
            }
            String[] all = ip.split(",");
            InetAddress[] out = new InetAddress[all.length];
            for (int i = 0; i < all.length; i++) {
                out[i] = InetAddress.getByName(all[i]);
            }
            return out;
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "127.8.9.10", "0.0.0.0", "10.1.2.3", "172.16.0.1", "172.31.255.255",
            "192.168.1.1", "100.64.0.1", "100.100.100.200", "169.254.169.254", "169.254.0.1", "192.0.0.10",
            "198.18.0.1", "224.0.0.1", "240.0.0.1", "255.255.255.255", "::1", "::", "fd00::1", "fc00::1",
            "fd00:ec2::254", "fe80::1", "fec0::1", "ff02::1", "::ffff:127.0.0.1", "::ffff:169.254.169.254",
            "::ffff:10.0.0.1", "2002:7f00:1::1", "2002:a9fe:a9fe::1", "64:ff9b::a9fe:a9fe", "2001:0:4136:e378::1",
            "2001:db8::1"})
    void internalAndReservedAddressesAreNotPublic(String ip) throws Exception {
        assertThat(OutboundUrlPolicy.isPublicAddress(InetAddress.getByName(ip))).as(ip).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "1.1.1.1", "93.184.216.34", "172.32.0.1", "100.128.0.1", "2606:4700:4700::1111",
            "2a00:1450:4001:80b::200e"})
    void globalAddressesArePublic(String ip) throws Exception {
        assertThat(OutboundUrlPolicy.isPublicAddress(InetAddress.getByName(ip))).as(ip).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "api.localhost", "printer.local", "metadata.google.internal",
            "ledger-service.meridian.svc", "x.svc.cluster.local", "ledger-service", "127.0.0.1", "2130706433",
            "127.1", "0177.0.0.1", "0x7f.0.0.1", "[::1]", "[fd00::1]", "169.254.169.254", ""})
    void hostLiteralsThatNameInternalTargetsAreRefused(String host) {
        assertThat(OutboundUrlPolicy.isPublicHostLiteral(host)).as(host).isFalse();
    }

    @Test
    void publicNamesAndLiteralsPassTheSyntacticCheck() {
        assertThat(OutboundUrlPolicy.isPublicHostLiteral("hooks.example.com")).isTrue();
        assertThat(OutboundUrlPolicy.isPublicHostLiteral("8.8.8.8")).isTrue();
        assertThat(OutboundUrlPolicy.isPublicHostLiteral("[2606:4700:4700::1111]")).isTrue();
    }

    @Test
    void onlyHttpsWithoutCredentialsIsAccepted() {
        OutboundUrlPolicy p = strict(Map.of("hooks.partner.com", "93.184.216.34"));
        assertThat(p.check("https://hooks.partner.com/x").getHost()).isEqualTo("hooks.partner.com");
        assertThatThrownBy(() -> p.check("http://hooks.partner.com/x"))
                .isInstanceOf(OutboundUrlPolicy.RejectedException.class).hasMessageContaining("https");
        assertThatThrownBy(() -> p.check("https://user:pw@hooks.partner.com/x"))
                .isInstanceOf(OutboundUrlPolicy.RejectedException.class).hasMessageContaining("credentials");
        assertThatThrownBy(() -> p.check("file:///etc/passwd")).isInstanceOf(OutboundUrlPolicy.RejectedException.class);
        assertThatThrownBy(() -> p.check("gopher://hooks.partner.com")).isInstanceOf(OutboundUrlPolicy.RejectedException.class);
        assertThatThrownBy(() -> p.check("https://169.254.169.254/latest/meta-data/"))
                .isInstanceOf(OutboundUrlPolicy.RejectedException.class)
                .extracting(e -> ((OutboundUrlPolicy.RejectedException) e).code()).isEqualTo("destination_not_allowed");
    }

    @Test
    void everyResolvedAddressMustBePublic() {
        OutboundUrlPolicy p = strict(Map.of("rebind.attacker.com", "93.184.216.34,127.0.0.1",
                "internal.attacker.com", "10.0.0.5", "meta.attacker.com", "169.254.169.254",
                "v6.attacker.com", "fd00::1"));
        for (String h : new String[] {"rebind.attacker.com", "internal.attacker.com", "meta.attacker.com", "v6.attacker.com"}) {
            assertThatThrownBy(() -> p.check("https://" + h + "/hook")).as(h)
                    .isInstanceOf(OutboundUrlPolicy.RejectedException.class)
                    .extracting(e -> ((OutboundUrlPolicy.RejectedException) e).code()).isEqualTo("destination_not_allowed");
        }
        assertThatThrownBy(() -> p.check("https://unknown.attacker.com/"))
                .isInstanceOf(OutboundUrlPolicy.RejectedException.class)
                .extracting(e -> ((OutboundUrlPolicy.RejectedException) e).code()).isEqualTo("unresolvable_host");
    }

    @Test
    void devRelaxationsAreIgnoredOutsideDevMode() {
        MockEnvironment jwt = new MockEnvironment().withProperty("meridian.security-mode", "jwt")
                .withProperty("meridian.outbound.allow-http", "true")
                .withProperty("meridian.outbound.allow-private-networks", "true");
        OutboundUrlPolicy p = OutboundUrlPolicy.fromEnvironment(jwt);
        assertThat(p.settings().allowHttp()).isFalse();
        assertThat(p.settings().allowPrivateNetworks()).isFalse();
        assertThat(p.relaxedForDev(true, true).settings().allowPrivateNetworks()).isFalse();
        assertThatThrownBy(() -> p.check("http://127.0.0.1:8080/")).isInstanceOf(OutboundUrlPolicy.RejectedException.class);

        MockEnvironment dev = new MockEnvironment().withProperty("meridian.security-mode", "dev");
        OutboundUrlPolicy d = OutboundUrlPolicy.fromEnvironment(dev);
        assertThatThrownBy(() -> d.check("https://127.0.0.1/")).isInstanceOf(OutboundUrlPolicy.RejectedException.class);
        assertThat(d.relaxedForDev(true, true).check("http://127.0.0.1:8080/x").getPort()).isEqualTo(8080);
    }

    @Test
    void operatorListedHostsMayUsePrivateRangesButNeverLoopbackOrMetadata() {
        OutboundUrlPolicy base = strict(Map.of("onprem.bank.example", "10.20.30.40", "evil.bank.example", "169.254.169.254"));
        OutboundUrlPolicy p = base.with(new OutboundUrlPolicy.Settings(false, false,
                Set.of("onprem.bank.example", "evil.bank.example"), null, null, null, 0));
        assertThat(p.check("https://onprem.bank.example/x").getHost()).isEqualTo("onprem.bank.example");
        assertThatThrownBy(() -> p.check("https://evil.bank.example/x")).isInstanceOf(OutboundUrlPolicy.RejectedException.class);
    }
}
