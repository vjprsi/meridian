package io.meridian.loanorigination.application;

import static io.meridian.loanorigination.infrastructure.Rows.str;
import static io.meridian.loanorigination.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.loanorigination.config.OriginationProperties;
import io.meridian.loanorigination.domain.Lifecycles;
import io.meridian.loanorigination.infrastructure.Rows;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * E-signature envelopes of accepted offers: send/resend/void through the provider adapter and the public provider
 * callback. A completed signature moves the application to SIGNED; booking the loan follows after commit
 * ({@link BookingService}).
 */
@Service
public class SignatureService {
    static final String ESIGN = "e_signature_request";
    private static final Set<String> OPEN = Set.of("CREATED", "SENT", "VIEWED");

    private final Support support;
    private final Rows rows;
    private final Events events;
    private final Map<String, ESignatureProvider> providers;
    private final OriginationProperties props;
    private final CallbackSecurity callbacks;

    public SignatureService(Support support, List<ESignatureProvider> providers, OriginationProperties props,
                            CallbackSecurity callbacks) {
        this.support = support;
        this.rows = support.rows();
        this.events = support.events();
        this.providers = providers.stream().collect(Collectors.toMap(p -> p.provider().toUpperCase(Locale.ROOT),
                Function.identity()));
        this.props = props;
        this.callbacks = callbacks;
    }

    /** Result of a provider callback; {@code signed} tells the caller to book the loan after commit. */
    public record CallbackResult(Map<String, Object> body, UUID applicationId, boolean signed) {}

    /** Creates the envelope for an accepted offer with the applicants as signers and sends it. */
    ObjectNode createAndSend(ObjectNode offer, String providerName) {
        String provider = (providerName == null || providerName.isBlank() ? props.defaultEsignProvider() : providerName)
                .toUpperCase(Locale.ROOT);
        provider(provider);
        UUID appId = uuid(offer, "loan_application_id");
        ArrayNode signers = rows.json().createArrayNode();
        int order = 0;
        for (ObjectNode a : rows.query("""
                select row_to_json(a)::text from loan_applicant a where loan_application_id = ?
                order by case role when 'PRIMARY' then 0 when 'CO_APPLICANT' then 1 else 2 end, created_at""", appId)) {
            signers.addObject().put("customer_id", str(a, "customer_id")).put("loan_applicant_id", str(a, "id"))
                    .put("role", str(a, "role")).put("order", ++order).put("status", "PENDING");
        }
        ObjectNode v = rows.object();
        v.put("loan_offer_id", str(offer, "id"));
        v.put("provider", provider);
        v.set("signers", signers);
        v.put("status", "CREATED");
        v.put("expires_at", support.dbNow().plusDays(props.envelopeValidityDays()).toString());
        ObjectNode env = rows.insert(ESIGN, v);
        return send(env, false);
    }

    @Transactional
    public ObjectNode send(UUID id) {
        ObjectNode env = rows.lock(ESIGN, id);
        Support.require(Lifecycles.ESIGN, "E-signature request", str(env, "status"), "SENT");
        return send(env, false);
    }

    @Transactional
    public ObjectNode resend(UUID id) {
        ObjectNode env = rows.lock(ESIGN, id);
        if (!Set.of("SENT", "VIEWED").contains(str(env, "status"))) {
            throw ApiException.conflict("invalid_transition", "Only sent envelopes awaiting signature can be resent (status "
                    + str(env, "status") + ")");
        }
        if (!rows.scalar("select coalesce(expires_at > now(), true) from e_signature_request where id = ?", Boolean.class, id)
                .orElse(false)) {
            throw ApiException.conflict("envelope_expired", "Envelope " + id + " has expired");
        }
        return send(env, true);
    }

    @Transactional
    public ObjectNode voidEnvelope(UUID id, String reason) {
        ObjectNode env = rows.lock(ESIGN, id);
        Support.require(Lifecycles.ESIGN, "E-signature request", str(env, "status"), "VOIDED");
        ObjectNode voided = close(env, "VOIDED", reason);
        ObjectNode offer = rows.lock(OfferService.OFFER, uuid(env, "loan_offer_id"));
        if ("ACCEPTED".equals(str(offer, "status"))) {
            ObjectNode w = rows.object();
            w.put("status", "WITHDRAWN");
            w.put("status_reason", "Signature envelope voided: " + reason);
            events.publish("lending.loan_offer.withdrawn", OfferService.OFFER, rows.update(OfferService.OFFER, uuid(offer, "id"), w));
            reopen(uuid(offer, "loan_application_id"));
        }
        return voided;
    }

    /** Voids the open envelopes of an offer (offer withdrawn/declined or application withdrawn). */
    void voidOpenEnvelopes(UUID offerId, String reason) {
        for (ObjectNode env : rows.query("""
                select row_to_json(e)::text from e_signature_request e where loan_offer_id = ?
                  and status in ('CREATED', 'SENT', 'VIEWED') for update""", offerId)) {
            close(env, "VOIDED", reason);
        }
    }

    /** Provider status callback, run as the owning tenant after the signature was verified. */
    @Transactional
    public CallbackResult callback(String providerName, UUID id, JsonNode body) {
        ObjectNode env = rows.lock(ESIGN, id);
        if (!str(env, "provider").equalsIgnoreCase(providerName)) {
            throw ApiException.notFound("e-signature envelope", Json.text(body, "provider_envelope_id"));
        }
        String to = Json.text(body, "provider_status") == null ? "" : Json.text(body, "provider_status").toUpperCase(Locale.ROOT);
        if (!Set.of("VIEWED", "SIGNED", "DECLINED", "EXPIRED", "VOIDED").contains(to)) {
            throw ApiException.unprocessable("invalid_status", "provider_status must be VIEWED, SIGNED, DECLINED, EXPIRED or VOIDED");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("e_signature_request_id", id.toString());
        UUID appId = uuid(rows.get(OfferService.OFFER, uuid(env, "loan_offer_id")), "loan_application_id");
        String from = str(env, "status");
        if (to.equals(from) || "VIEWED".equals(to) && "SIGNED".equals(from)) {
            out.put("status", from);
            out.put("outcome", "DUPLICATE");
            // a redelivered SIGNED retries a booking that failed the first time
            boolean retryBooking = "SIGNED".equals(from) && "SIGNED".equals(str(support.app(appId), "status"));
            return new CallbackResult(out, appId, retryBooking);
        }
        Support.require(Lifecycles.ESIGN, "E-signature request", from, to);
        ObjectNode updated;
        boolean signed = false;
        switch (to) {
            case "VIEWED" -> {
                ObjectNode v = rows.object();
                v.put("status", "VIEWED");
                updated = rows.update(ESIGN, id, v);
            }
            case "SIGNED" -> {
                ObjectNode v = rows.object();
                v.put("status", "SIGNED");
                v.put("signed_at", support.dbNow().toString());
                if (Json.text(body, "signed_document_id") != null) {
                    v.put("signed_document_id", Json.text(body, "signed_document_id"));
                }
                ArrayNode signers = ((ArrayNode) env.get("signers")).deepCopy();
                signers.forEach(s -> ((ObjectNode) s).put("status", "SIGNED"));
                v.set("signers", signers);
                updated = rows.update(ESIGN, id, v);
                events.publish("lending.e_signature_request.signed", ESIGN, updated);
                ObjectNode app = support.lockApp(appId);
                support.transition(app, "SIGNED");
                signed = true;
            }
            default -> { // DECLINED, EXPIRED, VOIDED: the offer is gone, the application may get a new offer
                updated = close(env, to, Json.text(body, "reason"));
                ObjectNode offer = rows.lock(OfferService.OFFER, uuid(env, "loan_offer_id"));
                if ("ACCEPTED".equals(str(offer, "status"))) {
                    String offerStatus = "DECLINED".equals(to) ? "DECLINED" : "EXPIRED".equals(to) ? "EXPIRED" : "WITHDRAWN";
                    ObjectNode w = rows.object();
                    w.put("status", offerStatus);
                    w.put("status_reason", "Signature " + to.toLowerCase(Locale.ROOT)
                            + (Json.text(body, "reason") == null ? "" : ": " + Json.text(body, "reason")));
                    events.publish("lending.loan_offer." + offerStatus.toLowerCase(Locale.ROOT),
                            OfferService.OFFER, rows.update(OfferService.OFFER, uuid(offer, "id"), w));
                    reopen(appId);
                }
            }
        }
        out.put("status", str(updated, "status"));
        out.put("outcome", "APPLIED");
        return new CallbackResult(out, appId, signed);
    }

    // ------------------------------------------------------------------ internals

    private ObjectNode send(ObjectNode env, boolean resend) {
        ESignatureProvider p = provider(str(env, "provider"));
        List<Map<String, Object>> signers = new ArrayList<>();
        env.path("signers").forEach(s -> {
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) Json.plain(s);
            signers.add(m);
        });
        String envelopeId = p.send(uuid(env, "id"), uuid(env, "agreement_document_id"), signers, resend);
        ObjectNode v = rows.object();
        v.put("provider_envelope_id", envelopeId);
        if (!resend) {
            v.put("status", "SENT");
        }
        v.put("sent_at", support.dbNow().toString());
        v.put("send_count", env.path("send_count").asInt(0) + 1);
        ObjectNode updated = rows.update(ESIGN, uuid(env, "id"), v);
        callbacks.register("E_SIGNATURE", str(env, "provider"), envelopeId, uuid(env, "id"));
        events.publish("lending.e_signature_request.sent", ESIGN, updated);
        return updated;
    }

    private ObjectNode close(ObjectNode env, String to, String reason) {
        if ("VOIDED".equals(to) && env.hasNonNull("provider_envelope_id")) {
            ESignatureProvider p = providers.get(str(env, "provider"));
            if (p != null) {
                p.voidEnvelope(str(env, "provider_envelope_id"), reason);
            }
        }
        ObjectNode v = rows.object();
        v.put("status", to);
        if (reason != null) {
            v.put("status_reason", reason.length() > 255 ? reason.substring(0, 255) : reason);
        }
        ObjectNode updated = rows.update(ESIGN, uuid(env, "id"), v);
        events.publish("DECLINED".equals(to) ? "lending.e_signature_request.declined"
                : "EXPIRED".equals(to) ? "lending.e_signature_request.expired" : "lending.e_signature_request.voided",
                ESIGN, updated);
        return updated;
    }

    /** The accepted offer fell away before signature: the application returns to APPROVED for a new offer. */
    private void reopen(UUID appId) {
        ObjectNode app = support.lockApp(appId);
        if ("OFFER_ACCEPTED".equals(str(app, "status"))) {
            support.transition(app, "APPROVED");
        }
    }

    private ESignatureProvider provider(String name) {
        ESignatureProvider p = providers.get(name);
        if (p == null) {
            throw ApiException.unprocessable("unsupported_esign_provider", "No e-signature provider " + name
                    + " (available: " + String.join(", ", providers.keySet()) + ")");
        }
        return p;
    }
}
