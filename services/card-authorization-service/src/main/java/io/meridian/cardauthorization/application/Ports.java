package io.meridian.cardauthorization.application;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Outbound ports of the authorisation service (implemented in infrastructure over ServiceClient / HTTP). */
public final class Ports {
    private Ports() {}

    /** card-service: GET /v1/cards/{id}/auth-context. */
    public interface CardDirectory {
        JsonNode authContext(UUID cardId);
    }

    /** ledger-service holds. Insufficient funds = ApiException 422 insufficient_funds; unavailable = 5xx. */
    public interface Ledger {
        UUID placeHold(String key, UUID account, BigDecimal amount, String currency, UUID authorizationId,
                       OffsetDateTime expiresAt, String description);

        void incrementHold(UUID holdId, BigDecimal amount, String reason, String key);

        void releaseHold(UUID holdId, String reason, String key);

        BigDecimal available(UUID account);
    }

    /** fraud-service: POST /v1/risk/fraud/scores/evaluate (raw response). */
    public interface Fraud {
        JsonNode evaluate(JsonNode body, String key);
    }

    /** card-vault-service: POST /v1/card-vault/verify (raw response). */
    public interface Vault {
        JsonNode verify(JsonNode body, String key);
    }

    /** card-clearing-service: sandbox presentment. */
    public interface Clearing {
        JsonNode submitFile(JsonNode body, String key);

        JsonNode records(UUID fileId);
    }

    /** Client JIT funding webhook. */
    public interface Jit {
        Response call(String url, int timeoutMs, JsonNode payload);

        /** result APPROVED, DECLINED, TIMEOUT or ERROR. */
        record Response(String result, BigDecimal approvedAmount, Integer httpStatus, int latencyMs, JsonNode body,
                        String declineReason) {}
    }

    public static boolean insufficientFunds(ApiException e) {
        return e.status() == 422 && "insufficient_funds".equals(e.code());
    }

    /** Downstream unavailable (5xx / connection failure / timeout) as opposed to a business rejection. */
    public static boolean unavailable(RuntimeException e) {
        return !(e instanceof ApiException a) || a.status() >= 500;
    }
}
