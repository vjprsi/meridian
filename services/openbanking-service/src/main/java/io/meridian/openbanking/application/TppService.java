package io.meridian.openbanking.application;

import static io.meridian.openbanking.infrastructure.Rows.str;
import static io.meridian.openbanking.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.openbanking.domain.ConsentRules;
import io.meridian.openbanking.domain.Psd2Certificate;
import io.meridian.openbanking.domain.SoftwareStatement;
import io.meridian.openbanking.infrastructure.Rows;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * TPP registry: manual onboarding and dynamic client registration with a signed software statement, eIDAS
 * certificate validation (validity, PSD2 roles, competent authority), verification / activation, suspension,
 * reactivation and certificate revocation.
 */
@Service
public class TppService {
    static final String TPP = "tpp";
    static final String CERT = "tpp_certificate";
    private static final Set<String> CERT_TYPES = Set.of("QWAC", "QSEAL", "OBWAC", "OBSEAL", "MTLS_CLIENT");

    private final Rows rows;
    private final Events events;
    private final SecureRandom random = new SecureRandom();

    public TppService(Rows rows, Events events) {
        this.rows = rows;
        this.events = events;
    }

    // ------------------------------------------------------------------ onboarding

    @Transactional
    public ObjectNode create(Object body) {
        ObjectNode v = rows.columns(TPP, body);
        v.set("roles", rows.json().valueToTree(roles(v.get("roles"))));
        validateRedirectUris(v.get("redirect_uris"));
        v.put("competent_authority", str(v, "competent_authority").toUpperCase(Locale.ROOT));
        v.put("client_id", newClientId());
        v.put("status", "PENDING");
        return rows.insert(TPP, v);
    }

    @Transactional
    public void validateUpdate(JsonNode req) {
        if (req.hasNonNull("roles")) {
            roles(req.get("roles"));
        }
        validateRedirectUris(req.get("redirect_uris"));
    }

    /** Dynamic client registration: verify the SSA with the presented certificate, register the TPP (PENDING). */
    @Transactional
    public ObjectNode register(String softwareStatement, String certificatePem) {
        Instant now = rows.now().toInstant();
        Psd2Certificate cert = certificate(certificatePem, now);
        if (!cert.hasPsd2Statement()) {
            throw ApiException.unprocessable("psd2_statement_missing",
                    "The signing certificate must be an eIDAS certificate with the PSD2 QCStatement");
        }
        SoftwareStatement ssa;
        try {
            ssa = SoftwareStatement.verify(softwareStatement, cert.publicKey(), now);
        } catch (SoftwareStatement.InvalidStatementException e) {
            throw ApiException.unprocessable(e.code(), e.getMessage());
        }
        if (cert.organizationIdentifier() != null && ssa.orgId() != null
                && !cert.organizationIdentifier().equalsIgnoreCase(ssa.orgId())) {
            throw ApiException.unprocessable("organisation_mismatch", "software_statement org_id " + ssa.orgId()
                    + " does not match the certificate organisation identifier " + cert.organizationIdentifier());
        }
        if (cert.ncaId() != null && !cert.ncaId().equalsIgnoreCase(ssa.competentAuthority())) {
            throw ApiException.unprocessable("nca_mismatch", "Competent authority " + ssa.competentAuthority()
                    + " does not match the certificate NCA " + cert.ncaId());
        }
        List<String> missing = cert.uncoveredRoles(ssa.roles());
        if (!missing.isEmpty()) {
            throw ApiException.unprocessable("role_not_authorised", "The certificate does not authorise the roles " + missing);
        }
        validateRedirectUris(rows.json().valueToTree(ssa.redirectUris()));
        if (rows.queryOne("select row_to_json(t)::text from tpp t where software_id = ?", ssa.softwareId()).isPresent()) {
            throw ApiException.conflict("software_already_registered", "Software " + ssa.softwareId() + " is already registered");
        }
        requireNewFingerprint(cert.fingerprintSha256());
        ObjectNode v = rows.object();
        v.put("name", trim(ssa.clientName(), 140));
        v.put("trading_name", trim(ssa.clientName(), 140));
        v.put("authorisation_number", ssa.authorisationNumber());
        v.put("competent_authority", ssa.competentAuthority());
        v.put("country", ssa.country());
        v.set("roles", rows.json().valueToTree(ssa.roles()));
        v.put("directory_id", ssa.orgId());
        v.put("software_id", ssa.softwareId());
        v.put("client_id", newClientId());
        v.set("redirect_uris", rows.json().valueToTree(ssa.redirectUris()));
        if (ssa.logoUri() != null) {
            v.put("logo_uri", trim(ssa.logoUri(), 500));
        }
        if (ssa.contactEmail() != null) {
            v.put("contact_email", ssa.contactEmail());
        }
        v.put("status", "PENDING");
        ObjectNode tpp = rows.insert(TPP, v);
        ObjectNode certRow = insertCertificate(uuid(tpp, "id"), "QSEAL", certificatePem, cert);
        events.publish("openbanking.tpp.registered", TPP, tpp);
        events.publish("openbanking.tpp_certificate.added", CERT, certRow);
        return tpp;
    }

    /** Re-verifies a TPP: expires lapsed certificates, then requires one valid certificate covering roles and NCA. */
    @Transactional
    public ObjectNode verify(UUID id) {
        ObjectNode tpp = rows.lock(TPP, id);
        String status = str(tpp, "status");
        if (!"PENDING".equals(status) && !"ACTIVE".equals(status)) {
            throw ApiException.conflict("invalid_transition", "A " + status + " TPP cannot be verified");
        }
        rows.execute("update tpp_certificate set status = 'EXPIRED', last_ocsp_check_at = now() "
                + "where tpp_id = ? and status = 'VALID' and valid_to <= now()", id);
        List<String> tppRoles = roles(tpp.get("roles"));
        List<ObjectNode> certs = rows.query("select row_to_json(t)::text from tpp_certificate t where tpp_id = ? "
                + "and status = 'VALID' and valid_from <= now() and valid_to > now()", id);
        if (certs.isEmpty()) {
            throw ApiException.unprocessable("no_valid_certificate", "The TPP has no valid, unexpired certificate");
        }
        String nca = str(tpp, "competent_authority");
        boolean ok = false;
        List<String> problems = new ArrayList<>();
        for (ObjectNode c : certs) {
            List<String> certRoles = strings(c.get("psd2_roles"));
            List<String> missing = new ArrayList<>();
            for (String r : tppRoles) {
                if (!certRoles.contains(Psd2Certificate.TPP_ROLE_TO_PSD2.get(r))) {
                    missing.add(r);
                }
            }
            String certNca = str(c, "nca_id");
            if (!missing.isEmpty()) {
                problems.add(str(c, "fingerprint_sha256") + ": roles " + missing + " not authorised");
            } else if (certNca == null || !certNca.equalsIgnoreCase(nca)) {
                problems.add(str(c, "fingerprint_sha256") + ": NCA " + certNca + " differs from " + nca);
            } else {
                ok = true;
            }
        }
        if (!ok) {
            throw ApiException.unprocessable("certificate_does_not_cover_tpp",
                    "No certificate authorises the TPP roles for its competent authority: " + String.join("; ", problems));
        }
        rows.execute("update tpp_certificate set last_ocsp_check_at = now() where tpp_id = ? and status = 'VALID'", id);
        ObjectNode v = rows.object();
        v.put("status", "ACTIVE");
        v.putNull("status_reason");
        rows.execute("update tpp set last_verified_at = now() where id = ?", id);
        ObjectNode updated = rows.update(TPP, id, v);
        events.publish("openbanking.tpp.verified", TPP, updated);
        return updated;
    }

    @Transactional
    public ObjectNode suspend(UUID id, String reason) {
        ObjectNode tpp = rows.lock(TPP, id);
        String status = str(tpp, "status");
        if (!"ACTIVE".equals(status) && !"PENDING".equals(status)) {
            throw ApiException.conflict("invalid_transition", "A " + status + " TPP cannot be suspended");
        }
        ObjectNode v = rows.object();
        v.put("status", "SUSPENDED");
        v.put("status_reason", trim(reason, 255));
        ObjectNode updated = rows.update(TPP, id, v);
        events.publish("openbanking.tpp.suspended", TPP, updated);
        return updated;
    }

    @Transactional
    public ObjectNode reactivate(UUID id, String reason) {
        ObjectNode tpp = rows.lock(TPP, id);
        if (!"SUSPENDED".equals(str(tpp, "status"))) {
            throw ApiException.conflict("invalid_transition", "Only a SUSPENDED TPP can be reactivated");
        }
        long valid = rows.scalar("select count(*) from tpp_certificate where tpp_id = ? and status = 'VALID' "
                + "and valid_from <= now() and valid_to > now()", Long.class, id).orElse(0L);
        if (valid == 0) {
            throw ApiException.unprocessable("no_valid_certificate", "The TPP has no valid certificate; it cannot be reactivated");
        }
        ObjectNode v = rows.object();
        v.put("status", "ACTIVE");
        if (reason != null) {
            v.put("status_reason", trim(reason, 255));
        } else {
            v.putNull("status_reason");
        }
        ObjectNode updated = rows.update(TPP, id, v);
        events.publish("openbanking.tpp.reactivated", TPP, updated);
        return updated;
    }

    // ------------------------------------------------------------------ certificates

    @Transactional
    public ObjectNode addCertificate(Object body) {
        ObjectNode v = rows.columns(CERT, body);
        UUID tppId = uuid(v, "tpp_id");
        String type = str(v, "certificate_type");
        if (!CERT_TYPES.contains(type)) {
            throw ApiException.unprocessable("invalid_certificate_type", "certificate_type must be one of " + CERT_TYPES);
        }
        ObjectNode tpp = rows.find(TPP, tppId).orElseThrow(() -> ApiException.unprocessable("tpp_not_found",
                "TPP " + tppId + " does not exist"));
        if ("REVOKED".equals(str(tpp, "status"))) {
            throw ApiException.conflict("tpp_revoked", "Certificates cannot be added to a revoked TPP");
        }
        String pem = str(v, "certificate_pem");
        Psd2Certificate cert = certificate(pem, rows.now().toInstant());
        try {
            cert.requirePsd2Statement(type);
        } catch (Psd2Certificate.InvalidCertificateException e) {
            throw ApiException.unprocessable(e.code(), e.getMessage());
        }
        if (cert.hasPsd2Statement()) {
            List<String> missing = cert.uncoveredRoles(roles(tpp.get("roles")));
            if (!missing.isEmpty()) {
                throw ApiException.unprocessable("roles_not_covered", "The certificate does not authorise the TPP roles " + missing);
            }
            if (cert.ncaId() == null || !cert.ncaId().equalsIgnoreCase(str(tpp, "competent_authority"))) {
                throw ApiException.unprocessable("nca_mismatch", "Certificate NCA " + cert.ncaId()
                        + " does not match the TPP competent authority " + str(tpp, "competent_authority"));
            }
        }
        requireNewFingerprint(cert.fingerprintSha256());
        ObjectNode row = insertCertificate(tppId, type, pem, cert);
        events.publish("openbanking.tpp_certificate.added", CERT, row);
        return row;
    }

    @Transactional
    public ObjectNode revokeCertificate(UUID id, String reason) {
        ObjectNode cert = rows.lock(CERT, id);
        if ("REVOKED".equals(str(cert, "status"))) {
            throw ApiException.conflict("invalid_transition", "The certificate is already revoked");
        }
        ObjectNode v = rows.object();
        v.put("status", "REVOKED");
        v.put("revocation_reason", trim(reason, 255));
        rows.execute("update tpp_certificate set last_ocsp_check_at = now() where id = ?", id);
        ObjectNode updated = rows.update(CERT, id, v);
        events.publish("openbanking.tpp_certificate.revoked", CERT, updated);
        return updated;
    }

    // ------------------------------------------------------------------ helpers

    private ObjectNode insertCertificate(UUID tppId, String type, String pem, Psd2Certificate cert) {
        ObjectNode c = rows.object();
        c.put("tpp_id", tppId.toString());
        c.put("certificate_type", type);
        c.put("certificate_pem", pem.trim());
        c.put("subject_dn", trim(cert.subjectDn(), 500));
        c.put("issuer_dn", trim(cert.issuerDn(), 500));
        c.put("serial_number", trim(cert.serialNumber(), 128));
        c.put("fingerprint_sha256", cert.fingerprintSha256());
        if (cert.organizationIdentifier() != null) {
            c.put("organization_identifier", trim(cert.organizationIdentifier(), 128));
        }
        c.set("psd2_roles", rows.json().valueToTree(cert.psd2Roles()));
        if (cert.ncaName() != null) {
            c.put("nca_name", trim(cert.ncaName(), 256));
        }
        if (cert.ncaId() != null) {
            c.put("nca_id", trim(cert.ncaId(), 16));
        }
        c.put("valid_from", cert.validFrom().toString());
        c.put("valid_to", cert.validTo().toString());
        c.put("status", "VALID");
        ObjectNode row = rows.insert(CERT, c);
        rows.execute("update tpp_certificate set last_ocsp_check_at = now() where id = ?", uuid(row, "id"));
        return rows.get(CERT, uuid(row, "id"));
    }

    private Psd2Certificate certificate(String pem, Instant now) {
        try {
            Psd2Certificate cert = Psd2Certificate.parse(pem);
            cert.requireValidAt(now);
            return cert;
        } catch (Psd2Certificate.InvalidCertificateException e) {
            throw ApiException.unprocessable(e.code(), e.getMessage());
        }
    }

    private void requireNewFingerprint(String fingerprint) {
        if (rows.queryOne("select row_to_json(t)::text from tpp_certificate t where fingerprint_sha256 = ?", fingerprint)
                .isPresent()) {
            throw ApiException.conflict("duplicate_certificate", "This certificate is already registered");
        }
    }

    /** Validates and normalises a TPP role list (non-empty subset of AISP / PISP / CBPII). */
    static List<String> roles(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw ApiException.unprocessable("invalid_roles", "roles must be a non-empty list of AISP, PISP, CBPII");
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (JsonNode r : node) {
            String role = r.asString("").toUpperCase(Locale.ROOT);
            if (!ConsentRules.TPP_ROLES.contains(role)) {
                throw ApiException.unprocessable("invalid_roles", "Unknown TPP role " + r.asString("") + "; use AISP, PISP, CBPII");
            }
            out.add(role);
        }
        return List.copyOf(out);
    }

    private static void validateRedirectUris(JsonNode uris) {
        if (uris == null || uris.isNull()) {
            return;
        }
        if (!uris.isArray()) {
            throw ApiException.unprocessable("invalid_redirect_uris", "redirect_uris must be a list");
        }
        for (JsonNode u : uris) {
            String s = u.asString("");
            if (!s.startsWith("https://")) {
                throw ApiException.unprocessable("invalid_redirect_uris", "redirect URI " + s + " must use https");
            }
        }
    }

    static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode x : (ArrayNode) arr) {
                out.add(x.asString(""));
            }
        }
        return out;
    }

    private String newClientId() {
        byte[] b = new byte[16];
        random.nextBytes(b);
        return "tpp-" + HexFormat.of().formatHex(b);
    }

    static String trim(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
