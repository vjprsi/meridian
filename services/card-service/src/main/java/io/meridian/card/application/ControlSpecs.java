package io.meridian.card.application;

import io.meridian.card.domain.ControlRules;
import io.meridian.card.infrastructure.Rows;
import io.meridian.common.error.ApiException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Maps control JSON (API shape) onto the pure {@link ControlRules.ControlSpec}. */
final class ControlSpecs {
    private ControlSpecs() {}

    static ControlRules.ControlSpec from(JsonNode n) {
        if (n == null || !n.isObject()) {
            throw ApiException.unprocessable("invalid_control", "a control must be a JSON object");
        }
        List<String> values = new ArrayList<>();
        JsonNode v = n.get("values");
        if (v != null && !v.isNull()) {
            if (!v.isArray()) {
                throw ApiException.unprocessable("invalid_control_values", "values must be an array");
            }
            v.forEach(x -> values.add(x.isNull() ? null : x.asString()));
        }
        ControlRules.TimeWindow tw = null;
        JsonNode w = n.get("time_window");
        if (w != null && w.isObject()) {
            List<String> days = new ArrayList<>();
            if (w.get("days") != null && w.get("days").isArray()) {
                w.get("days").forEach(d -> days.add(d.asString()));
            }
            tw = new ControlRules.TimeWindow(days, Rows.str(w, "start"), Rows.str(w, "end"));
        }
        return new ControlRules.ControlSpec(Rows.str(n, "control_type"), Rows.str(n, "channel"), values,
                Rows.dec(n, "max_amount"), Rows.str(n, "currency"), tw, Rows.str(n, "timezone"),
                ts(n, "valid_from"), ts(n, "valid_to"));
    }

    private static OffsetDateTime ts(JsonNode n, String f) {
        try {
            return Rows.ts(n, f);
        } catch (DateTimeParseException e) {
            throw ApiException.unprocessable("invalid_timestamp", f + " must be an ISO-8601 timestamp");
        }
    }
}
