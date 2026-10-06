package io.meridian.cardauthorization.application;

import io.meridian.cardauthorization.domain.AuthDecisionEngine.FraudResult;
import io.meridian.common.tenancy.TenantContext;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import tools.jackson.databind.JsonNode;

/**
 * Fraud scoring inside the program's time budget: the call runs on a virtual thread (tenant, principal and request
 * attributes propagated) and is abandoned when the budget is exceeded. Timeouts and errors return
 * {@link FraudResult#unavailable()}; the engine then applies the program's FAIL_OPEN / FAIL_CLOSED policy.
 */
@Component
public class FraudGateway {
    private static final Logger log = LoggerFactory.getLogger(FraudGateway.class);

    private final Ports.Fraud fraud;
    private final ExecutorService executor;

    public FraudGateway(Ports.Fraud fraud, ExecutorService authCallExecutor) {
        this.fraud = fraud;
        this.executor = authCallExecutor;
    }

    public FraudResult score(UUID authorizationId, JsonNode body, int timeoutMs) {
        UUID tenant = TenantContext.require();
        String principal = TenantContext.principal();
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        Future<JsonNode> f = executor.submit(() -> {
            RequestContextHolder.setRequestAttributes(attrs);
            try {
                return TenantContext.callAs(tenant, principal, () -> fraud.evaluate(body, "fraud:" + authorizationId));
            } finally {
                RequestContextHolder.resetRequestAttributes();
            }
        });
        try {
            JsonNode r = f.get(timeoutMs, TimeUnit.MILLISECONDS);
            Integer score = r.hasNonNull("score") ? r.get("score").asInt() : null;
            String decision = r.hasNonNull("decision") ? r.get("decision").asString() : "ALLOW";
            UUID id = r.hasNonNull("id") ? UUID.fromString(r.get("id").asString()) : null;
            return new FraudResult(true, score, decision, id);
        } catch (TimeoutException e) {
            f.cancel(true);
            log.warn("fraud scoring exceeded {} ms for authorization {}", timeoutMs, authorizationId);
            return FraudResult.unavailable();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return FraudResult.unavailable();
        } catch (Exception e) {
            log.warn("fraud scoring failed for authorization {}: {}", authorizationId, e.getMessage());
            return FraudResult.unavailable();
        }
    }
}
