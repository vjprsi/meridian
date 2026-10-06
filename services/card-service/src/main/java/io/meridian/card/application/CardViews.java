package io.meridian.card.application;

import io.meridian.card.infrastructure.Peers;
import io.meridian.card.infrastructure.Rows;
import io.meridian.common.error.ApiException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Read views of a card that combine card-service rows with other services (limits usage, authorisations). */
@Service
public class CardViews {
    private static final Logger log = LoggerFactory.getLogger(CardViews.class);

    private final Rows rows;
    private final Peers peers;
    private final ControlService controls;

    public CardViews(Rows rows, Peers peers, ControlService controls) {
        this.rows = rows;
        this.peers = peers;
        this.controls = controls;
    }

    /**
     * Spend limits of the card and its program; usage comes from card-authorization-service's counters (the
     * authoritative source, updated in the authorisation transaction). If that service is unreachable the stored
     * values are returned.
     */
    public ObjectNode limits(UUID cardId, Integer limit, String cursor) {
        ObjectNode page = controls.forCard("card_spend_limit", cardId, limit, cursor);
        Map<String, JsonNode> usage = new HashMap<>();
        try {
            for (JsonNode c : peers.usage(cardId).path("counters")) {
                usage.put(Rows.str(c, "limit_ref"), c);
            }
        } catch (RuntimeException e) {
            log.warn("limit usage unavailable for card {}: {}", cardId,
                    e instanceof ApiException a ? a.code() : e.getClass().getSimpleName());
        }
        for (JsonNode l : page.path("data")) {
            JsonNode u = usage.get(Rows.str(l, "id"));
            if (u != null) {
                ObjectNode o = (ObjectNode) l;
                o.put("used_amount", Rows.decOr0(u, "used_amount").toPlainString());
                o.put("used_count", Rows.integer(u, "used_count") == null ? 0 : Rows.integer(u, "used_count"));
                if (Rows.str(u, "period_start") != null) {
                    o.put("period_start", Rows.str(u, "period_start"));
                }
            }
        }
        return page;
    }

    public JsonNode authorizations(UUID cardId, Integer limit, String cursor) {
        rows.get("card", cardId);
        return peers.authorizations(cardId, limit, cursor);
    }
}
