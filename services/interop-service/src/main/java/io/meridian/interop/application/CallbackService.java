package io.meridian.interop.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.interop.domain.InteropCrypto;
import io.meridian.interop.infrastructure.Rows;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BiFunction;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Public switch callbacks (no bearer token, no Idempotency-Key). The signature header is verified before any tenant
 * data is touched: lowercase hex HMAC-SHA256 of the canonical JSON request body with the secret of the switch code
 * ({@code meridian.interop.switch-secrets.<SWITCH_CODE>}); unsigned or unknown-switch callbacks are refused. The
 * tenant comes from the callback URL registered with the switch. Handlers are idempotent on switch ids.
 */
@Service
public class CallbackService {
    public static final String HEADER = "X-Switch-Signature";
    static final String SECRET_PREFIX = "meridian.interop.switch-secrets.";

    private final Environment env;
    private final Rows rows;
    private final QuoteService quotes;
    private final TransferService transfers;

    public CallbackService(Environment env, Rows rows, QuoteService quotes, TransferService transfers) {
        this.env = env;
        this.rows = rows;
        this.quotes = quotes;
        this.transfers = transfers;
    }

    public ObjectNode quoteCallback(UUID tenantId, JsonNode body, String signature) {
        return handle(tenantId, body, signature, (sw, p) -> switch (type(p)) {
            case "QUOTE_REQUEST" -> quotes.onQuoteRequest(sw, p);
            case "QUOTE_RESPONSE" -> quotes.onQuoteResponse(sw, p, false);
            case "QUOTE_ERROR" -> quotes.onQuoteResponse(sw, p, true);
            default -> throw ApiException.unprocessable("unsupported_callback", "Unsupported quote callback " + type(p));
        });
    }

    public ObjectNode transferCallback(UUID tenantId, JsonNode body, String signature) {
        return handle(tenantId, body, signature, (sw, p) -> switch (type(p)) {
            case "TRANSFER_PREPARE" -> transfers.onPrepare(sw, p);
            case "TRANSFER_FULFIL" -> transfers.onFulfil(sw, p);
            case "TRANSFER_ERROR" -> transfers.onError(sw, p);
            default -> throw ApiException.unprocessable("unsupported_callback", "Unsupported transfer callback " + type(p));
        });
    }

    private static String type(JsonNode p) {
        return p.path("type").asString("");
    }

    private ObjectNode handle(UUID tenantId, JsonNode body, String signature, BiFunction<ObjectNode, JsonNode, ObjectNode> work) {
        String code = Rows.str(body, "switch_code");
        JsonNode payload = body.get("payload");
        if (code == null || payload == null || !payload.isObject()) {
            throw ApiException.badRequest("invalid_callback", "switch_code and a payload object are required");
        }
        if (!InteropCrypto.verifySignature(secret(code), body, signature)) {
            throw new ApiException(401, "invalid_signature", "Missing or invalid " + HEADER + " for switch " + code);
        }
        try {
            return TenantContext.callAs(tenantId, "switch:" + code, () -> {
                ObjectNode sw = rows.queryOne("select row_to_json(s)::text from interop_switch s where code = ?", code)
                        .orElseThrow(() -> ApiException.notFound("interop switch", code));
                if (!Rows.bool(sw, "is_active")) {
                    throw ApiException.unprocessable("switch_inactive", "Switch " + code + " is not active");
                }
                return work.apply(sw, payload);
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String secret(String code) {
        String s = env.getProperty(SECRET_PREFIX + code);
        if (s == null) {
            s = env.getProperty(SECRET_PREFIX + code.toLowerCase(Locale.ROOT));
        }
        return s;
    }
}
