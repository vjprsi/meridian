package io.meridian.rewards.application;

import static io.meridian.rewards.application.Json.text;

import io.meridian.common.error.ApiException;
import io.meridian.rewards.api.generated.model.PointsLedgerEntry;
import io.meridian.rewards.domain.PointsLots;
import io.meridian.rewards.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;

/**
 * The append-only points ledger. Every movement locks the points account row, appends one immutable entry (exactly
 * once per idempotency key) and refreshes the account's cached balances: available (never negative), pending,
 * lifetime totals, and the FIFO-derived expiring points and next expiry date.
 */
@Component
public class PointsLedger {
    static final String T = "points_ledger_entry";
    static final int EXPIRY_HORIZON_DAYS = 30;

    private final Rows rows;
    private final Events events;

    public PointsLedger(Rows rows, Events events) {
        this.rows = rows;
        this.events = events;
    }

    /** A new ledger entry; only type, points and key are required. */
    public static final class Move {
        final String type;
        final long points;
        final String key;
        boolean pending;
        OffsetDateTime confirmAfter;
        UUID confirmsId;
        UUID lotId;
        UUID ruleId;
        UUID transactionId;
        BigDecimal transactionAmount;
        String currency;
        String sourceType;
        UUID sourceId;
        UUID counterparty;
        LocalDate expiresOn;
        UUID reversalOf;
        String reason;
        String event;

        public Move(String type, long points, String key) {
            this.type = type;
            this.points = points;
            this.key = key;
        }

        public Move pending(OffsetDateTime confirmAfter) {
            this.pending = true;
            this.confirmAfter = confirmAfter;
            return this;
        }

        public Move confirms(UUID id) {
            this.confirmsId = id;
            return this;
        }

        public Move lot(UUID id) {
            this.lotId = id;
            return this;
        }

        public Move rule(UUID id) {
            this.ruleId = id;
            return this;
        }

        public Move transaction(UUID id, BigDecimal amount, String currency) {
            this.transactionId = id;
            this.transactionAmount = amount;
            this.currency = currency;
            return this;
        }

        public Move source(String type, UUID id) {
            this.sourceType = type;
            this.sourceId = id;
            return this;
        }

        public Move counterparty(UUID id) {
            this.counterparty = id;
            return this;
        }

        public Move expires(LocalDate d) {
            this.expiresOn = d;
            return this;
        }

        public Move reverses(UUID id) {
            this.reversalOf = id;
            return this;
        }

        public Move reason(String r) {
            this.reason = Json.truncate(r, 255);
            return this;
        }

        /** Event published for the entry (default: none). */
        public Move event(String e) {
            this.event = e;
            return this;
        }
    }

    public ObjectNode lockAccount(UUID id) {
        return rows.lock("points_account", id);
    }

    public Optional<ObjectNode> byKey(String key) {
        return rows.queryOne("select row_to_json(t)::text from points_ledger_entry t where t.idempotency_key = ?", key);
    }

    /**
     * Appends one entry to a locked account (replays the existing entry for a known key) and returns it.
     * 422 {@code insufficient_points} when a debit would make the available balance negative.
     */
    public ObjectNode append(ObjectNode account, Move m) {
        Optional<ObjectNode> existing = byKey(m.key);
        if (existing.isPresent()) {
            return existing.get();
        }
        UUID accountId = Json.uuid(account, "id");
        long available = account.path("available_points").asLong();
        long pending = account.path("pending_points").asLong();
        long earned = account.path("lifetime_earned").asLong();
        long redeemed = account.path("lifetime_redeemed").asLong();
        if (m.pending) {
            pending += m.points;
        } else {
            available += m.points;
            if (m.confirmsId != null) {
                pending -= m.points;
            }
            switch (m.type) {
                case "EARN", "BONUS" -> earned += Math.max(0, m.points);
                case "REDEEM" -> redeemed += -m.points;
                case "REVERSE" -> {
                    if ("REDEMPTION".equals(m.sourceType)) {
                        redeemed -= m.points;
                    } else if (m.points < 0) {
                        earned += m.points;
                    }
                }
                default -> { }
            }
        }
        if (available < 0) {
            throw ApiException.unprocessable("insufficient_points",
                    "Only " + account.path("available_points").asLong() + " points are available");
        }
        if (pending < 0) {
            pending = 0;
        }
        ObjectNode e = rows.object();
        e.put("points_account_id", accountId.toString());
        e.put("entry_type", m.type);
        e.put("points", m.points);
        e.put("balance_after", available);
        e.put("is_pending", m.pending);
        put(e, "confirm_after", m.confirmAfter);
        put(e, "confirms_id", m.confirmsId);
        put(e, "lot_id", m.lotId);
        put(e, "reward_rule_id", m.ruleId);
        put(e, "reward_transaction_id", m.transactionId);
        if (m.transactionAmount != null) {
            e.put("transaction_amount", m.transactionAmount.toPlainString());
        }
        put(e, "currency", m.currency);
        put(e, "source_type", m.sourceType);
        put(e, "source_id", m.sourceId);
        put(e, "counterparty_points_account_id", m.counterparty);
        put(e, "expires_on", m.expiresOn);
        put(e, "reversal_of_id", m.reversalOf);
        put(e, "reason", m.reason);
        e.put("idempotency_key", m.key);
        ObjectNode entry = rows.insert(T, e);
        ObjectNode upd = rows.object();
        upd.put("available_points", available);
        upd.put("pending_points", pending);
        upd.put("lifetime_earned", earned);
        upd.put("lifetime_redeemed", redeemed);
        ObjectNode updated = rows.update("points_account", accountId, upd);
        refreshExpiry(updated);
        account.setAll(rows.get("points_account", accountId));
        if (m.event != null) {
            events.publish(m.event, T, entry, PointsLedgerEntry.class);
        }
        return entry;
    }

    /** FIFO lots of an account, replayed from its ledger. */
    public PointsLots.State lots(UUID accountId) {
        List<PointsLots.Entry> entries = rows.jdbc().sql("""
                select e.id, e.entry_type, e.points, e.is_pending, coalesce(p.created_at, e.created_at)::date as earn_date,
                       e.expires_on, e.lot_id
                from points_ledger_entry e left join points_ledger_entry p on p.id = e.confirms_id
                where e.points_account_id = ? order by e.created_at, e.id""")
                .param(accountId)
                .query((rs, i) -> new PointsLots.Entry(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3),
                        rs.getBoolean(4), rs.getObject(5, LocalDate.class), rs.getObject(6, LocalDate.class),
                        rs.getObject(7, UUID.class)))
                .list();
        return PointsLots.replay(entries);
    }

    /** Recomputes expiring_points (next 30 days) and next_expiry_date from the lots. */
    public void refreshExpiry(ObjectNode account) {
        UUID id = Json.uuid(account, "id");
        LocalDate today = rows.today();
        PointsLots.State s = lots(id);
        ObjectNode upd = rows.object();
        upd.put("expiring_points", s.expiringBetween(today, today.plusDays(EXPIRY_HORIZON_DAYS)));
        LocalDate next = s.nextExpiry(today);
        if (next == null) {
            upd.putNull("next_expiry_date");
        } else {
            upd.put("next_expiry_date", next.toString());
        }
        rows.update("points_account", id, upd);
    }

    /** Expiry date of points earned on {@code earnDate} under the program (null = never). */
    public static LocalDate expiry(ObjectNode program, LocalDate earnDate) {
        Integer months = program.hasNonNull("points_expiry_months") ? program.get("points_expiry_months").asInt() : null;
        return months == null || months <= 0 ? null : earnDate.plusMonths(months);
    }

    private static void put(ObjectNode n, String f, Object v) {
        if (v != null) {
            n.put(f, v.toString());
        }
    }

    static String str(ObjectNode n, String f) {
        return text(n, f);
    }
}
