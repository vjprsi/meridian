package io.meridian.payment.api;

import io.meridian.payment.api.generated.PaymentsApi;
import org.springframework.web.bind.annotation.RestController;

/**
 * Payments API. Operations not yet overridden here return 501 Not Implemented (generated default methods).
 * Contract: generated/openapi/services/payment-service.openapi.yaml
 */
@RestController
public class PaymentsController implements PaymentsApi {
}
