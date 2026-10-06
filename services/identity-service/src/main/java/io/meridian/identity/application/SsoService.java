package io.meridian.identity.application;

import io.meridian.identity.domain.SsoConfigCheck;
import io.meridian.identity.infrastructure.IdentityStore;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** SSO provider "test login": an offline configuration check (no call to the identity provider). */
@Service
public class SsoService {
    private final IdentityStore store;
    private final JsonMapper json;

    public SsoService(IdentityStore store, JsonMapper json) {
        this.store = store;
        this.json = json;
    }

    public JsonNode test(UUID id) {
        JsonNode p = store.require("sso_provider", id);
        Instant now = Instant.now();
        List<SsoConfigCheck.Check> checks = SsoConfigCheck.run(p.get("protocol").asString(), text(p, "issuer_url"),
                text(p, "metadata_url"), text(p, "client_id"), p.hasNonNull("client_secret"),
                text(p, "signing_certificate"), now);
        ObjectNode out = json.createObjectNode();
        out.put("provider_id", id.toString());
        out.put("protocol", p.get("protocol").asString());
        out.put("success", checks.stream().allMatch(SsoConfigCheck.Check::passed));
        ArrayNode arr = out.putArray("checks");
        checks.forEach(c -> arr.add(json.valueToTree(c.asMap())));
        out.put("tested_at", now.toString());
        out.put("mode", "offline_configuration_check");
        return out;
    }

    private static String text(JsonNode n, String f) {
        return n.hasNonNull(f) ? n.get(f).asString() : null;
    }
}
