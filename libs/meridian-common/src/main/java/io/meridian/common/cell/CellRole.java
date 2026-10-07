package io.meridian.common.cell;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * MR-GAME-DAY: the runtime role of this service's cell, switchable without a restart.
 *
 * <p>A warm standby cell ({@code meridian.cell.role=standby}, Helm {@code global.cell.role: standby}) starts every
 * service with the outbox relay paused and the Kafka listeners stopped: its database is a read-only replica and its
 * topics are replicated from the primary, so relaying or consuming would publish / apply events twice. Promotion
 * ({@link #promote}) flips the role to {@code PRIMARY} in place; the libraries react through {@link #onChange}
 * listeners (meridian-events resumes the relay and starts the listener containers once the database accepts writes).
 * The switch is one-way in normal operation: demotion back to standby is an explicit operator action
 * ({@link #demote}), never automatic.
 *
 * <p>One instance per application context (a Spring bean); static state is avoided so tests stay isolated.
 */
public final class CellRole {

    public enum Role { PRIMARY, STANDBY }

    /** A role change: from, to, why, who, when. */
    public record Change(Role from, Role to, String reason, String actor, OffsetDateTime at) {}

    private final String cellId;
    private final List<Consumer<Change>> listeners = new CopyOnWriteArrayList<>();
    private volatile Role role;
    private volatile Change last;

    public CellRole(String cellId, Role initial) {
        this.cellId = cellId == null ? "" : cellId;
        this.role = initial == null ? Role.PRIMARY : initial;
    }

    /** {@code primary} (default, also blank) or {@code standby}; anything else is a configuration error. */
    public static Role parse(String value) {
        if (value == null || value.isBlank()) {
            return Role.PRIMARY;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "primary" -> Role.PRIMARY;
            case "standby" -> Role.STANDBY;
            default -> throw new IllegalArgumentException("meridian.cell.role must be primary or standby, not " + value);
        };
    }

    public String cellId() {
        return cellId;
    }

    public Role role() {
        return role;
    }

    /** True in a primary (serving, writable) cell: relays run, listeners consume, scheduled writers may write. */
    public boolean active() {
        return role == Role.PRIMARY;
    }

    public Change lastChange() {
        return last;
    }

    /** Registers a listener for role changes (called synchronously on the switching thread). */
    public void onChange(Consumer<Change> listener) {
        listeners.add(listener);
    }

    /** Standby -> primary. Idempotent: returns false (no listener called) when already primary. */
    public boolean promote(String reason, String actor) {
        return change(Role.PRIMARY, reason, actor);
    }

    /** Primary -> standby (failback preparation; operator only). Idempotent. */
    public boolean demote(String reason, String actor) {
        return change(Role.STANDBY, reason, actor);
    }

    private boolean change(Role to, String reason, String actor) {
        Change c;
        synchronized (this) {
            if (role == to) {
                return false;
            }
            c = new Change(role, to, reason, actor, io.meridian.common.time.Clocks.now());
            role = to;
            last = c;
        }
        for (Consumer<Change> l : listeners) {
            l.accept(c);
        }
        return true;
    }
}
