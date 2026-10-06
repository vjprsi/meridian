package io.meridian.loanorigination.infrastructure;

import io.meridian.loanorigination.application.ESignatureProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Sandbox e-signature provider: deterministic envelope ids, no external calls (signing is driven by callbacks). */
@Component
public class SandboxESignatureProvider implements ESignatureProvider {

    @Override
    public String provider() {
        return "SANDBOX";
    }

    @Override
    public String send(UUID requestId, UUID agreementDocumentId, List<Map<String, Object>> signers, boolean resend) {
        return envelopeId(requestId);
    }

    @Override
    public void voidEnvelope(String providerEnvelopeId, String reason) {
        // nothing to do in the sandbox
    }

    public static String envelopeId(UUID requestId) {
        try {
            return "sbx-env-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(requestId.toString().getBytes(StandardCharsets.UTF_8))).substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
