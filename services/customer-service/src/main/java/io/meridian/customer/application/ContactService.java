package io.meridian.customer.application;

import static io.meridian.customer.application.Json.now;
import static io.meridian.customer.application.Json.text;

import io.meridian.common.error.ApiException;
import io.meridian.customer.api.generated.model.CustomerCredential;
import io.meridian.customer.domain.Normalizer;
import io.meridian.customer.infrastructure.Crypto;
import io.meridian.customer.infrastructure.ExternalServices;
import io.meridian.customer.infrastructure.Rows;
import io.meridian.web.crud.CrudEngine;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

/**
 * Contact points and their verification by one-time password. A code is 6 random digits, stored only as a
 * salted hash with a 10 minute expiry in a customer_credential row of type SMS_OTP linked to the contact; five
 * wrong attempts lock it; a resend is possible after 30 seconds and supersedes the previous code.
 */
@Service
public class ContactService {
    static final String T = "customer_contact";
    private static final Set<String> PHONE_TYPES = Set.of("MOBILE", "LANDLINE", "WHATSAPP", "FAX");
    private static final Duration OTP_TTL = Duration.ofMinutes(10);
    private static final Duration RESEND_COOLDOWN = Duration.ofSeconds(30);
    static final int OTP_MAX_ATTEMPTS = 5;

    private final Rows rows;
    private final CrudEngine crud;
    private final Guards guards;
    private final Crypto crypto;
    private final ExternalServices external;
    private final Events events;

    public ContactService(Rows rows, CrudEngine crud, Guards guards, Crypto crypto, ExternalServices external, Events events) {
        this.rows = rows;
        this.crud = crud;
        this.guards = guards;
        this.crypto = crypto;
        this.external = external;
        this.events = events;
    }

    @Transactional
    public ObjectNode create(UUID customerId, Object body) {
        guards.openCustomer(customerId);
        ObjectNode v = crud.toColumns(T, body);
        v.put("customer_id", customerId.toString());
        normalise(v);
        if (v.path("is_primary").asBoolean()) {
            clearPrimary(customerId, text(v, "contact_type"), null);
        }
        return rows.insert(T, v);
    }

    @Transactional
    public ObjectNode update(UUID customerId, UUID id, String ifMatch, Object body) {
        int expected = CustomerService.parseVersion(ifMatch);
        ObjectNode current = guards.child(T, customerId, id, true);
        ObjectNode v = crud.toColumns(T, body);
        v.remove("customer_id");
        ObjectNode merged = current.deepCopy();
        merged.setAll(v);
        normalise(merged);
        for (String f : new String[] {"phone", "email"}) {
            if (v.has(f)) {
                v.set(f, merged.get(f));
            }
        }
        boolean pointChanged = !java.util.Objects.equals(text(current, "phone"), text(merged, "phone"))
                || !java.util.Objects.equals(text(current, "email"), text(merged, "email"));
        if (pointChanged) {
            v.put("is_verified", false);
            v.putNull("verified_at");
            rows.execute("""
                    update customer_credential set status = 'EXPIRED' where webauthn_credential_id = ? and status in ('ACTIVE', 'LOCKED')""",
                    otpLink(id));
        }
        if (v.path("is_primary").asBoolean()) {
            clearPrimary(customerId, text(merged, "contact_type"), id);
        }
        return rows.update(T, id, v, expected).orElseThrow(() -> CustomerService.versionConflict(expected));
    }

    private static void normalise(ObjectNode v) {
        String type = text(v, "contact_type");
        if (PHONE_TYPES.contains(type)) {
            String phone = Normalizer.phone(text(v, "phone"));
            if (phone == null || !Normalizer.E164.matcher(phone).matches()) {
                throw ApiException.unprocessable("invalid_phone", "A " + type + " contact needs a phone number in E.164 format");
            }
            if (text(v, "email") != null) {
                throw ApiException.unprocessable("invalid_contact", "A " + type + " contact cannot carry an email");
            }
            v.put("phone", phone);
        } else if ("EMAIL".equals(type)) {
            String email = Normalizer.email(text(v, "email"));
            if (email == null || !Normalizer.EMAIL.matcher(email).matches()) {
                throw ApiException.unprocessable("invalid_email", "An EMAIL contact needs a valid email address");
            }
            if (text(v, "phone") != null) {
                throw ApiException.unprocessable("invalid_contact", "An EMAIL contact cannot carry a phone number");
            }
            v.put("email", email);
        }
        if (text(v, "valid_from") != null && text(v, "valid_to") != null
                && text(v, "valid_to").compareTo(text(v, "valid_from")) < 0) {
            throw ApiException.unprocessable("invalid_period", "valid_to must not be before valid_from");
        }
    }

    private void clearPrimary(UUID customerId, String type, UUID except) {
        rows.execute("update customer_contact set is_primary = false where customer_id = ? and contact_type = ? and is_primary and id <> ?",
                customerId, type, except == null ? new UUID(0, 0) : except);
    }

    // ------------------------------------------------------------------ OTP verification

    @Transactional
    public void sendVerification(UUID customerId, UUID contactId) {
        ObjectNode customer = guards.openCustomer(customerId);
        ObjectNode contact = guards.child(T, customerId, contactId, true);
        if (contact.path("is_verified").asBoolean()) {
            throw ApiException.conflict("already_verified", "Contact is already verified");
        }
        if (!contact.path("can_receive_otp").asBoolean()) {
            throw ApiException.unprocessable("otp_not_allowed", "This contact may not receive one-time passwords");
        }
        String type = text(contact, "contact_type");
        if ("FAX".equals(type) || "LANDLINE".equals(type)) {
            throw ApiException.unprocessable("otp_not_supported", type + " contacts cannot receive one-time passwords");
        }
        OffsetDateTime now = now();
        Optional<ObjectNode> previous = activeOtp(contactId);
        if (previous.isPresent()) {
            OffsetDateTime sentAt = OffsetDateTime.parse(previous.get().get("created_at").asString());
            if (sentAt.plus(RESEND_COOLDOWN).isAfter(now)) {
                throw new ApiException(429, "otp_cooldown", "Wait " + RESEND_COOLDOWN.toSeconds() + "s before requesting a new code");
            }
            if ("LOCKED".equals(text(previous.get(), "status"))) {
                OffsetDateTime until = Json.timestamp(previous.get(), "locked_until");
                if (until != null && until.isAfter(now)) {
                    throw ApiException.conflict("verification_locked", "Too many wrong codes; try again later");
                }
            }
            ObjectNode expire = rows.object();
            expire.put("status", "EXPIRED");
            rows.update("customer_credential", UUID.fromString(previous.get().get("id").asString()), expire);
        }
        String code = crypto.numericCode(6);
        OffsetDateTime expiresAt = now.plus(OTP_TTL);
        ObjectNode otp = rows.object();
        otp.put("customer_id", customerId.toString());
        otp.put("credential_type", "SMS_OTP");
        otp.put("status", "ACTIVE");
        otp.put("webauthn_credential_id", otpLink(contactId));
        otp.put("secret_hash", crypto.hashOtp(code));
        otp.put("expires_at", expiresAt.toString());
        ObjectNode row = rows.insert("customer_credential", otp);
        String channel = switch (type) {
            case "EMAIL" -> "EMAIL";
            case "WHATSAPP" -> "WHATSAPP";
            default -> "SMS";
        };
        String destination = "EMAIL".equals(type) ? text(contact, "email") : text(contact, "phone");
        external.sendOtp(customerId, UUID.fromString(row.get("id").asString()), channel, destination, code, expiresAt,
                text(customer, "preferred_language"));
    }

    /** Wrong codes are counted (and committed) even though the request fails. */
    @Transactional(noRollbackFor = ApiException.class)
    public ObjectNode verify(UUID customerId, UUID contactId, String code) {
        guards.openCustomer(customerId);
        ObjectNode contact = guards.child(T, customerId, contactId, true);
        if (contact.path("is_verified").asBoolean()) {
            throw ApiException.conflict("already_verified", "Contact is already verified");
        }
        ObjectNode otp = activeOtp(contactId)
                .orElseThrow(() -> ApiException.conflict("no_active_verification", "Request a verification code first"));
        UUID otpId = UUID.fromString(otp.get("id").asString());
        if ("LOCKED".equals(text(otp, "status"))) {
            throw ApiException.conflict("verification_locked", "Too many wrong codes; request a new code later");
        }
        OffsetDateTime now = now();
        if (Json.timestamp(otp, "expires_at").isBefore(now)) {
            ObjectNode expire = rows.object();
            expire.put("status", "EXPIRED");
            rows.update("customer_credential", otpId, expire);
            throw ApiException.unprocessable("otp_expired", "The code has expired; request a new one");
        }
        if (code == null || !code.matches("\\d{6}") || !crypto.verifyOtp(code, text(otp, "secret_hash"))) {
            int attempts = otp.path("failed_attempts").asInt() + 1;
            ObjectNode fail = rows.object();
            fail.put("failed_attempts", attempts);
            if (attempts >= OTP_MAX_ATTEMPTS) {
                fail.put("status", "LOCKED");
                fail.put("locked_until", now.plusMinutes(30).toString());
                ObjectNode locked = rows.update("customer_credential", otpId, fail);
                events.publish("party.customer_credential.locked", "customer_credential", locked, CustomerCredential.class);
                throw ApiException.conflict("verification_locked", "Too many wrong codes; request a new code later");
            }
            rows.update("customer_credential", otpId, fail);
            throw ApiException.unprocessable("invalid_otp", "The code is not correct; "
                    + (OTP_MAX_ATTEMPTS - attempts) + " attempts left");
        }
        ObjectNode consumed = rows.object();
        consumed.put("status", "EXPIRED");
        consumed.put("last_used_at", now.toString());
        rows.update("customer_credential", otpId, consumed);
        ObjectNode v = rows.object();
        v.put("is_verified", true);
        v.put("verified_at", now.toString());
        return rows.update(T, contactId, v);
    }

    private Optional<ObjectNode> activeOtp(UUID contactId) {
        return rows.query("""
                select row_to_json(t)::text from customer_credential t where t.webauthn_credential_id = ?
                and t.credential_type = 'SMS_OTP' and t.status in ('ACTIVE', 'LOCKED') order by t.id desc limit 1 for update""",
                otpLink(contactId)).stream().findFirst();
    }

    static String otpLink(UUID contactId) {
        return "otp:contact:" + contactId;
    }
}
