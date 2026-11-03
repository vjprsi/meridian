package io.meridian.commission.application;

import io.meridian.commission.infrastructure.Rows;
import io.meridian.common.time.Clocks;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * WE-PRICE-1 (PE-13): deferred inactive-customer rewards. A reward with trigger AFTER_DURATION is released by the EOD
 * {@code release-due} when its date has passed; one with trigger AFTER_FIRST_DEBIT is released when the credited
 * customer later makes their first debit. Release turns the pending row into a commission earning (idempotent: a row
 * is released at most once, guarded by its status under a lock).
 */
@Service
public class PendingRewardService {
    private static final Logger log = LoggerFactory.getLogger(PendingRewardService.class);
    static final String PENDING = "pending_commission_reward";
    static final String EARNING = "commission_earning";

    private final Rows rows;
    private final Events events;
    private final TransactionTemplate tx;

    public PendingRewardService(Rows rows, Events events, TransactionTemplate tx) {
        this.rows = rows;
        this.events = events;
        this.tx = tx;
    }

    /** Releases every AFTER_DURATION reward due on or before the business date. Idempotent per business date. */
    public ObjectNode releaseDue(LocalDate businessDate) {
        List<ObjectNode> due = rows.query("""
                select row_to_json(p)::text from pending_commission_reward p
                where p.status = 'PENDING' and p.reward_trigger = 'AFTER_DURATION'
                  and p.release_due_on is not null and p.release_due_on <= ?
                order by p.release_due_on, p.id limit 1000""", businessDate);
        return releaseAll(due, "business_date", businessDate.toString());
    }

    /** Releases every AFTER_FIRST_DEBIT reward held for a customer (called when that customer first debits). */
    public void releaseOnFirstDebit(UUID customerWalletId) {
        if (customerWalletId == null) {
            return;
        }
        List<ObjectNode> due = rows.query("""
                select row_to_json(p)::text from pending_commission_reward p
                where p.status = 'PENDING' and p.reward_trigger = 'AFTER_FIRST_DEBIT' and p.customer_wallet_id = ?
                order by p.id""", customerWalletId);
        releaseAll(due, null, null);
    }

    private ObjectNode releaseAll(List<ObjectNode> due, String keyName, String keyValue) {
        int released = 0;
        List<String> failed = new ArrayList<>();
        for (ObjectNode p : due) {
            try {
                if (release(Rows.uuid(p, "id"))) {
                    released++;
                }
            } catch (RuntimeException e) {
                failed.add(Rows.str(p, "id"));
                log.warn("could not release pending reward {}: {}", Rows.str(p, "id"), e.getMessage());
            }
        }
        ObjectNode out = rows.object();
        if (keyName != null) {
            out.put(keyName, keyValue);
        }
        out.put("released", released);
        ArrayNode arr = out.putArray("failed_ids");
        failed.forEach(arr::add);
        return out;
    }

    /** Releases one pending reward into an earning under its row lock (returns false if already released). */
    private boolean release(UUID id) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            ObjectNode p = rows.lock(PENDING, id);
            if (!"PENDING".equals(Rows.str(p, "status"))) {
                return false;
            }
            ObjectNode v = rows.object();
            for (String f : List.of("commission_plan_id", "commission_rule_id", "wallet_transaction_id", "beneficiary",
                    "beneficiary_agent_id", "beneficiary_wallet_id", "base_amount", "commission_amount", "tax_amount",
                    "net_amount", "currency", "payout_frequency")) {
                if (p.hasNonNull(f)) {
                    v.set(f, p.get(f));
                }
            }
            v.put("is_clawback", false);
            v.put("earned_at", Clocks.now().toString());
            v.put("payout_due_on", rows.today().toString());
            ObjectNode earning = rows.insert(EARNING, v);
            events.publish("wallets.commission_earning.accrued", EARNING, earning);
            ObjectNode u = rows.object();
            u.put("status", "RELEASED");
            u.put("released_earning_id", Rows.str(earning, "id"));
            u.put("released_at", Clocks.now().toString());
            ObjectNode after = rows.update(PENDING, id, u);
            events.publish("wallets.pending_commission_reward.released", PENDING, after);
            return true;
        }));
    }
}
