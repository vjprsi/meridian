package io.meridian.webhook.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Response helpers shared by the controllers. */
final class Responses {
    private Responses() {}

    /** Idempotency fingerprint for bodiless actions: binds the key to the operation and target. */
    static Map<String, Object> op(String op, UUID id, Object body) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op", op);
        m.put("id", id.toString());
        if (body != null) {
            m.put("body", body);
        }
        return m;
    }
}
