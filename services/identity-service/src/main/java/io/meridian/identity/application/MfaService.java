package io.meridian.identity.application;

import io.meridian.common.error.ApiException;
import io.meridian.identity.domain.Base32;
import io.meridian.identity.domain.Secrets;
import io.meridian.identity.domain.Totp;
import io.meridian.identity.infrastructure.IdentityStore;
import io.meridian.web.crud.CrudEngine;
import java.time.Instant;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** MFA enrolment and verification (TOTP per RFC 6238; WebAuthn assertion check is a stub). */
@Service
public class MfaService {
    public static final String ISSUER = "Meridian";

    private final IdentityStore store;
    private final CrudEngine crud;
    private final UserService users;
    private final SessionService sessions;
    private final JsonMapper json;

    public MfaService(IdentityStore store, CrudEngine crud, UserService users, SessionService sessions, JsonMapper json) {
        this.store = store;
        this.crud = crud;
        this.users = users;
        this.sessions = sessions;
        this.json = json;
    }

    public record Enrolment(ObjectNode factor, Map<String, String> secrets) {}

    @Transactional
    public Enrolment enrol(UUID userId, Object body) {
        ObjectNode v = crud.toColumns("mfa_factor", body);
        if (v.hasNonNull("user_id") && !userId.toString().equals(v.get("user_id").asString())) {
            throw ApiException.unprocessable("user_mismatch", "user_id in the body must match the path");
        }
        JsonNode user = store.lock("app_user", userId);
        if ("DEACTIVATED".equals(user.get("status").asString())) {
            throw ApiException.conflict("user_deactivated", "Cannot enrol MFA for a deactivated user");
        }
        String type = v.get("factor_type").asString();
        if (v.hasNonNull("status") && !"PENDING_VERIFICATION".equals(v.get("status").asString())) {
            throw ApiException.unprocessable("invalid_initial_status", "A new factor starts PENDING_VERIFICATION");
        }
        switch (type) {
            case "SMS_OTP", "EMAIL_OTP" -> {
                if (!v.hasNonNull("destination")) {
                    throw ApiException.unprocessable("destination_required", type + " requires a destination");
                }
            }
            case "WEBAUTHN" -> {
                if (!v.hasNonNull("credential_id")) {
                    throw ApiException.unprocessable("credential_required", "WEBAUTHN requires credential_id");
                }
            }
            default -> { }
        }
        v.put("user_id", userId.toString());
        v.put("status", "PENDING_VERIFICATION");
        v.put("is_primary", false);
        Map<String, String> secrets = Map.of();
        if ("TOTP".equals(type)) {
            String secret = Base32.encode(Secrets.randomBytes(20));
            v.put("totp_secret", secret);
            secrets = Map.of("totp_secret", secret,
                    "otpauth_uri", Totp.otpauthUri(ISSUER, user.get("username").asString(), secret));
        }
        String row = crud.insert("mfa_factor", v);
        return new Enrolment(store.publicView(row), secrets);
    }

    @Transactional
    public String verify(UUID userId, UUID factorId, String code, JsonNode assertion) {
        JsonNode f = lockFactor(userId, factorId);
        String status = f.get("status").asString();
        if ("DISABLED".equals(status)) {
            throw ApiException.conflict("factor_disabled", "Factor is disabled");
        }
        if ("ACTIVE".equals(status)) {
            throw ApiException.conflict("factor_already_verified", "Factor is already verified");
        }
        switch (f.get("factor_type").asString()) {
            case "TOTP" -> {
                if (code == null || code.isBlank()) {
                    throw ApiException.unprocessable("code_required", "A TOTP code is required");
                }
                OptionalLong step = Totp.verify(Base32.decode(f.get("totp_secret").asString()), code.trim(),
                        Instant.now(), Totp.DEFAULT_WINDOW);
                if (step.isEmpty()) {
                    throw ApiException.unprocessable("invalid_code", "The code is not valid");
                }
            }
            case "WEBAUTHN" -> {
                boolean ok = assertion != null && assertion.isObject()
                        && f.get("credential_id").asString().equals(assertion.path("credential_id").asString(null))
                        && !assertion.path("signature").asString("").isBlank();
                if (!ok) {
                    throw ApiException.unprocessable("invalid_assertion",
                            "assertion must carry the enrolled credential_id and a signature");
                }
            }
            default -> throw ApiException.unprocessable("verification_not_supported",
                    f.get("factor_type").asString() + " verification requires OTP delivery, which is not available");
        }
        Integer primaries = store.jdbc().sql("""
                select count(*) from mfa_factor where user_id = ? and status = 'ACTIVE' and is_primary""")
                .param(userId).query(Integer.class).single();
        String row = store.updateReturning("""
                update mfa_factor t set status = 'ACTIVE', last_used_at = now(), is_primary = ?
                where t.id = ? returning row_to_json(t)::text""", primaries == 0, factorId);
        store.jdbc().sql("update app_user set mfa_enrolled = true where id = ?").param(userId).update();
        users.activateIfInvited(userId);
        return row;
    }

    @Transactional
    public void delete(UUID userId, UUID factorId) {
        lockFactor(userId, factorId);
        store.jdbc().sql("delete from mfa_factor where id = ?").param(factorId).update();
        refreshEnrolled(userId);
    }

    /** Executes an approved MFA reset: disables every factor and revokes the user's sessions. */
    @Transactional
    public JsonNode resetAll(UUID userId, String reason) {
        store.lock("app_user", userId);
        int disabled = store.jdbc().sql("""
                update mfa_factor set status = 'DISABLED', is_primary = false
                where user_id = ? and status <> 'DISABLED'""").param(userId).update();
        refreshEnrolled(userId);
        int revoked = sessions.revokeActive(userId, "MFA reset: " + (reason == null ? "approved" : reason));
        ObjectNode out = json.createObjectNode();
        out.put("user_id", userId.toString());
        out.put("factors_disabled", disabled);
        out.put("sessions_revoked", revoked);
        return out;
    }

    private void refreshEnrolled(UUID userId) {
        store.jdbc().sql("""
                update app_user set mfa_enrolled = exists (
                  select 1 from mfa_factor where user_id = ? and status = 'ACTIVE') where id = ?""")
                .params(userId, userId).update();
    }

    private JsonNode lockFactor(UUID userId, UUID factorId) {
        JsonNode f = store.lock("mfa_factor", factorId);
        if (!f.get("user_id").asString().equals(userId.toString())) {
            throw ApiException.notFound("mfa factor", factorId);
        }
        return f;
    }
}
