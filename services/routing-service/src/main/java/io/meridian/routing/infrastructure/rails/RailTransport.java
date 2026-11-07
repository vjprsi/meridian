package io.meridian.routing.infrastructure.rails;

import java.math.BigDecimal;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * A protocol adapter that speaks one rail's participant API over the network. One bean per protocol; a rail's
 * {@code rail_adapter} rows (one per access node / connectivity provider) name the protocol in
 * {@code config.protocol} and are ranked by {@code TransportService} for smart routing and failover.
 */
public interface RailTransport {

    /** Protocol id carried in {@code rail_adapter.config.protocol}, e.g. {@code BREB_JSON_V1}. */
    String protocol();

    /** One access node of a rail: a {@code rail_adapter} row. */
    record Node(UUID adapterId, String name, String endpoint, String participantId, JsonNode config) {}

    /** ANSWERED = the rail gave a pacs.002; TIMEOUT = sent, no answer in time; UNREACHABLE = not delivered. */
    enum Kind { ANSWERED, UNREACHABLE, TIMEOUT, NOT_FOUND }

    /** Result of one exchange with one node; status (ACCP / RJCT), reason and clearing ref only when ANSWERED. */
    record Outcome(Kind kind, String status, String reasonCode, String reason, String clearingRef, String raw,
                   long latencyMs, String detail) {
        public boolean answered() {
            return kind == Kind.ANSWERED;
        }
    }

    /** What the payer's institution sends (pacs.008 content). creditorAccountType PROXY = an instant-payment key. */
    record OutboundTransfer(String messageId, String endToEndId, BigDecimal amount, String currency, String debtorName,
                            String debtorAccount, String creditorName, String creditorAccount, String creditorAccountType,
                            String creditorAgentClearingId, String remittance, String purpose) {}

    /** A directory (key / alias) exchange: HTTP-like status (0 = node unreachable) and the answer. */
    record DirectoryResult(int status, JsonNode body, String detail) {}

    Outcome send(Node node, OutboundTransfer transfer);

    /** pacs.028 status query for an end-to-end id. */
    Outcome query(Node node, String endToEndId);

    /** op = RESOLVE, REGISTER or DELETE. */
    DirectoryResult directory(Node node, String op, String keyType, String keyValue, JsonNode body);
}
