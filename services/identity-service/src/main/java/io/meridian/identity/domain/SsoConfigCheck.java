package io.meridian.identity.domain;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline validation of an SSO provider configuration (the "test login" stub): no network calls are made; it checks
 * everything a real login would need before redirecting a user to the identity provider.
 */
public final class SsoConfigCheck {

    public record Check(String name, boolean passed, String detail) {
        public Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("passed", passed);
            m.put("detail", detail);
            return m;
        }
    }

    private SsoConfigCheck() {}

    public static List<Check> run(String protocol, String issuerUrl, String metadataUrl, String clientId,
                                  boolean hasClientSecret, String signingCertificate, Instant now) {
        List<Check> checks = new ArrayList<>();
        checks.add(httpsUrl("issuer_url", issuerUrl, true));
        if (metadataUrl != null && !metadataUrl.isBlank()) {
            checks.add(httpsUrl("metadata_url", metadataUrl, true));
        }
        if ("OIDC".equals(protocol)) {
            boolean ok = clientId != null && !clientId.isBlank();
            checks.add(new Check("client_id", ok, ok ? "client id configured" : "OIDC requires a client_id"));
            checks.add(new Check("client_credentials", hasClientSecret || ok,
                    hasClientSecret ? "client secret configured" : "no client secret: PKCE / private_key_jwt assumed"));
        } else if ("SAML2".equals(protocol)) {
            checks.add(certificate(signingCertificate, now));
            boolean ok = metadataUrl != null && !metadataUrl.isBlank();
            checks.add(new Check("metadata", ok || signingCertificate != null,
                    ok ? "metadata URL configured" : "metadata URL missing; relying on the configured certificate"));
        } else {
            checks.add(new Check("protocol", false, "unsupported protocol " + protocol));
        }
        return checks;
    }

    static Check httpsUrl(String name, String url, boolean required) {
        if (url == null || url.isBlank()) {
            return new Check(name, !required, required ? name + " is required" : "not set");
        }
        try {
            URI u = URI.create(url);
            if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null) {
                return new Check(name, false, name + " must be an absolute https URL");
            }
            return new Check(name, true, "https://" + u.getHost());
        } catch (IllegalArgumentException e) {
            return new Check(name, false, name + " is not a valid URL");
        }
    }

    static Check certificate(String pem, Instant now) {
        if (pem == null || pem.isBlank()) {
            return new Check("signing_certificate", false, "SAML 2.0 requires the IdP signing certificate (PEM)");
        }
        try {
            X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(pem.trim().getBytes(StandardCharsets.US_ASCII)));
            cert.checkValidity(Date.from(now));
            return new Check("signing_certificate", true, "valid until " + cert.getNotAfter().toInstant());
        } catch (java.security.cert.CertificateExpiredException e) {
            return new Check("signing_certificate", false, "certificate expired");
        } catch (java.security.cert.CertificateNotYetValidException e) {
            return new Check("signing_certificate", false, "certificate not yet valid");
        } catch (Exception e) {
            return new Check("signing_certificate", false, "not a parseable X.509 PEM certificate");
        }
    }
}
