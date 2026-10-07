package io.meridian.identity.application;

import io.meridian.common.error.ApiException;
import io.meridian.events.OutboxWriter;
import io.meridian.identity.domain.Base32;
import io.meridian.identity.domain.Secrets;
import io.meridian.identity.domain.SeedCipher;
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
    private final OutboxWriter outbox;
    private final JsonMapper json;
    private final io.meridian.identity.domain.SeedCipher seeds;
    private final org.springframework.transaction.support.TransactionTemplate tx;
    private final int maxAttempts;
    private final java.time.Duration lockout;
    private final io.micrometer.core.instrument.MeterRegistry meters;

    public MfaService(IdentityStore store, CrudEngine crud, UserService users, SessionService sessions,
                      OutboxWriter outbox, JsonMapper json,
                      org.springframework.transaction.support.TransactionTemplate tx,
                      @org.springframework.beans.factory.annotation.Value("${meridian.identity.mfa-seed-key}") String seedKey,
                      @org.springframework.beans.factory.annotation.Value("${meridian.identity.mfa-max-attempts:5}") int maxAttempts,
                      @org.springframework.beans.factory.annotation.Value("${meridian.identity.mfa-lockout:PT15M}") java.time.Duration lockout,
                      io.micrometer.core.instrument.MeterRegistry meters) {
        this.meters = meters;
        this.seeds = new io.meridian.identity.domain.SeedCipher(seedKey);
        this.tx = tx;
        this.maxAttempts = maxAttempts;
        this.lockout = lockout;
        this.store = store;
        this.crud = crud;
        this.users = users;
        this.sessions = sessions;
        this.outbox = outbox;
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
        v.remove("totp_seed_enc"); // never client-supplied
        if ("TOTP".equals(type)) {
            String secret = Base32.encode(Secrets.randomBytes(20));
            // SEC-25: encrypted at rest, bound to the factor id
            String factorId = io.meridian.common.id.Ids.newId().toString();
            v.put("id", factorId);
            v.put("totp_seed_enc", seeds.encrypt(secret, factorId));
            secrets = Map.of("totp_secret", secret,
                    "otpauth_uri", Totp.otpauthUri(ISSUER, user.get("username").asString(), secret));
        }
        String row = crud.insert("mfa_factor", v);
        return new Enrolment(store.publicView(row), secrets);
    }

    /** Outcome of a verification attempt; failures are returned so the attempt counter commits (SEC-25). */
    private record Attempt(String row, ApiException failure) {}

    public String verify(UUID userId, UUID factorId, String code, JsonNode assertion) {
        String outcome = "error";
        try {
            Attempt a = tx.execute(s -> attempt(userId, factorId, code, assertion));
            if (a.failure() != null) {
                outcome = a.failure().code();
                throw a.failure();
            }
            outcome = "verified";
            return a.row();
        } catch (ApiException e) {
            outcome = e.code();
            throw e;
        } finally {
            // GAP-10: MFA verification outcomes (invalid_code / factor_locked spikes = guessing or a broken factor)
            meters.counter("meridian.identity.mfa.verifications", "outcome", outcome).increment();
        }
    }

    private Attempt attempt(UUID userId, UUID factorId, String code, JsonNode assertion) {
        JsonNode f = lockFactor(userId, factorId);
        if (f.hasNonNull("locked_until") && java.time.OffsetDateTime.parse(f.get("locked_until").asString())
                .isAfter(java.time.OffsetDateTime.now())) {
            throw new ApiException(429, "factor_locked", "Too many failed attempts; try again later");
        }
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
                String stored = f.get("totp_seed_enc").asString();
                String seed = seeds.decrypt(stored, factorId.toString());
                OptionalLong step = Totp.verify(Base32.decode(seed), code.trim(), Instant.now(), Totp.DEFAULT_WINDOW);
                long last = f.hasNonNull("last_used_step") ? f.get("last_used_step").asLong() : -1;
                if (step.isEmpty() || step.getAsLong() <= last) {
                    return new Attempt(null, recordFailure(f, factorId));
                }
                store.jdbc().sql("""
                        update mfa_factor set last_used_step = ?, failed_attempts = 0, locked_until = null,
                          totp_seed_enc = ? where id = ?""")
                        .params((int) step.getAsLong(), SeedCipher.isEncrypted(stored) ? stored
                                : seeds.encrypt(seed, factorId.toString()), factorId).update();
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
        outbox.publish("platform.mfa_factor.verified", "mfa_factor", factorId, store.publicView(row));
        users.activateIfInvited(userId);
        return new Attempt(row, null);
    }

    /** Counts a failed verification (committed with the attempt); locks the factor after the limit. */
    private ApiException recordFailure(JsonNode f, UUID factorId) {
        int failed = f.path("failed_attempts").asInt(0) + 1;
        if (failed >= maxAttempts) {
            store.jdbc().sql("update mfa_factor set failed_attempts = ?, locked_until = now() + make_interval(secs => ?) where id = ?")
                    .params(failed, (double) lockout.toSeconds(), factorId).update();
            return new ApiException(429, "factor_locked", "Too many failed attempts; try again later");
        }
        store.jdbc().sql("update mfa_factor set failed_attempts = ? where id = ?").params(failed, factorId).update();
        return ApiException.unprocessable("invalid_code", "The code is not valid");
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
        if (disabled > 0) {
            outbox.publish("platform.app_user.mfa_reset", "app_user", userId,
                    store.publicView(store.find("app_user", userId).orElseThrow()));
        }
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
