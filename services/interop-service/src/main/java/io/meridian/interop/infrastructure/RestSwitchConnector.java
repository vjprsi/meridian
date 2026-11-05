package io.meridian.interop.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.web.outbound.OutboundHttp;
import io.meridian.web.outbound.OutboundUrlPolicy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * SCAFFOLD / TODO — a real REST switch connector for switches configured with an {@code endpoint_url}.
 *
 * <p>The biller side of this change ships a complete connector registry ({@code RoutingBillerGateway} + the
 * {@code connector} package): a {@code @Primary} facade that selects a real connector from routing metadata and
 * falls back to the simulator. Mirroring that for the interop switch is <b>not</b> low-risk, so it is deliberately
 * left as this documented scaffold rather than wired in:
 * <ul>
 *   <li>a Mojaloop/FSPIOP transfer is asynchronous — {@code prepare} is fire-and-forget and the result returns on the
 *       public transfer callback ({@code CallbackService}); a REST connector must therefore also authenticate and
 *       verify those inbound callbacks, not just make outbound calls;</li>
 *   <li>quotes carry an ILP condition/fulfilment ({@code InteropCrypto}); a real payee FSP computes the fulfilment,
 *       so the simulator's shared-secret shortcut cannot be reused and the condition handling must be revisited;</li>
 *   <li>FSPIOP uses specific media types, {@code FSPIOP-Source}/{@code -Destination}/{@code -Signature} headers and
 *       ISO 20022 variants that differ per deployment.</li>
 * </ul>
 *
 * <p>This class intentionally is <b>not</b> a Spring {@code @Component}: {@code SwitchService} injects a single
 * {@link SwitchGateway} (the {@link SimulatedSwitch}), so adding a second bean would need a {@code @Primary}
 * {@code RoutingSwitchGateway} facade (the biller pattern). It only demonstrates the safe, synchronous read path
 * (participant directory) over the shared, policy-checked {@link OutboundHttp}; the asynchronous paths throw the same
 * 502 the simulator raises for an endpoint without a bundled adapter.
 *
 * <p>TO ACTIVATE: add a {@code RoutingSwitchGateway implements SwitchGateway} marked {@code @Primary} that delegates
 * to this connector when {@code sw.endpoint_url} is set (and a protocol is configured) and to {@link SimulatedSwitch}
 * otherwise, make this class a {@code @Component}, and implement {@link #lookup}, {@link #quote} and {@link #prepare}
 * with the deployment's FSPIOP/ISO 20022 mapping plus inbound-callback verification.
 */
public final class RestSwitchConnector implements SwitchGateway {
    private final OutboundHttp http;
    private final JsonMapper json;

    public RestSwitchConnector(OutboundUrlPolicy policy, JsonMapper json) {
        this.http = new OutboundHttp(policy.with(policy.settings()
                .withTimeouts(Duration.ofSeconds(3), Duration.ofSeconds(15), Duration.ofSeconds(15))
                .withMaxResponseBytes(256 * 1024)));
        this.json = json;
    }

    @Override
    public List<Participant> directory(JsonNode sw) {
        String base = sw.path("endpoint_url").asString("");
        if (base.isBlank()) {
            throw new ApiException(502, "switch_adapter_unavailable", "No endpoint_url configured for this switch");
        }
        String own = sw.path("participant_id").asString("");
        String ccy = sw.path("currency").asString(null);
        try {
            OutboundHttp.Response r = http.get(trim(base) + "/participants", Map.of("Accept", "application/json"));
            if (r.status() >= 300 || r.truncated()) {
                throw new ApiException(502, "switch_adapter_unavailable", "Switch directory returned HTTP " + r.status());
            }
            JsonNode body = json.readTree(new String(r.body(), StandardCharsets.UTF_8));
            JsonNode items = body.isArray() ? body : body.path("participants");
            List<Participant> out = new ArrayList<>();
            for (JsonNode p : items) {
                String fspId = p.path("fspId").asString(p.path("fsp_id").asString(null));
                if (fspId == null || fspId.equals(own)) {
                    continue;
                }
                out.add(new Participant(fspId, p.path("name").asString(fspId),
                        p.path("participantType").asString(p.path("type").asString("WALLET")),
                        p.path("currency").asString(ccy)));
            }
            return out;
        } catch (OutboundHttp.Failure | OutboundUrlPolicy.RejectedException e) {
            throw new ApiException(502, "switch_adapter_unavailable", "Switch is not reachable: " + e.getMessage());
        }
    }

    @Override
    public Party lookup(JsonNode sw, String partyType, String partyId, List<Participant> candidates) {
        // TODO: FSPIOP GET /parties/{Type}/{Id}; map the resolved party / currency.
        throw new ApiException(502, "switch_adapter_unavailable",
                "No protocol adapter is configured for switch endpoint " + sw.path("endpoint_url").asString(""));
    }

    @Override
    public Quote quote(JsonNode sw, String quoteId, String payeeFspId, String partyType, String partyId,
                       BigDecimal amount, String currency) {
        // TODO: FSPIOP POST /quotes; return fee, payee receive amount, ILP condition (InteropCrypto), payee name.
        throw new ApiException(502, "switch_adapter_unavailable",
                "No protocol adapter is configured for switch endpoint " + sw.path("endpoint_url").asString(""));
    }

    @Override
    public void prepare(JsonNode sw, String transferId, String quoteId, String payeeFspId, BigDecimal amount,
                        String currency, String ilpCondition) {
        // TODO: FSPIOP POST /transfers (fire-and-forget); the switch answers on the public transfer callback.
        throw new ApiException(502, "switch_adapter_unavailable",
                "No protocol adapter is configured for switch endpoint " + sw.path("endpoint_url").asString(""));
    }

    private static String trim(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
