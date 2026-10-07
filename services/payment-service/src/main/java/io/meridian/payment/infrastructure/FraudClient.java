package io.meridian.payment.infrastructure;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.client.ServiceClient;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import tools.jackson.databind.JsonNode;

/**
 * fraud-service evaluation inside a time budget: the call runs on a virtual thread (tenant, principal and request
 * attributes propagated) and is abandoned when the budget is exceeded. Timeouts, errors and fraud-service's own
 * fallback answers ({@code fallback: true}, its store was unavailable) come back as {@link Result#unavailable}; the
 * caller then applies its fail-open / fail-closed policy. The idempotency key makes a retry replay the evaluation
 * fraud-service already stored.
 */
@Component
public class FraudClient implements DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(FraudClient.class);
    static final String PATH = "/v1/risk/fraud/scores/evaluate";

    /** Outcome of one evaluation; {@code degradedReason} is set when unavailable (TIMEOUT, UNAVAILABLE, ...). */
    public record Result(boolean available, String decision, Integer score, UUID scoreId, UUID alertId,
                         String reasonCodes, String degradedReason) {
        static Result unavailable(String reason) {
            return new Result(false, null, null, null, null, null, reason);
        }
    }

    private final ServiceClient client;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public FraudClient(ServiceClient client) {
        this.client = client;
    }

    public Result evaluate(JsonNode body, String key, int timeoutMs) {
        UUID tenant = TenantContext.require();
        String principal = TenantContext.principal();
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        boolean asService = ServiceClient.asServiceActive();
        Future<JsonNode> f = executor.submit(() -> {
            RequestContextHolder.setRequestAttributes(attrs);
            try {
                return TenantContext.callAs(tenant, principal, () -> asService
                        ? ServiceClient.asService(() -> client.post("fraud-service", PATH, body, key))
                        : client.post("fraud-service", PATH, body, key));
            } finally {
                RequestContextHolder.resetRequestAttributes();
            }
        });
        try {
            JsonNode r = f.get(timeoutMs, TimeUnit.MILLISECONDS);
            if (r.path("fallback").asBoolean(false) || !r.hasNonNull("decision")) {
                return Result.unavailable(r.path("degraded_reason").asString("SCORING_UNAVAILABLE"));
            }
            StringBuilder reasons = new StringBuilder();
            r.path("reason_codes").forEach(n -> reasons.append(reasons.isEmpty() ? "" : ",").append(n.asString()));
            return new Result(true, r.get("decision").asString(), r.hasNonNull("score") ? r.get("score").asInt() : null,
                    uuid(r, "fraud_score_id"), uuid(r, "fraud_alert_id"), reasons.toString(), null);
        } catch (TimeoutException e) {
            f.cancel(true);
            log.warn("fraud scoring exceeded {} ms ({})", timeoutMs, key);
            return Result.unavailable("TIMEOUT");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.unavailable("INTERRUPTED");
        } catch (ExecutionException e) {
            log.warn("fraud scoring failed ({}): {}", key, e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
            return Result.unavailable("UNAVAILABLE");
        } catch (RuntimeException e) {
            log.warn("fraud scoring failed ({}): {}", key, e.getMessage());
            return Result.unavailable("UNAVAILABLE");
        }
    }

    /**
     * A stored evaluation ({@code GET /v1/risk/fraud/scores/{id}}) read with payment-service's own identity, never the
     * caller's; empty when it does not exist in the tenant or fraud-service cannot answer.
     */
    public java.util.Optional<JsonNode> score(UUID scoreId) {
        try {
            return java.util.Optional.ofNullable(ServiceClient.asService(
                    () -> client.get("fraud-service", "/v1/risk/fraud/scores/" + scoreId)));
        } catch (RuntimeException e) {
            log.warn("fraud score {} could not be read: {}", scoreId, e.getMessage());
            return java.util.Optional.empty();
        }
    }

    private static UUID uuid(JsonNode r, String f) {
        String v = r.path(f).asString(null);
        return v == null || v.isBlank() ? null : UUID.fromString(v);
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
