package io.meridian.payment.application;

import static io.meridian.payment.infrastructure.Rows.integer;
import static io.meridian.payment.infrastructure.Rows.str;
import static io.meridian.payment.infrastructure.Rows.ts;
import static io.meridian.payment.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.payment.config.PaymentProperties;
import io.meridian.payment.domain.AliasRules;
import io.meridian.payment.domain.Bic;
import io.meridian.payment.domain.Iban;
import io.meridian.payment.domain.NameMatcher;
import io.meridian.payment.infrastructure.CoreBankingClient;
import io.meridian.payment.infrastructure.Rows;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Payee book with Confirmation of Payee (on-us name check with exact / close / no-match scoring), trust and blocking;
 * proxy aliases (phone, e-mail, VPA, PIX keys) with OTP ownership verification, resolution and portability.
 */
@Service
public class PayeeService {
    static final String PAYEE = "payee";
    static final String ALIAS = "payee_alias";
    static final String VERIFICATION = "payee_verification";
    private static final Set<String> EEA = Set.of("AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR",
            "HU", "IE", "IT", "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE", "IS", "LI", "NO");

    /** Outcome of a name check before it is stored. */
    public record CopResult(String result, java.math.BigDecimal score, String matchedName, String provider, String providerRef) {}

    private final Rows rows;
    private final Events events;
    private final CoreBankingClient core;
    private final PaymentProperties props;
    private final TransactionTemplate tx;
    private final SecureRandom random = new SecureRandom();

    public PayeeService(Rows rows, Events events, CoreBankingClient core, PaymentProperties props, TransactionTemplate tx) {
        this.rows = rows;
        this.events = events;
        this.core = core;
        this.props = props;
        this.tx = tx;
    }

    // ================================================================== payees

    public ObjectNode create(JsonNode req) {
        ObjectNode v = rows.columns(PAYEE, req);
        normaliseAccount(v);
        v.put("trusted", false);
        v.put("status", "PENDING_VERIFICATION");
        if (!v.hasNonNull("beneficiary_type")) {
            v.put("beneficiary_type", "INDIVIDUAL");
        } else if (!Set.of("INDIVIDUAL", "BUSINESS").contains(str(v, "beneficiary_type"))) {
            throw ApiException.unprocessable("invalid_beneficiary_type", "beneficiary_type must be INDIVIDUAL or BUSINESS");
        }
        return tx.execute(s -> {
            if (rows.scalar("select 1 from payee where customer_id = ? and account_number = ? and status <> 'DELETED' limit 1",
                    Integer.class, uuid(v, "customer_id"), str(v, "account_number")).isPresent()) {
                throw ApiException.conflict("duplicate_payee", "This account is already in the customer's payee book");
            }
            v.put("cooling_off_until", rows.now().plusHours(props.payeeCoolingOffHours()).toString());
            ObjectNode payee = rows.insert(PAYEE, v);
            events.publish("payments.payee.created", PAYEE, payee);
            return payee;
        });
    }

    /** PATCH with optimistic locking; changing the account or name resets verification and trust. */
    public ObjectNode update(UUID id, int expectedVersion, JsonNode req) {
        ObjectNode changes = rows.columns(PAYEE, req);
        for (String f : List.of("customer_id", "status", "verification_result", "verified_at", "cooling_off_until",
                "last_paid_at", "blocked_reason")) {
            if (changes.has(f)) {
                throw ApiException.unprocessable("field_not_updatable", f + " cannot be changed with PATCH");
            }
        }
        if (changes.has("trusted") && changes.get("trusted").asBoolean()) {
            throw ApiException.unprocessable("field_not_updatable", "Use POST /trust (after step-up authentication) to trust a payee");
        }
        return tx.execute(s -> {
            ObjectNode current = rows.lock(PAYEE, id);
            if (integer(current, "row_version") != expectedVersion) {
                throw ApiException.conflict("version_conflict", "If-Match " + expectedVersion
                        + " does not match the current version; reload and retry");
            }
            if ("DELETED".equals(str(current, "status"))) {
                throw ApiException.conflict("payee_deleted", "The payee was deleted");
            }
            ObjectNode merged = current.deepCopy();
            merged.setAll(changes);
            if (changes.has("account_number") || changes.has("account_id_type") || changes.has("country")) {
                normaliseAccount(merged);
                changes.put("account_number", str(merged, "account_number"));
                changes.put("country", str(merged, "country"));
            }
            if (changes.has("agent_bic")) {
                changes.put("agent_bic", str(merged, "agent_bic"));
            }
            boolean identityChanged = changes.has("account_number") && !str(changes, "account_number").equals(str(current, "account_number"))
                    || changes.has("name") && !str(changes, "name").equals(str(current, "name"));
            if (identityChanged && !"BLOCKED".equals(str(current, "status"))) {
                changes.put("status", "PENDING_VERIFICATION");
                changes.putNull("verification_result");
                changes.putNull("verified_at");
                changes.put("trusted", false);
                changes.put("cooling_off_until", rows.now().plusHours(props.payeeCoolingOffHours()).toString());
            }
            return rows.update(PAYEE, id, changes);
        });
    }

    /** Soft delete: payments keep referencing the payee. */
    public void delete(UUID id) {
        tx.execute(s -> {
            rows.lock(PAYEE, id);
            ObjectNode c = rows.object();
            c.put("status", "DELETED");
            c.put("trusted", false);
            return rows.update(PAYEE, id, c);
        });
    }

    public ObjectNode verify(UUID payeeId) {
        ObjectNode payee = rows.get(PAYEE, payeeId);
        if ("DELETED".equals(str(payee, "status"))) {
            throw ApiException.conflict("payee_deleted", "The payee was deleted");
        }
        CopResult r = check(str(payee, "name"), str(payee, "account_id_type"), str(payee, "account_number"),
                str(payee, "country"));
        return tx.execute(s -> {
            ObjectNode locked = rows.lock(PAYEE, payeeId);
            ObjectNode verification = store(r, str(payee, "name"), str(payee, "account_id_type"), str(payee, "account_number"),
                    str(payee, "agent_clearing_id"), payeeId, null);
            ObjectNode c = rows.object();
            c.put("verification_result", r.result());
            c.put("verified_at", rows.now().toString());
            if ("MATCH".equals(r.result()) && "PENDING_VERIFICATION".equals(str(locked, "status"))) {
                c.put("status", "ACTIVE");
            }
            rows.update(PAYEE, payeeId, c);
            return verification;
        });
    }

    /** Stand-alone name check (payee-verifications create). */
    public ObjectNode verifyStandalone(JsonNode req) {
        String type = str(req, "account_id_type");
        String number = "IBAN".equals(type) ? Iban.normalise(str(req, "account_number")) : str(req, "account_number");
        if ("IBAN".equals(type)) {
            Iban.validate(number).ifPresent(code -> {
                throw ApiException.unprocessable(code, "account_number is not a valid IBAN");
            });
        }
        UUID payeeId = uuid(req, "payee_id");
        if (payeeId != null) {
            rows.get(PAYEE, payeeId);
        }
        UUID paymentId = uuid(req, "payment_id");
        if (paymentId != null) {
            rows.get("payment", paymentId);
        }
        String country = "IBAN".equals(type) ? Iban.country(number) : null;
        CopResult r = check(str(req, "name_provided"), type, number, country);
        return tx.execute(s -> store(r, str(req, "name_provided"), type, number, str(req, "agent_clearing_id"), payeeId, paymentId));
    }

    private ObjectNode store(CopResult r, String name, String type, String number, String clearing, UUID payeeId, UUID paymentId) {
        ObjectNode v = rows.object();
        if (payeeId != null) {
            v.put("payee_id", payeeId.toString());
        }
        if (paymentId != null) {
            v.put("payment_id", paymentId.toString());
        }
        v.put("name_provided", name);
        v.put("account_id_type", type);
        v.put("account_number", number);
        if (clearing != null) {
            v.put("agent_clearing_id", clearing);
        }
        v.put("result", r.result());
        if (r.score() != null) {
            v.put("match_score", r.score().toPlainString());
        }
        if (r.matchedName() != null) {
            v.put("matched_name", r.matchedName());
        }
        v.put("provider", r.provider());
        v.put("provider_ref", r.providerRef());
        ObjectNode row = rows.insert(VERIFICATION, v);
        events.publish("payments.payee.verified", VERIFICATION, row);
        return row;
    }

    /**
     * Confirmation of Payee. On-us accounts are answered from our own books (account status and holder name);
     * accounts at other institutions are reported NOT_AVAILABLE until the scheme responder is connected.
     */
    public CopResult check(String providedName, String idType, String number, String country) {
        String ref = PaymentProcessor.reference("COP");
        Optional<JsonNode> onUs = core.findDepositAccount(idType, number);
        if (onUs.isPresent()) {
            JsonNode account = onUs.get();
            if ("CLOSED".equals(str(account, "status"))) {
                return new CopResult("ACCOUNT_CLOSED", null, null, "ON_US", ref);
            }
            Optional<String> actual = core.customerName(uuid(account, "customer_id"));
            if (actual.isEmpty()) {
                return new CopResult("NOT_AVAILABLE", null, null, "ON_US", ref);
            }
            NameMatcher.Result m = NameMatcher.match(providedName, actual.get(), props.cop().matchThreshold(),
                    props.cop().closeMatchThreshold());
            return new CopResult(m.outcome().name(), m.score(),
                    m.outcome() == NameMatcher.Outcome.CLOSE_MATCH ? actual.get() : null, "ON_US", ref);
        }
        if ("IBAN".equals(idType) && Iban.bankCode(number).map(c -> c.startsWith(props.bankCode())).orElse(false)) {
            return new CopResult("ACCOUNT_NOT_FOUND", null, null, "ON_US", ref);
        }
        String provider = "GB".equals(country) ? "UK_COP" : country != null && EEA.contains(country) ? "EU_VOP"
                : "NG".equals(country) ? "NIBSS_NAME_ENQUIRY" : "NAME_ENQUIRY";
        return new CopResult("NOT_AVAILABLE", null, null, provider, ref);
    }

    public ObjectNode trust(UUID payeeId) {
        return tx.execute(s -> {
            ObjectNode p = rows.lock(PAYEE, payeeId);
            if (!"ACTIVE".equals(str(p, "status"))) {
                throw ApiException.conflict("payee_not_verified", "Only an ACTIVE (verified) payee can be trusted; status is "
                        + str(p, "status"));
            }
            return rows.update(PAYEE, payeeId, rows.object().put("trusted", true));
        });
    }

    public ObjectNode block(UUID payeeId, String reason) {
        return tx.execute(s -> {
            ObjectNode p = rows.lock(PAYEE, payeeId);
            if (Set.of("BLOCKED", "DELETED").contains(str(p, "status"))) {
                throw ApiException.conflict("invalid_transition", "Payee is already " + str(p, "status"));
            }
            ObjectNode c = rows.object();
            c.put("status", "BLOCKED");
            c.put("trusted", false);
            c.put("blocked_reason", PaymentProcessor.truncate(reason, 255));
            ObjectNode updated = rows.update(PAYEE, payeeId, c);
            events.publish("payments.payee.blocked", PAYEE, updated);
            return updated;
        });
    }

    public ObjectNode unblock(UUID payeeId) {
        return tx.execute(s -> {
            ObjectNode p = rows.lock(PAYEE, payeeId);
            if (!"BLOCKED".equals(str(p, "status"))) {
                throw ApiException.conflict("invalid_transition", "Payee is not blocked");
            }
            ObjectNode c = rows.object();
            c.put("status", "MATCH".equals(str(p, "verification_result")) ? "ACTIVE" : "PENDING_VERIFICATION");
            c.putNull("blocked_reason");
            return rows.update(PAYEE, payeeId, c);
        });
    }

    private static void normaliseAccount(ObjectNode v) {
        String type = str(v, "account_id_type");
        String number = str(v, "account_number");
        if ("IBAN".equals(type)) {
            String iban = Iban.normalise(number);
            Iban.validate(iban).ifPresent(code -> {
                throw ApiException.unprocessable(code, "account_number is not a valid IBAN");
            });
            v.put("account_number", iban);
            if (v.hasNonNull("country") && !Iban.country(iban).equals(str(v, "country"))) {
                throw ApiException.unprocessable("country_mismatch", "country does not match the IBAN country " + Iban.country(iban));
            }
            v.put("country", Iban.country(iban));
        } else if (number != null && number.isBlank()) {
            throw ApiException.unprocessable("invalid_account_number", "account_number is required");
        }
        if (v.hasNonNull("agent_bic")) {
            String bic = Bic.normalise(str(v, "agent_bic"));
            if (!Bic.isValid(bic)) {
                throw ApiException.unprocessable("invalid_bic", "agent_bic " + bic + " is not a valid BIC");
            }
            v.put("agent_bic", bic);
        }
    }

    // ================================================================== aliases

    public ObjectNode registerAlias(JsonNode req) {
        ObjectNode v = rows.columns(ALIAS, req);
        String type = str(v, "alias_type");
        String value;
        try {
            value = AliasRules.normalise(type, str(v, "alias_value"));
        } catch (AliasRules.InvalidAlias e) {
            throw ApiException.unprocessable("invalid_alias", e.getMessage());
        }
        v.put("alias_value", value);
        UUID accountId = uuid(v, "account_id");
        UUID customerId = uuid(v, "customer_id");
        if (accountId == null) {
            throw ApiException.unprocessable("account_required", "An alias must point to an account (account_id)");
        }
        if (customerId == null) {
            throw ApiException.unprocessable("customer_required", "customer_id (the alias owner) is required");
        }
        JsonNode account = core.depositAccount(accountId, "alias");
        if (!customerId.equals(uuid(account, "customer_id"))) {
            throw ApiException.unprocessable("account_not_owned", "The account does not belong to the alias owner");
        }
        if (Set.of("CLOSED", "BLOCKED", "BLOCKED_CREDIT").contains(str(account, "status"))) {
            throw ApiException.unprocessable("alias_account_not_active", "The account is " + str(account, "status"));
        }
        if (!v.hasNonNull("display_name")) {
            core.customerName(customerId).ifPresent(n -> v.put("display_name", PaymentProcessor.truncate(n, 140)));
        }
        String otp = otp();
        ObjectNode alias = tx.execute(s -> {
            if (rows.scalar("select 1 from payee_alias where alias_type = ? and alias_value = ? limit 1", Integer.class, type, value)
                    .isPresent()) {
                throw ApiException.conflict("alias_taken", "This alias is already registered");
            }
            if (Boolean.TRUE.equals(v.path("is_default").asBoolean(true))) {
                rows.execute("update payee_alias set is_default = false where customer_id = ? and alias_type = ? and is_default",
                        customerId, type);
            }
            v.put("status", "PENDING");
            v.put("id", io.meridian.common.id.Ids.newId().toString());
            v.put("otp_hash", hash(otp, str(v, "id")));
            v.put("otp_expires_at", rows.now().plusMinutes(props.otp().ttlMinutes()).toString());
            v.put("otp_attempts", 0);
            ObjectNode a = rows.insert(ALIAS, v);
            events.publish("payments.payee_alias.registered", ALIAS, a);
            return a;
        });
        sendOtp(alias, otp);
        return alias;
    }

    private void sendOtp(ObjectNode alias, String otp) {
        ObjectNode vars = rows.object();
        vars.put("otp", otp);
        vars.put("alias", AliasRules.mask(str(alias, "alias_type"), str(alias, "alias_value")));
        vars.put("expires_in_minutes", props.otp().ttlMinutes());
        String channel = "EMAIL".equals(str(alias, "alias_type")) ? "EMAIL" : "SMS";
        core.notifyCustomer(uuid(alias, "customer_id"), "PAYEE_ALIAS_OTP", channel, vars,
                "alias-otp:" + str(alias, "id") + ":" + hash(otp, "k").substring(0, 12));
    }

    /** OTP check; failed attempts are counted (and committed) before the error is returned. */
    public ObjectNode verifyOwnership(UUID aliasId, String otp) {
        String[] failure = new String[2];
        ObjectNode result = tx.execute(s -> {
            ObjectNode a = rows.lock(ALIAS, aliasId);
            if (!"PENDING".equals(str(a, "status"))) {
                throw ApiException.conflict("invalid_transition", "Alias is " + str(a, "status") + ", not awaiting verification");
            }
            if (integer(a, "otp_attempts") >= props.otp().maxAttempts()) {
                throw ApiException.conflict("otp_locked", "Too many wrong codes; request a new OTP");
            }
            if (ts(a, "otp_expires_at") == null || ts(a, "otp_expires_at").isBefore(rows.now())) {
                failure[0] = "otp_expired";
                failure[1] = "The code has expired; request a new OTP";
                return null;
            }
            if (otp == null || !hash(otp.trim(), aliasId.toString()).equals(str(a, "otp_hash"))) {
                rows.update(ALIAS, aliasId, rows.object().put("otp_attempts", integer(a, "otp_attempts") + 1));
                failure[0] = "invalid_otp";
                failure[1] = "The code is not correct";
                return null;
            }
            ObjectNode c = rows.object();
            c.put("status", "ACTIVE");
            c.put("verified_at", rows.now().toString());
            c.putNull("otp_hash");
            c.putNull("otp_expires_at");
            c.put("otp_attempts", 0);
            if (!a.hasNonNull("registry_ref")) {
                c.put("registry_ref", PaymentProcessor.reference("REG"));
            }
            ObjectNode verified = rows.update(ALIAS, aliasId, c);
            events.publish("payments.payee_alias.verified", ALIAS, verified);
            return verified;
        });
        if (result == null) {
            throw ApiException.unprocessable(failure[0], failure[1]);
        }
        return result;
    }

    public ObjectNode resendOtp(UUID aliasId) {
        String otp = otp();
        ObjectNode alias = tx.execute(s -> {
            ObjectNode a = rows.lock(ALIAS, aliasId);
            if (!"PENDING".equals(str(a, "status"))) {
                throw ApiException.conflict("invalid_transition", "Alias is " + str(a, "status") + ", not awaiting verification");
            }
            ObjectNode c = rows.object();
            c.put("otp_hash", hash(otp, aliasId.toString()));
            c.put("otp_expires_at", rows.now().plusMinutes(props.otp().ttlMinutes()).toString());
            c.put("otp_attempts", 0);
            return rows.update(ALIAS, aliasId, c);
        });
        sendOtp(alias, otp);
        return alias;
    }

    /** Resolves an ACTIVE alias to a masked payee; the owner's customer id is never disclosed. */
    public ObjectNode resolve(String type, String rawValue) {
        String value;
        try {
            value = AliasRules.normalise(type, rawValue);
        } catch (AliasRules.InvalidAlias e) {
            throw ApiException.unprocessable("invalid_alias", e.getMessage());
        }
        ObjectNode a = rows.queryOne("select row_to_json(t)::text from payee_alias t where alias_type = ? and alias_value = ? "
                        + "and status = 'ACTIVE'", type, value)
                .orElseThrow(() -> new ApiException(404, "alias_not_found", "No active alias " + type + " " + AliasRules.mask(type, value)));
        a.putNull("customer_id");
        a.put("display_name", NameMatcher.mask(str(a, "display_name")));
        a.put("alias_value", AliasRules.mask(type, value));
        return a;
    }

    public ObjectNode requestPort(UUID aliasId, String donor) {
        return tx.execute(s -> {
            ObjectNode a = rows.lock(ALIAS, aliasId);
            if (!"PENDING".equals(str(a, "status"))) {
                throw ApiException.conflict("invalid_transition", "Only an alias awaiting registration can be ported in; status is "
                        + str(a, "status"));
            }
            ObjectNode c = rows.object();
            c.put("status", "PORTING");
            c.put("donor_institution", PaymentProcessor.truncate(donor, 140));
            ObjectNode updated = rows.update(ALIAS, aliasId, c);
            events.publish("payments.payee_alias.port_requested", ALIAS, updated);
            return updated;
        });
    }

    public ObjectNode completePort(UUID aliasId) {
        return tx.execute(s -> {
            ObjectNode a = rows.lock(ALIAS, aliasId);
            if (!"PORTING".equals(str(a, "status"))) {
                throw ApiException.conflict("invalid_transition", "Alias is not being ported");
            }
            ObjectNode c = rows.object();
            c.put("status", "ACTIVE");
            c.put("verified_at", rows.now().toString());
            c.put("registry_ref", PaymentProcessor.reference("REG"));
            c.putNull("otp_hash");
            ObjectNode updated = rows.update(ALIAS, aliasId, c);
            events.publish("payments.payee_alias.verified", ALIAS, updated);
            return updated;
        });
    }

    private String otp() {
        return String.format("%06d", random.nextInt(1_000_000));
    }

    static String hash(String otp, String salt) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((salt + ":" + otp).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
