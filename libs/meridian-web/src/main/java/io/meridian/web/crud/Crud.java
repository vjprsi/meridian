package io.meridian.web.crud;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Static access point used by generated API interfaces (default methods cannot hold injected fields).
 * The engine is registered once at startup by {@link CrudEngine}'s bean initialisation.
 */
public final class Crud {
    private static volatile CrudEngine engine;

    private Crud() {}

    static void register(CrudEngine e) {
        engine = e;
    }

    public static CrudEngine engine() {
        CrudEngine e = engine;
        if (e == null) {
            throw new IllegalStateException("CrudEngine not initialised");
        }
        return e;
    }

    public static Params params() {
        return new Params();
    }

    /** Request parameters (path + query) in declaration order; null values are ignored. */
    public static final class Params {
        private final Map<String, Object> values = new LinkedHashMap<>();

        public Params with(String name, Object value) {
            if (value != null) {
                values.put(name, value);
            }
            return this;
        }

        public Map<String, Object> asMap() {
            return values;
        }
    }
}
