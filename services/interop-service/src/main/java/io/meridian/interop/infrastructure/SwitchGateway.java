package io.meridian.interop.infrastructure;

import java.math.BigDecimal;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Port to a switch / hub. Calls are FSPIOP-style: directory, party lookup and quotes are answered synchronously by
 * this port; a transfer prepare is fire-and-forget and the switch answers through the public transfer callback.
 */
public interface SwitchGateway {

    record Participant(String fspId, String name, String participantType, String currency) {}

    record Party(boolean found, String partyType, String partyId, String fspId, String name, String currency) {}

    record Quote(BigDecimal fee, BigDecimal payeeReceiveAmount, String ilpCondition, String payeeName) {}

    /** The participant directory of the switch (our own participant excluded). */
    List<Participant> directory(JsonNode sw);

    /** Resolves a party among the given candidate participants (active, supporting lookup). */
    Party lookup(JsonNode sw, String partyType, String partyId, List<Participant> candidates);

    /** Requests a quote for an outbound transfer. */
    Quote quote(JsonNode sw, String quoteId, String payeeFspId, String partyType, String partyId, BigDecimal amount,
                String currency);

    /** Sends the transfer prepare; the result arrives later through the transfer callback. */
    void prepare(JsonNode sw, String transferId, String quoteId, String payeeFspId, BigDecimal amount, String currency,
                 String ilpCondition);
}
