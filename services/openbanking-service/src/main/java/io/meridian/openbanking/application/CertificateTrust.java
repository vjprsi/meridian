package io.meridian.openbanking.application;

import io.meridian.common.error.ApiException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * SEC-27: a TPP's eIDAS certificate must chain to a configured QTSP trust anchor
 * ({@code meridian.openbanking.trust-anchors}: PEM text or file paths) under PKIX path validation, optionally with
 * revocation checking ({@code meridian.openbanking.revocation-check}, OCSP / CRL from the certificates). Outside dev
 * mode an unanchored certificate is refused ({@code meridian.openbanking.require-trusted-certificates}, default true
 * except in dev mode). The certificate PEM may carry the leaf followed by intermediates.
 */
@Component
public class CertificateTrust {
    private final Set<TrustAnchor> anchors = new HashSet<>();
    private final boolean required;
    private final boolean revocation;

    public CertificateTrust(@Value("${meridian.openbanking.trust-anchors:}") List<String> sources,
                            @Value("${meridian.openbanking.require-trusted-certificates:}") String required,
                            @Value("${meridian.openbanking.revocation-check:false}") boolean revocation,
                            Environment env) {
        for (String s : sources) {
            if (s != null && !s.isBlank()) {
                parse(s.contains("-----BEGIN") ? s : read(s.trim())).forEach(c -> anchors.add(new TrustAnchor(c, null)));
            }
        }
        this.required = required == null || required.isBlank() ? !io.meridian.web.security.RequiredSecrets.devMode(env)
                : Boolean.parseBoolean(required.trim());
        this.revocation = revocation;
    }

    public void verify(String pemChain, Instant at) {
        if (!required) {
            return;
        }
        if (anchors.isEmpty()) {
            throw ApiException.unprocessable("certificate_untrusted",
                    "No QTSP trust anchors are configured; eIDAS certificates cannot be validated");
        }
        try {
            List<X509Certificate> chain = parse(pemChain);
            CertPath path = CertificateFactory.getInstance("X.509").generateCertPath(chain);
            PKIXParameters params = new PKIXParameters(anchors);
            params.setDate(Date.from(at));
            params.setRevocationEnabled(revocation);
            CertPathValidator.getInstance("PKIX").validate(path, params);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw ApiException.unprocessable("certificate_untrusted",
                    "The certificate does not chain to a trusted QTSP (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static String read(String path) {
        try {
            return Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read trust anchor " + path, e);
        }
    }

    static List<X509Certificate> parse(String pem) {
        try {
            Collection<? extends java.security.cert.Certificate> certs = CertificateFactory.getInstance("X.509")
                    .generateCertificates(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
            List<X509Certificate> out = new ArrayList<>();
            certs.forEach(c -> out.add((X509Certificate) c));
            if (out.isEmpty()) {
                throw new IllegalArgumentException("no certificate");
            }
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("invalid certificate", e);
        }
    }
}
