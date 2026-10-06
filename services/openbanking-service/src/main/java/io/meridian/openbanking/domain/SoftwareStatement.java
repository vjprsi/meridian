package io.meridian.openbanking.domain;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Software statement assertion (SSA) used for dynamic client registration: a JWS signed with the key of the TPP's
 * QSEAL / directory signing certificate. Verifies the signature and time claims and extracts the registration data.
 */
public record SoftwareStatement(String softwareId, String clientName, String orgId, String authorisationNumber,
                                String competentAuthority, String country, List<String> roles,
                                List<String> redirectUris, String logoUri, String contactEmail) {

    private static final long CLOCK_SKEW_SECONDS = 60;

    public static final class InvalidStatementException extends RuntimeException {
        private final String code;

        public InvalidStatementException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** Verifies {@code jws} with {@code key} at {@code now} and maps the claims. */
    public static SoftwareStatement verify(String jws, PublicKey key, Instant now) {
        JWSObject obj;
        try {
            obj = JWSObject.parse(jws == null ? "" : jws.trim());
        } catch (ParseException e) {
            throw new InvalidStatementException("malformed_software_statement", "software_statement is not a compact JWS");
        }
        JWSVerifier verifier;
        try {
            if (key instanceof RSAPublicKey rsa) {
                verifier = new RSASSAVerifier(rsa);
            } else if (key instanceof ECPublicKey ec) {
                verifier = new ECDSAVerifier(ec);
            } else {
                throw new InvalidStatementException("unsupported_key", "Certificate key type is not supported for JWS");
            }
            if (!obj.verify(verifier)) {
                throw new InvalidStatementException("invalid_signature",
                        "software_statement signature does not verify with the certificate key");
            }
        } catch (JOSEException e) {
            throw new InvalidStatementException("invalid_signature", "software_statement cannot be verified: " + e.getMessage());
        }
        Map<String, Object> c = obj.getPayload().toJSONObject();
        if (c == null) {
            throw new InvalidStatementException("malformed_software_statement", "software_statement payload is not JSON");
        }
        Long exp = number(c.get("exp"));
        if (exp != null && Instant.ofEpochSecond(exp).isBefore(now.minusSeconds(CLOCK_SKEW_SECONDS))) {
            throw new InvalidStatementException("software_statement_expired", "software_statement has expired");
        }
        Long iat = number(c.get("iat"));
        if (iat != null && Instant.ofEpochSecond(iat).isAfter(now.plusSeconds(CLOCK_SKEW_SECONDS))) {
            throw new InvalidStatementException("software_statement_not_yet_valid", "software_statement iat is in the future");
        }
        String softwareId = str(c.get("software_id"));
        if (softwareId == null || softwareId.isBlank()) {
            throw new InvalidStatementException("software_id_required", "software_statement must contain software_id");
        }
        String name = first(str(c.get("org_name")), str(c.get("software_client_name")));
        if (name == null || name.isBlank()) {
            throw new InvalidStatementException("org_name_required", "software_statement must contain org_name or software_client_name");
        }
        String orgId = str(c.get("org_id"));
        String[] split = Psd2Certificate.splitOrganisationId(orgId);
        String authNumber = first(str(c.get("authorisation_number")), split == null ? null : split[2]);
        String nca = first(str(c.get("competent_authority")), split == null ? null : split[1]);
        String country = first(str(c.get("org_jurisdiction")), split == null ? null : split[0]);
        if (authNumber == null || nca == null || country == null) {
            throw new InvalidStatementException("organisation_id_required",
                    "software_statement must identify the regulated entity (org_id PSD<CC>-<NCA>-<number>)");
        }
        List<String> roles = new ArrayList<>(new LinkedHashSet<>(normaliseRoles(list(c.get("software_roles")))));
        if (roles.isEmpty()) {
            throw new InvalidStatementException("software_roles_required", "software_statement must list software_roles");
        }
        return new SoftwareStatement(softwareId, name, orgId, authNumber, nca.toUpperCase(Locale.ROOT),
                country.toUpperCase(Locale.ROOT), roles, list(c.get("software_redirect_uris")),
                str(c.get("software_logo_uri")), str(c.get("contact_email")));
    }

    /** Maps directory role spellings (AISP, PSP_AI, AIS...) to AISP / PISP / CBPII. */
    static List<String> normaliseRoles(List<String> raw) {
        List<String> out = new ArrayList<>();
        for (String r : raw) {
            String u = r.toUpperCase(Locale.ROOT);
            switch (u) {
                case "AISP", "PSP_AI", "AIS" -> out.add("AISP");
                case "PISP", "PSP_PI", "PIS" -> out.add("PISP");
                case "CBPII", "PSP_IC", "PIISP", "CBPIIS" -> out.add("CBPII");
                default -> throw new InvalidStatementException("invalid_software_role", "Unknown software role " + r);
            }
        }
        return out;
    }

    private static Long number(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        return null;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static String first(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static List<String> list(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            for (Object x : l) {
                if (x != null) {
                    out.add(x.toString());
                }
            }
        } else if (o instanceof String s && !s.isBlank()) {
            out.add(s);
        }
        return out;
    }
}
