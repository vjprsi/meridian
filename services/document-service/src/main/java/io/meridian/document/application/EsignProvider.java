package io.meridian.document.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Port to an electronic-signature provider (DocuSign, Adobe Sign, native OTP). */
public interface EsignProvider {

    /** Creates and sends the envelope; returns the provider envelope id. */
    String send(String provider, UUID esignRequestId, String documentDownloadUrl, List<Map<String, Object>> signers);

    void remind(String provider, String envelopeId, List<Map<String, Object>> pendingSigners);

    void cancel(String provider, String envelopeId, String reason);
}
