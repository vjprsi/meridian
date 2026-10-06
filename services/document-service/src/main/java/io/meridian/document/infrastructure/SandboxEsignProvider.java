package io.meridian.document.infrastructure;

import io.meridian.document.application.EsignProvider;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Sandbox e-signature adapter: accepts every envelope, assigns a provider envelope id and records the calls.
 * Real DocuSign / Adobe Sign adapters replace it per environment; status changes then arrive via the signed
 * provider callback endpoint.
 */
@Component
public class SandboxEsignProvider implements EsignProvider {
    private static final Logger log = LoggerFactory.getLogger(SandboxEsignProvider.class);

    /** Calls made, newest last (inspection in sandbox / tests). */
    public final List<String> calls = new CopyOnWriteArrayList<>();

    @Override
    public String send(String provider, UUID esignRequestId, String documentDownloadUrl, List<Map<String, Object>> signers) {
        String envelopeId = provider.toLowerCase() + "-env-" + UUID.randomUUID();
        calls.add("send:" + envelopeId + ":" + signers.size());
        log.info("Sandbox e-sign {}: sent envelope {} for request {} to {} signer(s)", provider, envelopeId,
                esignRequestId, signers.size());
        return envelopeId;
    }

    @Override
    public void remind(String provider, String envelopeId, List<Map<String, Object>> pendingSigners) {
        calls.add("remind:" + envelopeId + ":" + pendingSigners.size());
        log.info("Sandbox e-sign {}: reminded {} signer(s) of {}", provider, pendingSigners.size(), envelopeId);
    }

    @Override
    public void cancel(String provider, String envelopeId, String reason) {
        calls.add("cancel:" + envelopeId);
        log.info("Sandbox e-sign {}: voided envelope {} ({})", provider, envelopeId, reason);
    }
}
