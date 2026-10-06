package io.meridian.openbanking.domain;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * An X.509 certificate presented by a TPP, with the eIDAS PSD2 attributes (ETSI TS 119 495):
 * the organisation identifier in the subject (OID 2.5.4.97, e.g. {@code PSDGB-FCA-123456}) and the PSD2
 * QCStatement (0.4.0.19495.2 inside extension 1.3.6.1.5.5.7.1.3) carrying the PSP roles and the competent
 * authority. Pure: parsing and checks only, no I/O.
 */
public record Psd2Certificate(
        X509Certificate certificate,
        String subjectDn,
        String issuerDn,
        String serialNumber,
        String fingerprintSha256,
        Instant validFrom,
        Instant validTo,
        String organizationIdentifier,
        boolean hasPsd2Statement,
        List<String> psd2Roles,
        String ncaName,
        String ncaId) {

    public static final String QC_STATEMENTS_OID = "1.3.6.1.5.5.7.1.3";
    public static final String PSD2_QC_TYPE_OID = "0.4.0.19495.2";
    public static final String ORGANIZATION_IDENTIFIER_OID = "2.5.4.97";

    /** ETSI role OIDs → role names. */
    public static final Map<String, String> ROLE_OIDS = Map.of(
            "0.4.0.19495.1.1", "PSP_AS",
            "0.4.0.19495.1.2", "PSP_PI",
            "0.4.0.19495.1.3", "PSP_AI",
            "0.4.0.19495.1.4", "PSP_IC");

    /** Business TPP role → PSD2 certificate role that authorises it. */
    public static final Map<String, String> TPP_ROLE_TO_PSD2 = Map.of(
            "AISP", "PSP_AI",
            "PISP", "PSP_PI",
            "CBPII", "PSP_IC");

    /** Invalid certificate: {@code code} is a stable API error code. */
    public static final class InvalidCertificateException extends RuntimeException {
        private final String code;

        public InvalidCertificateException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** Parses a PEM (or bare base64 DER) certificate. */
    public static Psd2Certificate parse(String pem) {
        X509Certificate cert = decode(pem);
        String fingerprint;
        try {
            fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
        } catch (NoSuchAlgorithmException | java.security.cert.CertificateEncodingException e) {
            throw new IllegalStateException(e);
        }
        String orgId;
        try {
            orgId = organizationIdentifier(cert.getSubjectX500Principal().getEncoded());
        } catch (Der.MalformedException e) {
            throw new InvalidCertificateException("malformed_certificate", "Subject name is not valid DER: " + e.getMessage());
        }
        boolean hasPsd2 = false;
        List<String> roles = List.of();
        String ncaName = null;
        String ncaId = null;
        byte[] ext = cert.getExtensionValue(QC_STATEMENTS_OID);
        if (ext != null) {
            try {
                Der.Element wrapped = Der.parse(ext);
                if (wrapped.tag() != Der.OCTET_STRING) {
                    throw new Der.MalformedException("extension value is not an OCTET STRING");
                }
                for (Der.Element statement : Der.parse(wrapped.value()).children()) {
                    List<Der.Element> parts = statement.children();
                    if (parts.isEmpty() || !PSD2_QC_TYPE_OID.equals(parts.getFirst().oid())) {
                        continue;
                    }
                    if (parts.size() < 2) {
                        throw new Der.MalformedException("PSD2 QCStatement has no statementInfo");
                    }
                    List<Der.Element> info = parts.get(1).children();
                    if (info.size() < 3) {
                        throw new Der.MalformedException("PSD2 QCStatement needs rolesOfPSP, nCAName and nCAId");
                    }
                    Set<String> found = new LinkedHashSet<>();
                    for (Der.Element role : info.get(0).children()) {
                        List<Der.Element> r = role.children();
                        String oid = r.getFirst().oid();
                        String name = ROLE_OIDS.get(oid);
                        if (name == null && r.size() > 1) {
                            name = r.get(1).string().toUpperCase(Locale.ROOT);
                        }
                        if (name != null) {
                            found.add(name);
                        }
                    }
                    hasPsd2 = true;
                    roles = List.copyOf(found);
                    ncaName = info.get(1).string();
                    ncaId = info.get(2).string();
                }
            } catch (Der.MalformedException e) {
                throw new InvalidCertificateException("malformed_qc_statement", "QCStatements extension is malformed: "
                        + e.getMessage());
            }
        }
        return new Psd2Certificate(cert, cert.getSubjectX500Principal().getName(),
                cert.getIssuerX500Principal().getName(), cert.getSerialNumber().toString(16), fingerprint,
                cert.getNotBefore().toInstant(), cert.getNotAfter().toInstant(), orgId, hasPsd2, roles, ncaName, ncaId);
    }

    static X509Certificate decode(String pem) {
        if (pem == null || pem.isBlank()) {
            throw new InvalidCertificateException("malformed_certificate", "certificate_pem is empty");
        }
        String b64 = pem.replaceAll("-----BEGIN CERTIFICATE-----|-----END CERTIFICATE-----", "").replaceAll("\\s+", "");
        byte[] der;
        try {
            der = Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            throw new InvalidCertificateException("malformed_certificate", "certificate_pem is not valid base64 PEM");
        }
        try {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
        } catch (CertificateException | ClassCastException e) {
            throw new InvalidCertificateException("malformed_certificate", "certificate_pem is not an X.509 certificate");
        }
    }

    /** Reads the organizationIdentifier attribute from a DER-encoded X.500 Name, or null. */
    static String organizationIdentifier(byte[] name) {
        for (Der.Element rdn : Der.parse(name).children()) {
            for (Der.Element ava : rdn.children()) {
                List<Der.Element> kv = ava.children();
                if (kv.size() == 2 && ORGANIZATION_IDENTIFIER_OID.equals(kv.get(0).oid())) {
                    return kv.get(1).string();
                }
            }
        }
        return null;
    }

    /** Rejects certificates outside their validity window at {@code now}. */
    public void requireValidAt(Instant now) {
        if (now.isBefore(validFrom)) {
            throw new InvalidCertificateException("certificate_not_yet_valid", "Certificate is valid only from " + validFrom);
        }
        if (now.isAfter(validTo)) {
            throw new InvalidCertificateException("certificate_expired", "Certificate expired at " + validTo);
        }
    }

    /** eIDAS certificate types must carry the PSD2 QCStatement. */
    public void requirePsd2Statement(String certificateType) {
        if (("QWAC".equals(certificateType) || "QSEAL".equals(certificateType)) && !hasPsd2Statement) {
            throw new InvalidCertificateException("psd2_statement_missing",
                    certificateType + " certificates must carry the PSD2 QCStatement (" + PSD2_QC_TYPE_OID + ")");
        }
    }

    /** TPP roles (AISP/PISP/CBPII) not authorised by this certificate's PSD2 roles. */
    public List<String> uncoveredRoles(List<String> tppRoles) {
        List<String> missing = new ArrayList<>();
        for (String r : tppRoles) {
            String needed = TPP_ROLE_TO_PSD2.get(r);
            if (needed == null || !psd2Roles.contains(needed)) {
                missing.add(r);
            }
        }
        return missing;
    }

    public PublicKey publicKey() {
        return certificate.getPublicKey();
    }

    /** Organisation identifier {@code PSD<CC>-<NCA>-<number>} split into country, authority id and number. */
    public static String[] splitOrganisationId(String orgId) {
        if (orgId == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("PSD([A-Z]{2})-([A-Z0-9]{2,8})-(.+)").matcher(orgId);
        return m.matches() ? new String[] {m.group(1), m.group(1) + "-" + m.group(2), m.group(3)} : null;
    }

    public static String toPem(byte[] der) {
        return "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(der) + "\n-----END CERTIFICATE-----\n";
    }
}
