package io.meridian.loanorigination.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An e-signature provider integration (DocuSign, Adobe Sign, in-app OTP, sandbox). Status changes come back
 * through the public, HMAC-signed callback.
 */
public interface ESignatureProvider {

    /** Provider code as used in {@code e_signature_request.provider}. */
    String provider();

    /** Creates (or re-sends) the envelope; returns the provider envelope id (stable for the same request id). */
    String send(UUID requestId, UUID agreementDocumentId, List<Map<String, Object>> signers, boolean resend);

    /** Voids the envelope at the provider. */
    void voidEnvelope(String providerEnvelopeId, String reason);
}
