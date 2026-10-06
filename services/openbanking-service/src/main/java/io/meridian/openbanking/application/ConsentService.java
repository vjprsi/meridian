package io.meridian.openbanking.application;

import static io.meridian.openbanking.application.TppService.strings;
import static io.meridian.openbanking.application.TppService.trim;
import static io.meridian.openbanking.infrastructure.Rows.str;
import static io.meridian.openbanking.infrastructure.Rows.ts;
import static io.meridian.openbanking.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.openbanking.domain.ConsentRules;
import io.meridian.openbanking.infrastructure.CoreBanking;
import io.meridian.openbanking.infrastructure.Rows;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Consents and their SCA authorisation: request (TPP role, profile, permission rules, expiry), authorisation
 * session (SCA approach, registered redirect URI, PKCE), account selection with ownership checks, approval /
 * denial, revocation and expiry. All validity windows use the database clock.
 */
@Service
public class ConsentService {
    static final String CONSENT = "ob_consent";
    static final String AUTH = "ob_authorisation";
    static final String PROFILE = "ob_standard_profile";
    private static final Set<String> OPEN_SESSION = Set.of("STARTED", "SCA_REQUIRED", "AUTHENTICATED", "ACCOUNTS_SELECTED");
    private static final Set<String> SINGLE_ACCOUNT = Set.of("PIS", "CBPII", "VRP");

    private final Rows rows;
    private final Events events;
    private final CoreBanking core;
    private final SecureRandom random = new SecureRandom();

    public ConsentService(Rows rows, Events events, CoreBanking core) {
        this.rows = rows;
        this.events = events;
        this.core = core;
    }

    // ------------------------------------------------------------------ profiles

    /** Validates profile fields on create / update (SCA approaches, validity bounds). */
    public void validateProfile(JsonNode req, boolean create) {
        JsonNode sca = req.get("sca_approaches");
        if (create || (sca != null && !sca.isNull())) {
            if (sca == null || !sca.isArray() || sca.isEmpty()) {
                throw ApiException.unprocessable("invalid_sca_approaches", "sca_approaches must list REDIRECT, DECOUPLED and/or EMBEDDED");
            }
            for (JsonNode s : sca) {
                if (!ConsentRules.SCA_APPROACHES.contains(s.asString(""))) {
                    throw ApiException.unprocessable("invalid_sca_approaches", "Unknown SCA approach " + s.asString(""));
                }
            }
        }
        if (req.hasNonNull("consent_max_days") && (req.get("consent_max_days").asInt() < 1 || req.get("consent_max_days").asInt() > 3650)) {
            throw ApiException.unprocessable("invalid_consent_max_days", "consent_max_days must be 1-3650");
        }
        if (req.hasNonNull("authorisation_ttl_minutes") && (req.get("authorisation_ttl_minutes").asInt() < 1
                || req.get("authorisation_ttl_minutes").asInt() > 1440)) {
            throw ApiException.unprocessable("invalid_authorisation_ttl", "authorisation_ttl_minutes must be 1-1440");
        }
        if (req.hasNonNull("base_path") && !req.get("base_path").asString().startsWith("/")) {
            throw ApiException.unprocessable("invalid_base_path", "base_path must start with /");
        }
    }

    @Transactional(readOnly = true)
    public ObjectNode discovery(UUID profileId) {
        ObjectNode p = rows.get(PROFILE, profileId);
        String base = str(p, "base_path").replaceAll("/+$", "");
        ObjectNode d = rows.object();
        d.put("profile_id", profileId.toString());
        d.put("standard", str(p, "standard"));
        d.put("version", str(p, "version"));
        d.put("base_path", base);
        d.put("is_active", p.path("is_active").asBoolean());
        d.set("sca_approaches", p.get("sca_approaches"));
        d.put("consent_max_days", p.path("consent_max_days").asInt());
        d.put("authorisation_ttl_minutes", p.path("authorisation_ttl_minutes").asInt());
        if (str(p, "directory_url") != null) {
            d.put("directory_url", str(p, "directory_url"));
        }
        ObjectNode ep = d.putObject("endpoints");
        boolean berlin = "BERLIN_GROUP".equals(str(p, "standard"));
        ep.put("consents", base + (berlin ? "/v1/consents" : "/account-access-consents"));
        ep.put("accounts", base + (berlin ? "/v1/accounts" : "/accounts"));
        ep.put("payments", base + (berlin ? "/v1/payments/{payment-product}" : "/domestic-payments"));
        ep.put("payment_consents", base + (berlin ? "/v1/payments/{payment-product}" : "/domestic-payment-consents"));
        ep.put("funds_confirmations", base + (berlin ? "/v1/funds-confirmations" : "/funds-confirmations"));
        ep.put("funds_confirmation_consents", base + (berlin ? "/v2/consents/confirmation-of-funds" : "/funds-confirmation-consents"));
        ArrayNode types = d.putArray("consent_types");
        for (String t : List.of("AIS", "PIS", "CBPII", "VRP")) {
            types.add(t);
        }
        d.putArray("signing_algorithms").add("PS256").add("ES256");
        d.putArray("token_endpoint_auth_methods").add("private_key_jwt").add("tls_client_auth");
        return d;
    }

    // ------------------------------------------------------------------ consents

    @Transactional
    public ObjectNode create(Object body) {
        ObjectNode v = rows.columns(CONSENT, body);
        String type = str(v, "consent_type");
        UUID tppId = uuid(v, "tpp_id");
        ObjectNode tpp = rows.find(TppService.TPP, tppId).orElseThrow(() -> ApiException.unprocessable("tpp_not_found",
                "TPP " + tppId + " does not exist"));
        requireActiveTpp(tpp);
        String role = ConsentRules.REQUIRED_ROLE.get(type);
        if (role == null || !strings(tpp.get("roles")).contains(role)) {
            throw ApiException.unprocessable("tpp_role_not_authorised", "The TPP is not authorised as " + role
                    + " required for " + type + " consents");
        }
        UUID profileId = uuid(v, "ob_standard_profile_id");
        ObjectNode profile = rows.find(PROFILE, profileId).orElseThrow(() -> ApiException.unprocessable("profile_not_found",
                "Standard profile " + profileId + " does not exist"));
        if (!profile.path("is_active").asBoolean()) {
            throw ApiException.unprocessable("profile_inactive", "Standard profile " + profileId + " is not active");
        }
        JsonNode perms = v.get("permissions");
        if (perms == null || !perms.isArray()) {
            throw ApiException.unprocessable("invalid_permissions", "permissions must be a list");
        }
        List<String> permissions = new ArrayList<>(new LinkedHashSet<>(strings(perms)));
        List<String> errors = ConsentRules.permissionErrors(type, permissions);
        if (!errors.isEmpty()) {
            throw ApiException.unprocessable("invalid_permissions", String.join("; ", errors));
        }
        v.set("permissions", rows.json().valueToTree(permissions));
        if ("PIS".equals(type)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> initiation = v.hasNonNull("initiation")
                    ? rows.json().treeToValue(v.get("initiation"), Map.class) : null;
            List<String> ie = ConsentRules.initiationErrors(initiation);
            if (!ie.isEmpty()) {
                throw ApiException.unprocessable("invalid_initiation", String.join("; ", ie));
            }
            v.put("recurring", false);
        }
        if ("VRP".equals(type) && !v.hasNonNull("vrp_control_parameters")) {
            throw ApiException.unprocessable("vrp_control_parameters_required", "VRP consents need vrp_control_parameters");
        }
        OffsetDateTime now = rows.now();
        OffsetDateTime expires = ts(v, "expires_at");
        if ("AIS".equals(type) || "FDX_DATA".equals(type)) {
            OffsetDateTime max = now.plusDays(profile.path("consent_max_days").asInt(90));
            if (expires == null) {
                expires = max;
            } else if (expires.isAfter(max)) {
                throw ApiException.unprocessable("consent_too_long", "expires_at exceeds the profile maximum of "
                        + profile.path("consent_max_days").asInt() + " days");
            }
        } else if ("PIS".equals(type) && expires == null) {
            expires = now.plusDays(1);
        }
        if (expires != null && !expires.isAfter(now)) {
            throw ApiException.unprocessable("invalid_expiry", "expires_at must be in the future");
        }
        if (expires != null) {
            v.put("expires_at", expires.toString());
        }
        OffsetDateTime from = ts(v, "transaction_from");
        OffsetDateTime to = ts(v, "transaction_to");
        if (from != null && to != null && from.isAfter(to)) {
            throw ApiException.unprocessable("invalid_transaction_window", "transaction_from must not be after transaction_to");
        }
        if (v.hasNonNull("frequency_per_day") && v.get("frequency_per_day").asInt() < 1) {
            throw ApiException.unprocessable("invalid_frequency", "frequency_per_day must be at least 1");
        }
        v.put("external_consent_id", type.toLowerCase(Locale.ROOT) + "-" + token(12));
        v.put("status", "AWAITING_AUTHORISATION");
        ObjectNode row = rows.insert(CONSENT, v);
        events.publish("openbanking.ob_consent.created", CONSENT, row);
        return row;
    }

    @Transactional
    public ObjectNode authorise(UUID consentId, JsonNode req) {
        ObjectNode consent = rows.lock(CONSENT, consentId);
        requireStatus(consent, "AWAITING_AUTHORISATION", "authorised");
        requireNotExpired(consent);
        ObjectNode profile = rows.get(PROFILE, uuid(consent, "ob_standard_profile_id"));
        ObjectNode tpp = rows.get(TppService.TPP, uuid(consent, "tpp_id"));
        requireActiveTpp(tpp);
        String approach = str(req, "sca_approach") == null ? null : str(req, "sca_approach").toUpperCase(Locale.ROOT);
        if (!strings(profile.get("sca_approaches")).contains(approach)) {
            throw ApiException.unprocessable("sca_approach_not_supported", "SCA approach " + approach
                    + " is not supported by the profile " + strings(profile.get("sca_approaches")));
        }
        if ("REDIRECT".equals(approach)) {
            String uri = str(req, "redirect_uri");
            if (uri == null || !strings(tpp.get("redirect_uris")).contains(uri)) {
                throw ApiException.unprocessable("redirect_uri_not_registered",
                        "redirect_uri must be one of the TPP's registered redirect URIs");
            }
            if (str(req, "code_challenge") == null) {
                throw ApiException.unprocessable("pkce_required", "code_challenge (PKCE S256) is required for REDIRECT");
            }
        }
        ObjectNode v = rows.object();
        v.put("ob_consent_id", consentId.toString());
        v.put("sca_approach", approach);
        for (String f : List.of("redirect_uri", "state", "code_challenge")) {
            if (str(req, f) != null) {
                v.put(f, str(req, f));
            }
        }
        v.put("status", "DECOUPLED".equals(approach) ? "SCA_REQUIRED" : "STARTED");
        v.put("expires_at", rows.now().plusMinutes(profile.path("authorisation_ttl_minutes").asInt(15)).toString());
        return rows.insert(AUTH, v);
    }

    @Transactional
    public ObjectNode selectAccounts(UUID authId, UUID customerId, JsonNode accountIds) {
        ObjectNode auth = rows.lock(AUTH, authId);
        requireOpenSession(auth);
        ObjectNode consent = rows.lock(CONSENT, uuid(auth, "ob_consent_id"));
        requireStatus(consent, "AWAITING_AUTHORISATION", "authorised");
        if (customerId == null) {
            throw ApiException.unprocessable("customer_required", "customer_id is required");
        }
        if (accountIds == null || !accountIds.isArray() || accountIds.isEmpty()) {
            throw ApiException.unprocessable("accounts_required", "account_ids must list at least one account");
        }
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        for (JsonNode a : accountIds) {
            try {
                ids.add(UUID.fromString(a.asString("")));
            } catch (IllegalArgumentException e) {
                throw ApiException.unprocessable("invalid_account_id", "account id " + a.asString("") + " is not a UUID");
            }
        }
        String type = str(consent, "consent_type");
        if (SINGLE_ACCOUNT.contains(type) && ids.size() != 1) {
            throw ApiException.unprocessable("single_account_required", type + " consents apply to exactly one account");
        }
        String pisCurrency = consent.path("initiation").path("currency").asString(null);
        for (UUID id : ids) {
            JsonNode acc;
            try {
                acc = core.depositAccount(id);
            } catch (ApiException e) {
                if (e.status() == 404) {
                    throw ApiException.unprocessable("account_not_found", "Account " + id + " does not exist");
                }
                throw e;
            }
            if (!customerId.toString().equals(acc.path("customer_id").asString(null))) {
                throw ApiException.unprocessable("account_not_owned", "Account " + id + " does not belong to the customer");
            }
            if (!"ACTIVE".equals(acc.path("status").asString(null))) {
                throw ApiException.unprocessable("account_not_active", "Account " + id + " is not active");
            }
            if (pisCurrency != null && !pisCurrency.equals(acc.path("currency").asString(null))) {
                throw ApiException.unprocessable("currency_mismatch", "Account " + id + " is not in " + pisCurrency);
            }
        }
        ObjectNode v = rows.object();
        v.put("customer_id", customerId.toString());
        v.set("selected_account_ids", rows.json().valueToTree(ids.stream().map(UUID::toString).toList()));
        v.put("status", "ACCOUNTS_SELECTED");
        return rows.update(AUTH, authId, v);
    }

    @Transactional
    public ObjectNode approve(UUID authId, String authenticationMethod) {
        ObjectNode auth = rows.lock(AUTH, authId);
        if (!"ACCOUNTS_SELECTED".equals(str(auth, "status"))) {
            throw ApiException.conflict("invalid_transition", "The authorisation is " + str(auth, "status")
                    + "; the customer must select accounts before approving");
        }
        requireOpenSession(auth);
        if (authenticationMethod == null || authenticationMethod.isBlank()) {
            throw ApiException.unprocessable("authentication_method_required", "authentication_method is required");
        }
        UUID consentId = uuid(auth, "ob_consent_id");
        ObjectNode consent = rows.lock(CONSENT, consentId);
        requireStatus(consent, "AWAITING_AUTHORISATION", "authorised");
        requireNotExpired(consent);
        UUID customerId = uuid(auth, "customer_id");
        for (String accountId : strings(auth.get("selected_account_ids"))) {
            ObjectNode a = rows.object();
            a.put("ob_consent_id", consentId.toString());
            a.put("account_id", accountId);
            a.put("account_resource_id", "acc-" + token(12));
            rows.insert("ob_consent_account", a);
        }
        rows.execute("update ob_authorisation set completed_at = now() where id = ?", authId);
        ObjectNode av = rows.object();
        av.put("status", "FINALISED");
        av.put("authentication_method", trim(authenticationMethod, 32));
        ObjectNode updatedAuth = rows.update(AUTH, authId, av);
        rows.execute("update ob_consent set authorised_at = now() where id = ?", consentId);
        ObjectNode cv = rows.object();
        cv.put("status", "AUTHORISED");
        cv.put("customer_id", customerId.toString());
        ObjectNode updated = rows.update(CONSENT, consentId, cv);
        events.publish("openbanking.ob_consent.authorised", CONSENT, updated);
        return updatedAuth;
    }

    @Transactional
    public ObjectNode deny(UUID authId, String reason) {
        ObjectNode auth = rows.lock(AUTH, authId);
        if (!OPEN_SESSION.contains(str(auth, "status"))) {
            throw ApiException.conflict("invalid_transition", "The authorisation is already " + str(auth, "status"));
        }
        UUID consentId = uuid(auth, "ob_consent_id");
        ObjectNode consent = rows.lock(CONSENT, consentId);
        requireStatus(consent, "AWAITING_AUTHORISATION", "denied");
        rows.execute("update ob_authorisation set completed_at = now() where id = ?", authId);
        ObjectNode av = rows.object();
        av.put("status", "FAILED");
        av.put("failure_reason", reason == null ? "Customer denied the consent" : trim(reason, 255));
        ObjectNode updatedAuth = rows.update(AUTH, authId, av);
        ObjectNode cv = rows.object();
        cv.put("status", "REJECTED");
        cv.put("status_reason", reason == null ? "Customer denied the consent" : trim(reason, 255));
        events.publish("openbanking.ob_consent.rejected", CONSENT, rows.update(CONSENT, consentId, cv));
        return updatedAuth;
    }

    @Transactional
    public ObjectNode revoke(UUID consentId, String revokedBy, String reason) {
        ObjectNode consent = rows.lock(CONSENT, consentId);
        String by = revokedBy == null ? null : revokedBy.toUpperCase(Locale.ROOT);
        if (!ConsentRules.REVOKERS.contains(by)) {
            throw ApiException.unprocessable("invalid_revoked_by", "revoked_by must be CUSTOMER, TPP or ASPSP");
        }
        String status = str(consent, "status");
        if (!"AUTHORISED".equals(status) && !"AWAITING_AUTHORISATION".equals(status)) {
            throw ApiException.conflict("invalid_transition", "A " + status + " consent cannot be revoked");
        }
        rows.execute("update ob_consent set revoked_at = now() where id = ?", consentId);
        rows.execute("update ob_authorisation set status = 'FAILED', failure_reason = 'Consent revoked', completed_at = now() "
                + "where ob_consent_id = ? and status in ('STARTED','SCA_REQUIRED','AUTHENTICATED','ACCOUNTS_SELECTED')", consentId);
        ObjectNode v = rows.object();
        v.put("status", "REVOKED");
        v.put("revoked_by", by);
        if (reason != null) {
            v.put("status_reason", trim(reason, 255));
        }
        ObjectNode updated = rows.update(CONSENT, consentId, v);
        events.publish("openbanking.ob_consent.revoked", CONSENT, updated);
        return updated;
    }

    /** Expires consents and SCA sessions whose expiry passed (database clock). */
    @Transactional
    public ObjectNode expireDue() {
        List<ObjectNode> expired = rows.query("""
                update ob_consent t set status = 'EXPIRED', status_reason = 'Consent validity ended'
                where status in ('AWAITING_AUTHORISATION', 'AUTHORISED') and expires_at is not null and expires_at <= now()
                returning row_to_json(t)::text""");
        for (ObjectNode c : expired) {
            events.publish("openbanking.ob_consent.expired", CONSENT, c);
        }
        int sessions = rows.execute("""
                update ob_authorisation set status = 'EXPIRED', failure_reason = 'Authorisation session expired'
                where status in ('STARTED','SCA_REQUIRED','AUTHENTICATED','ACCOUNTS_SELECTED') and expires_at <= now()""");
        ObjectNode out = rows.object();
        out.put("consents_expired", expired.size());
        out.put("authorisations_expired", sessions);
        ArrayNode ids = out.putArray("expired_consent_ids");
        expired.forEach(c -> ids.add(str(c, "id")));
        out.put("as_of", rows.now().toString());
        return out;
    }

    // ------------------------------------------------------------------ shared checks (also used by PIS / CBPII)

    /** Locks a consent and checks it is usable by this TPP: type, AUTHORISED, unexpired, TPP active. */
    ObjectNode usableConsent(UUID consentId, UUID tppId, Set<String> types) {
        ObjectNode consent = rows.lock(CONSENT, consentId);
        if (tppId == null || !tppId.equals(uuid(consent, "tpp_id"))) {
            throw new ApiException(403, "consent_tpp_mismatch", "The consent was not granted to this TPP");
        }
        requireActiveTpp(rows.get(TppService.TPP, tppId));
        if (!types.contains(str(consent, "consent_type"))) {
            throw ApiException.unprocessable("wrong_consent_type", "A " + str(consent, "consent_type")
                    + " consent cannot be used here (needs " + types + ")");
        }
        if (!"AUTHORISED".equals(str(consent, "status"))) {
            throw ApiException.unprocessable("consent_not_authorised", "The consent is " + str(consent, "status"));
        }
        requireNotExpired(consent);
        return consent;
    }

    void requireNotExpired(ObjectNode consent) {
        boolean expired = rows.scalar("select expires_at is not null and expires_at <= now() from ob_consent where id = ?",
                Boolean.class, uuid(consent, "id")).orElse(false);
        if (expired) {
            throw ApiException.unprocessable("consent_expired", "The consent has expired");
        }
    }

    static void requireActiveTpp(JsonNode tpp) {
        if (!"ACTIVE".equals(str(tpp, "status"))) {
            throw new ApiException(403, "tpp_not_active", "TPP " + str(tpp, "id") + " is " + str(tpp, "status"));
        }
    }

    private void requireOpenSession(ObjectNode auth) {
        if (!OPEN_SESSION.contains(str(auth, "status"))) {
            throw ApiException.conflict("invalid_transition", "The authorisation is already " + str(auth, "status"));
        }
        boolean expired = rows.scalar("select expires_at <= now() from ob_authorisation where id = ?", Boolean.class,
                uuid(auth, "id")).orElse(true);
        if (expired) {
            throw ApiException.unprocessable("authorisation_expired", "The authorisation session has expired");
        }
    }

    private static void requireStatus(ObjectNode consent, String expected, String verb) {
        if (!expected.equals(str(consent, "status"))) {
            throw ApiException.conflict("invalid_transition", "A " + str(consent, "status") + " consent cannot be " + verb);
        }
    }

    String token(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
