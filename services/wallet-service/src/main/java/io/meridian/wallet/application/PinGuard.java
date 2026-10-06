package io.meridian.wallet.application;

import io.meridian.common.error.ApiException;
import io.meridian.wallet.config.WalletProperties;
import io.meridian.wallet.domain.PinPolicy;
import io.meridian.wallet.infrastructure.Rows;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.ObjectNode;

/**
 * PIN verification with lockout. Each check runs in its own transaction so a failed attempt is counted even when the
 * calling business action is rejected afterwards; callers must therefore not hold a lock on the wallet row.
 */
@Service
public class PinGuard {
    private final Rows rows;
    private final TransactionTemplate tx;
    private final WalletProperties props;
    private final Events events;

    public PinGuard(Rows rows, TransactionTemplate tx, WalletProperties props, Events events) {
        this.rows = rows;
        this.tx = tx;
        this.props = props;
        this.events = events;
    }

    /** Result of a verification: valid, remaining attempts and the lock deadline when locked. */
    public record Outcome(boolean valid, int remainingAttempts, OffsetDateTime lockedUntil, String code) {
        public Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("valid", valid);
            m.put("remaining_attempts", remainingAttempts);
            m.put("locked_until", lockedUntil == null ? null : lockedUntil.toString());
            if (code != null) {
                m.put("code", code);
            }
            return m;
        }
    }

    /** Verifies and records the attempt; never throws for a wrong PIN (see {@link #require}). */
    public Outcome verify(UUID walletId, String pin) {
        return tx.execute(s -> {
            ObjectNode w = rows.lock("wallet", walletId);
            int max = props.pin().maxAttempts();
            Boolean locked = rows.scalar("select pin_locked_until is not null and pin_locked_until > now() from wallet where id = ?",
                    Boolean.class, walletId).orElse(false);
            if (locked) {
                return new Outcome(false, 0, Rows.ts(w, "pin_locked_until"), "pin_locked");
            }
            if (!Rows.bool(w, "has_pin") || Rows.str(w, "pin_hash") == null) {
                return new Outcome(false, max, null, "pin_not_set");
            }
            int attempts = Rows.integer(w, "pin_failed_attempts") == null ? 0 : Rows.integer(w, "pin_failed_attempts");
            if (PinPolicy.verify(pin, Rows.str(w, "pin_hash"))) {
                if (attempts > 0 || w.hasNonNull("pin_locked_until")) {
                    rows.execute("update wallet set pin_failed_attempts = 0, pin_locked_until = null where id = ?", walletId);
                }
                return new Outcome(true, max, null, null);
            }
            PinPolicy.Failure f = PinPolicy.Failure.after(attempts, max);
            if (f.locked()) {
                rows.execute("""
                        update wallet set pin_failed_attempts = ?, pin_locked_until = now() + make_interval(mins => ?)
                        where id = ?""", f.attempts(), props.pin().lockMinutes(), walletId);
                ObjectNode after = rows.get("wallet", walletId);
                events.publish("wallets.wallet.pin_locked", "wallet", after);
                return new Outcome(false, 0, Rows.ts(after, "pin_locked_until"), "pin_locked");
            }
            rows.execute("update wallet set pin_failed_attempts = ? where id = ?", f.attempts(), walletId);
            return new Outcome(false, f.remaining(), null, "invalid_pin");
        });
    }

    /** Verifies the PIN and throws 422 {@code invalid_pin} / {@code pin_locked} / {@code pin_not_set} when it fails. */
    public void require(UUID walletId, String pin) {
        Outcome o = verify(walletId, pin);
        if (!o.valid()) {
            throw switch (o.code()) {
                case "pin_locked" -> ApiException.unprocessable("pin_locked",
                        "The wallet PIN is locked until " + o.lockedUntil() + " after too many failed attempts");
                case "pin_not_set" -> ApiException.unprocessable("pin_not_set", "The wallet has no PIN; set one first");
                default -> ApiException.unprocessable("invalid_pin",
                        "Incorrect PIN; " + o.remainingAttempts() + " attempt(s) left before the PIN locks");
            };
        }
    }
}
