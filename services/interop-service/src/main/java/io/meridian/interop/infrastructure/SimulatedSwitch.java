package io.meridian.interop.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.interop.config.InteropProperties;
import io.meridian.interop.domain.InteropCrypto;
import io.meridian.interop.domain.InteropRules;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Built-in switch simulator, used for switches without an endpoint_url (sandbox, tests, demos). Deterministic:
 * a fixed directory; parties whose id ends in 0000 do not exist; the payee FSP's fulfilment for a quote is
 * HMAC(simulator secret, quote id), so a test harness acting as the switch can compute it. A switch with an
 * endpoint_url needs a protocol adapter (FSPIOP / ISO 20022) that is not bundled: such calls fail with 502.
 */
@Component
public class SimulatedSwitch implements SwitchGateway {
    static final List<Participant> DIRECTORY = List.of(
            new Participant("mtnmoney", "MTN Mobile Money", "WALLET", null),
            new Participant("airtelmoney", "Airtel Money", "WALLET", null),
            new Participant("equitybank", "Equity Bank", "BANK", null),
            new Participant("faulumfi", "Faulu Microfinance", "MFI", null));

    private final InteropProperties props;
    /** Prepares sent, for diagnostics and tests. */
    public final List<String> prepared = Collections.synchronizedList(new ArrayList<>());

    public SimulatedSwitch(InteropProperties props) {
        this.props = props;
    }

    /** The fulfilment the simulated payee FSP reveals for a quote. */
    public String fulfilmentFor(String quoteId) {
        return InteropCrypto.fulfilmentFrom(props.simulatorSecret(), quoteId);
    }

    private static void requireSimulated(JsonNode sw) {
        String url = sw.path("endpoint_url").asString("");
        if (!url.isBlank()) {
            throw new ApiException(502, "switch_adapter_unavailable",
                    "No protocol adapter is configured for switch endpoint " + url);
        }
    }

    @Override
    public List<Participant> directory(JsonNode sw) {
        requireSimulated(sw);
        String own = sw.path("participant_id").asString("");
        String ccy = sw.path("currency").asString(null);
        return DIRECTORY.stream().filter(p -> !p.fspId().equals(own))
                .map(p -> new Participant(p.fspId(), p.name(), p.participantType(), ccy)).toList();
    }

    @Override
    public Party lookup(JsonNode sw, String partyType, String partyId, List<Participant> candidates) {
        requireSimulated(sw);
        if (partyId.endsWith("0000") || candidates.isEmpty()) {
            return new Party(false, partyType, partyId, null, null, null);
        }
        Participant fsp = candidates.get(Math.floorMod(partyId.hashCode(), candidates.size()));
        String tail = partyId.length() > 4 ? partyId.substring(partyId.length() - 4) : partyId;
        return new Party(true, partyType, partyId, fsp.fspId(), "Party " + tail + " (" + fsp.name() + ")", fsp.currency());
    }

    @Override
    public Quote quote(JsonNode sw, String quoteId, String payeeFspId, String partyType, String partyId,
                       BigDecimal amount, String currency) {
        requireSimulated(sw);
        if (partyId.endsWith("0000")) {
            throw ApiException.unprocessable("party_not_found", "The payee is not registered at " + payeeFspId);
        }
        BigDecimal fee = InteropRules.fee(amount, decimal(sw, "fee_flat"), decimal(sw, "fee_percentage"));
        String tail = partyId.length() > 4 ? partyId.substring(partyId.length() - 4) : partyId;
        return new Quote(fee, amount, InteropCrypto.condition(fulfilmentFor(quoteId)), "Party " + tail);
    }

    @Override
    public void prepare(JsonNode sw, String transferId, String quoteId, String payeeFspId, BigDecimal amount,
                        String currency, String ilpCondition) {
        requireSimulated(sw);
        prepared.add(transferId);
    }

    private static BigDecimal decimal(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? BigDecimal.ZERO : new BigDecimal(v.asString());
    }
}
