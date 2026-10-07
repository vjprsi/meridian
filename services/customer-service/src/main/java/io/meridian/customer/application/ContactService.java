package io.meridian.customer.application;

import static io.meridian.customer.application.Json.now;
import static io.meridian.customer.application.Json.text;

import io.meridian.common.error.ApiException;
import io.meridian.customer.api.generated.model.ContactVerification;
import io.meridian.customer.api.generated.model.CustomerContact;
import io.meridian.customer.domain.Normalizer;
import io.meridian.customer.infrastructure.ContactLookup;
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
 * Contact points and their verification by one-time password. Each code is a contact_verification challenge: 6
 * random digits, stored only as a salted hash, valid for 10 minutes; five wrong attempts lock the challenge for 30
 * minutes; a new code can be requested after 30 seconds and supersedes the pending one.
 *
 * <p>Search: every contact carries {@code masked_value} and {@code lookup_hash} ({@link ContactLookup}). A verified
 * contact is announced with {@code party.customer_contact.verified}, and its deletion, change of value or erasure with
 * {@code party.customer_contact.removed}; both payloads carry the masked value and the hash, never the phone or e-mail,
 * so search-service can find a customer by exact contact without indexing it.
 */
@Service
public class ContactService {
    static final String T = "customer_contact";
    static final String V = "contact_verification";
    private static final Duration LOCKOUT = Duration.ofMinutes(30);
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
    private final ContactLookup lookup;

    public ContactService(Rows rows, CrudEngine crud, Guards guards, Crypto crypto, ExternalServices external, Events events,
                          ContactLookup lookup) {
        this.rows = rows;
        this.lookup = lookup;
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
        searchFields(v, v);
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
            searchFields(merged, v);
            if (current.path("is_verified").asBoolean()) {
                removed(current);
            }
            v.put("is_verified", false);
            v.putNull("verified_at");
            rows.execute("""
                    update contact_verification set status = 'SUPERSEDED' where customer_contact_id = ? and status in ('PENDING', 'LOCKED')""",
                    id);
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

    /** Removes a contact point together with its verification history. */
    @Transactional
    public void delete(UUID customerId, UUID id) {
        ObjectNode contact = guards.child(T, customerId, id, true);
        if (contact.path("is_verified").asBoolean()) {
            removed(contact);
        }
        rows.execute("delete from contact_verification where customer_contact_id = ?", id);
        rows.execute("delete from customer_contact where id = ?", id);
    }

    /** Announces the removal of every verified contact of a customer (erasure), before the rows are deleted. */
    public void removeAllFromSearch(UUID customerId) {
        verified(customerId).forEach(this::removed);
    }

    /** The customer's verified contacts (rows), e.g. before a merge moves or drops them. */
    public java.util.List<ObjectNode> verified(UUID customerId) {
        return rows.query("select row_to_json(t)::text from customer_contact t where t.customer_id = ? and t.is_verified", customerId);
    }

    /** After a merge: verified contacts that moved to the survivor are announced again, the dropped copies removed. */
    public void afterMerge(java.util.List<ObjectNode> verifiedBefore) {
        for (ObjectNode c : verifiedBefore) {
            UUID id = UUID.fromString(c.get("id").asString());
            Optional<ObjectNode> moved = rows.query("select row_to_json(t)::text from customer_contact t where t.id = ?", id)
                    .stream().findFirst();
            if (moved.isPresent()) {
                events.publishPayload("party.customer_contact.verified", T, id, eventPayload(moved.get()));
            } else {
                removed(c);
            }
        }
    }

    /** Masked display value and lookup hash of the contact point in {@code source}, written into {@code target}. */
    private void searchFields(ObjectNode source, ObjectNode target) {
        String value = text(source, "email") != null ? text(source, "email") : text(source, "phone");
        if (value == null) {
            target.putNull("masked_value");
            target.putNull("lookup_hash");
            return;
        }
        target.put("masked_value", mask(value));
        String hash = lookup.hash(value);
        if (hash == null) {
            target.putNull("lookup_hash");
        } else {
            target.put("lookup_hash", hash);
        }
    }

    private void removed(ObjectNode contact) {
        events.publishPayload("party.customer_contact.removed", T, UUID.fromString(contact.get("id").asString()), eventPayload(contact));
    }

    /** The contact's API representation without the phone number or e-mail address (masked value and hash only). */
    private Object eventPayload(ObjectNode contact) {
        ObjectNode p = rows.toTree(rows.read(contact, CustomerContact.class));
        p.remove("phone");
        p.remove("email");
        return p;
    }

    // ------------------------------------------------------------------ OTP verification

    /** Issues a new challenge (superseding a pending one) and sends the code; returns the challenge. */
    @Transactional
    public ObjectNode sendVerification(UUID customerId, UUID contactId) {
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
        Optional<ObjectNode> previous = openChallenge(contactId);
        if (previous.isPresent()) {
            ObjectNode p = previous.get();
            if (Json.timestamp(p, "resend_available_at").isAfter(now)) {
                throw new ApiException(429, "otp_cooldown", "Wait " + RESEND_COOLDOWN.toSeconds() + "s before requesting a new code");
            }
            OffsetDateTime until = Json.timestamp(p, "locked_until");
            if ("LOCKED".equals(text(p, "status")) && until != null && until.isAfter(now)) {
                throw ApiException.conflict("verification_locked", "Too many wrong codes; try again later");
            }
            ObjectNode supersede = rows.object();
            supersede.put("status", "LOCKED".equals(text(p, "status")) ? "EXPIRED" : "SUPERSEDED");
            rows.update(V, UUID.fromString(p.get("id").asString()), supersede);
        }
        String channel = switch (type) {
            case "EMAIL" -> "EMAIL";
            case "WHATSAPP" -> "WHATSAPP";
            default -> "SMS";
        };
        String destination = "EMAIL".equals(type) ? text(contact, "email") : text(contact, "phone");
        String code = crypto.numericCode(6);
        OffsetDateTime expiresAt = now.plus(OTP_TTL);
        ObjectNode challenge = rows.object();
        challenge.put("customer_id", customerId.toString());
        challenge.put("customer_contact_id", contactId.toString());
        challenge.put("channel", channel);
        challenge.put("destination_masked", mask(destination));
        challenge.put("code_hash", crypto.hashOtp(code));
        challenge.put("status", "PENDING");
        challenge.put("max_attempts", OTP_MAX_ATTEMPTS);
        challenge.put("expires_at", expiresAt.toString());
        challenge.put("resend_available_at", now.plus(RESEND_COOLDOWN).toString());
        ObjectNode row = rows.insert(V, challenge);
        external.sendOtp(customerId, UUID.fromString(row.get("id").asString()), channel, destination, code, expiresAt,
                text(customer, "preferred_language"));
        return row;
    }

    /** Wrong codes are counted (and committed) even though the request fails. */
    @Transactional(noRollbackFor = ApiException.class)
    public ObjectNode verify(UUID customerId, UUID contactId, String code) {
        guards.openCustomer(customerId);
        ObjectNode contact = guards.child(T, customerId, contactId, true);
        if (contact.path("is_verified").asBoolean()) {
            throw ApiException.conflict("already_verified", "Contact is already verified");
        }
        ObjectNode challenge = openChallenge(contactId)
                .orElseThrow(() -> ApiException.conflict("no_active_verification", "Request a verification code first"));
        UUID challengeId = UUID.fromString(challenge.get("id").asString());
        if ("LOCKED".equals(text(challenge, "status"))) {
            throw ApiException.conflict("verification_locked", "Too many wrong codes; request a new code later");
        }
        OffsetDateTime now = now();
        if (Json.timestamp(challenge, "expires_at").isBefore(now)) {
            ObjectNode expire = rows.object();
            expire.put("status", "EXPIRED");
            rows.update(V, challengeId, expire);
            throw ApiException.unprocessable("otp_expired", "The code has expired; request a new one");
        }
        if (code == null || !code.matches("\\d{6}") || !crypto.verifyOtp(code, text(challenge, "code_hash"))) {
            int attempts = challenge.path("attempts").asInt() + 1;
            int max = challenge.path("max_attempts").asInt(OTP_MAX_ATTEMPTS);
            ObjectNode fail = rows.object();
            fail.put("attempts", attempts);
            if (attempts >= max) {
                fail.put("status", "LOCKED");
                fail.put("locked_until", now.plus(LOCKOUT).toString());
                ObjectNode locked = rows.update(V, challengeId, fail);
                events.publish("party.contact_verification.locked", V, locked, ContactVerification.class);
                throw ApiException.conflict("verification_locked", "Too many wrong codes; request a new code later");
            }
            rows.update(V, challengeId, fail);
            throw ApiException.unprocessable("invalid_otp", "The code is not correct; " + (max - attempts) + " attempts left");
        }
        ObjectNode done = rows.object();
        done.put("status", "VERIFIED");
        done.put("verified_at", now.toString());
        rows.update(V, challengeId, done);
        ObjectNode v = rows.object();
        v.put("is_verified", true);
        v.put("verified_at", now.toString());
        if (text(contact, "masked_value") == null || text(contact, "lookup_hash") == null) {
            searchFields(contact, v); // contacts created before the search fields existed
        }
        ObjectNode verified = rows.update(T, contactId, v);
        events.publishPayload("party.customer_contact.verified", T, contactId, eventPayload(verified));
        return verified;
    }

    /** Challenges of a contact, newest first (keyset paging). */
    @Transactional(readOnly = true)
    public tools.jackson.databind.JsonNode verifications(UUID customerId, UUID contactId, Integer limit, String cursor) {
        guards.child(T, customerId, contactId, false);
        return rows.page("select row_to_json(t)::text from contact_verification t where t.customer_contact_id = ?",
                java.util.List.of(contactId), limit, cursor);
    }

    /** The challenge that can still be answered (or is locked) for the contact; locked for the transaction. */
    private Optional<ObjectNode> openChallenge(UUID contactId) {
        return rows.query("""
                select row_to_json(t)::text from contact_verification t where t.customer_contact_id = ?
                and t.status in ('PENDING', 'LOCKED') order by t.id desc limit 1 for update""", contactId).stream().findFirst();
    }

    /** +447700900123 -> +44*******123; alice@example.com -> a***e@example.com. */
    static String mask(String destination) {
        if (destination == null) {
            return "***";
        }
        int at = destination.indexOf('@');
        if (at > 0) {
            String local = destination.substring(0, at);
            String shown = local.length() <= 2 ? local.substring(0, 1) + "*" : local.charAt(0) + "***" + local.charAt(local.length() - 1);
            return shown + destination.substring(at);
        }
        if (destination.length() <= 6) {
            return "***";
        }
        return destination.substring(0, 3) + "*".repeat(destination.length() - 6) + destination.substring(destination.length() - 3);
    }
}
