package io.meridian.workflow.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Helpers shared by the controllers. */
final class Actions {
    private Actions() {}

    /**
     * Idempotency fingerprint of an action: operation + target + body, so reusing a key for another action or
     * another resource is rejected instead of replaying an unrelated response.
     */
    static Map<String, Object> fingerprint(String op, UUID id, Object body) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op", op);
        m.put("id", id == null ? null : id.toString());
        m.put("body", body);
        return m;
    }
}
