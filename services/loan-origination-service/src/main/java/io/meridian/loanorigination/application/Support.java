package io.meridian.loanorigination.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.loanorigination.domain.Lifecycles;
import io.meridian.loanorigination.infrastructure.Rows;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;

/** Shared helpers of the origination use cases: application lookups, guarded transitions, the database clock. */
@Component
public class Support {
    public static final String APP = "loan_application";

    private final Rows rows;
    private final Events events;

    public Support(Rows rows, Events events) {
        this.rows = rows;
        this.events = events;
    }

    public Rows rows() {
        return rows;
    }

    public Events events() {
        return events;
    }

    public ObjectNode lockApp(UUID id) {
        return rows.lock(APP, id);
    }

    public ObjectNode app(UUID id) {
        return rows.get(APP, id);
    }

    /** Current time of the database (the single clock for validity windows compared in SQL). */
    public OffsetDateTime dbNow() {
        return rows.scalar("select now()", OffsetDateTime.class).orElseThrow();
    }

    public LocalDate dbToday() {
        return rows.scalar("select (now() at time zone 'UTC')::date", LocalDate.class).orElseThrow();
    }

    /**
     * Moves an (locked) application to {@code to} with the extra column values, publishing status_changed; a no-op
     * status (same value) only applies the extra values.
     */
    public ObjectNode transition(ObjectNode app, String to, ObjectNode extra) {
        String from = Rows.str(app, "status");
        ObjectNode values = extra == null ? rows.object() : extra;
        if (!to.equals(from)) {
            require(Lifecycles.APPLICATION, "Loan application", from, to);
            values.put("status", to);
        }
        if (values.isEmpty()) {
            return app;
        }
        ObjectNode updated = rows.update(APP, Rows.uuid(app, "id"), values);
        if (!to.equals(from)) {
            events.publish("lending.loan_application.status_changed", APP, updated);
        }
        return updated;
    }

    public ObjectNode transition(ObjectNode app, String to) {
        return transition(app, to, null);
    }

    public static void require(Map<String, Set<String>> machine, String what, String from, String to) {
        try {
            Lifecycles.require(machine, what, from, to);
        } catch (Lifecycles.InvalidTransition e) {
            throw ApiException.conflict("invalid_transition", e.getMessage());
        }
    }

    public static void requireStatus(ObjectNode row, Set<String> allowed, String what, String code) {
        String s = Rows.str(row, "status");
        if (!allowed.contains(s)) {
            throw ApiException.conflict(code, what + " is " + s + "; allowed in " + String.join(", ", new java.util.TreeSet<>(allowed)));
        }
    }

    /** Four-eyes: the checker must not be the maker. */
    public static void fourEyes(String maker, String what) {
        io.meridian.common.tenancy.FourEyes.require(maker, TenantContext.principal(),
                what + " must be approved by a different user than " + maker);
    }

    /** The application's PRIMARY applicant (null when none yet). */
    public ObjectNode primaryApplicant(UUID appId) {
        return rows.queryOne("""
                select row_to_json(a)::text from loan_applicant a where loan_application_id = ? and role = 'PRIMARY'
                order by created_at limit 1""", appId).orElse(null);
    }
}
