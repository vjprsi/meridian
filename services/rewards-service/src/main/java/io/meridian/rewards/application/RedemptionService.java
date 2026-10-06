package io.meridian.rewards.application;

import static io.meridian.rewards.application.Json.text;

import io.meridian.common.error.ApiException;
import io.meridian.rewards.api.generated.model.Redemption;
import io.meridian.rewards.domain.Lifecycles;
import io.meridian.rewards.infrastructure.ExternalServices;
import io.meridian.rewards.infrastructure.Rows;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

/**
 * Redemptions: points are debited (REDEEM) and stock reserved when the redemption is requested. Statement credits
 * and cash transfers post to the ledger immediately (FULFILLED, or FAILED with the ledger's reason and retryable);
 * vouchers are issued at once; partner items (miles, gift cards, merchandise, donations) stay PROCESSING until the
 * partner confirms or fails. Cancelling restores the points (REVERSE) and the stock.
 */
@Service
public class RedemptionService {
    static final String T = "redemption";
    private static final Set<String> CASH_ITEMS = Set.of("STATEMENT_CREDIT", "CASH_TRANSFER");

    private final Rows rows;
    private final Patcher patcher;
    private final PointsLedger ledger;
    private final ExternalServices external;
    private final Events events;

    public RedemptionService(Rows rows, Patcher patcher, PointsLedger ledger, ExternalServices external, Events events) {
        this.rows = rows;
        this.patcher = patcher;
        this.ledger = ledger;
        this.external = external;
        this.events = events;
    }

    @Transactional
    public ObjectNode create(Object body, String idempotencyKey) {
        ObjectNode v = patcher.columns(T, body);
        Optional<ObjectNode> prior = rows.queryOne("select row_to_json(t)::text from redemption t where t.idempotency_key = ?", idempotencyKey);
        if (prior.isPresent()) {
            return prior.get();
        }
        UUID accountId = Json.uuid(v, "points_account_id");
        ObjectNode account = rows.queryOne("select row_to_json(t)::text from points_account t where t.id = ? for update", accountId)
                .orElseThrow(() -> ApiException.unprocessable("account_not_found", "Points account " + accountId + " does not exist"));
        AccountService.requireActive(account);
        UUID itemId = Json.uuid(v, "reward_catalog_item_id");
        ObjectNode item = rows.queryOne("select row_to_json(t)::text from reward_catalog_item t where t.id = ? for update", itemId)
                .orElseThrow(() -> ApiException.unprocessable("item_not_found", "Catalogue item " + itemId + " does not exist"));
        if (!text(item, "rewards_program_id").equals(text(account, "rewards_program_id"))) {
            throw ApiException.unprocessable("program_mismatch", "The item belongs to another program");
        }
        if (!item.path("is_active").asBoolean()) {
            throw ApiException.unprocessable("item_inactive", "The item is not available for redemption");
        }
        ObjectNode program = rows.get("rewards_program", Json.uuid(account, "rewards_program_id"));
        if (!"ACTIVE".equals(text(program, "status"))) {
            throw ApiException.unprocessable("program_not_active", "The program is " + text(program, "status"));
        }
        int quantity = Json.integer(v, "quantity", 1);
        if (quantity < 1 || quantity > 100) {
            throw ApiException.unprocessable("invalid_quantity", "quantity must be 1-100");
        }
        if (item.hasNonNull("stock") && item.get("stock").asInt() < quantity) {
            throw ApiException.unprocessable("out_of_stock", "Only " + item.get("stock").asInt() + " left in stock");
        }
        if (item.hasNonNull("min_tier_rank")) {
            int rank = Json.uuid(account, "reward_tier_id") == null ? 0
                    : rows.find("reward_tier", Json.uuid(account, "reward_tier_id")).map(t -> t.path("rank").asInt()).orElse(0);
            if (rank < item.get("min_tier_rank").asInt()) {
                throw ApiException.unprocessable("tier_too_low", "The item requires tier rank " + item.get("min_tier_rank").asInt());
            }
        }
        String type = text(item, "item_type");
        if ("PARTNER_MILES".equals(type) && text(v, "partner_member_id") == null) {
            throw ApiException.unprocessable("partner_member_required", "partner_member_id is required for partner miles");
        }
        UUID credit = Json.uuid(v, "credit_account_id") != null ? Json.uuid(v, "credit_account_id")
                : Json.uuid(account, "default_credit_account_id");
        if (CASH_ITEMS.contains(type) && credit == null) {
            throw ApiException.unprocessable("no_credit_account", "A credit_account_id is required for " + type);
        }
        long cost = Math.multiplyExact(item.path("points_cost").asLong(), quantity);
        BigDecimal cash = Json.decimal(item, "cash_value") == null ? null
                : Json.decimal(item, "cash_value").multiply(BigDecimal.valueOf(quantity));

        v.put("quantity", quantity);
        v.put("points_spent", cost);
        if (cash != null) {
            v.put("cash_value", cash.toPlainString());
            v.put("currency", text(item, "currency"));
        }
        if (credit != null) {
            v.put("credit_account_id", credit.toString());
        }
        v.put("status", "REQUESTED");
        v.put("idempotency_key", idempotencyKey);
        ObjectNode row = rows.insert(T, v);
        UUID id = Json.uuid(row, "id");
        ObjectNode entry = ledger.append(ledger.lockAccount(accountId), new PointsLedger.Move("REDEEM", -cost, "redeem:" + id)
                .source("REDEMPTION", id).event("engagement.points.redeemed"));
        if (item.hasNonNull("stock")) {
            rows.execute("update reward_catalog_item set stock = stock - ? where id = ?", quantity, itemId);
        }
        ObjectNode u = rows.object();
        u.put("points_ledger_entry_id", text(entry, "id"));
        row = rows.update(T, id, u);
        events.publish("engagement.redemption.requested", T, row, Redemption.class);
        return fulfilAutomatically(row, item, program);
    }

    private ObjectNode fulfilAutomatically(ObjectNode row, ObjectNode item, ObjectNode program) {
        UUID id = Json.uuid(row, "id");
        String type = text(item, "item_type");
        ObjectNode v = rows.object();
        if (CASH_ITEMS.contains(type)) {
            try {
                ExternalServices.CustomerAccount acct = external.customerAccount(Json.uuid(row, "credit_account_id"));
                if (acct.currency() != null && !acct.currency().equals(text(row, "currency"))) {
                    throw ApiException.unprocessable("currency_mismatch", "The credit account is in " + acct.currency());
                }
                UUID journal = external.payout(Json.uuid(program, "ledger_id"), Json.uuid(program, "funding_ledger_account_id"),
                        acct.ledgerAccountId(), new BigDecimal(text(row, "cash_value")), text(row, "currency"), "REDEMPTION", id,
                        Json.truncate("Points redemption " + text(item, "name"), 500), "rewards-redemption:" + id);
                v.put("journal_entry_id", journal.toString());
                return finish(id, v, "FULFILLED", null);
            } catch (ApiException e) {
                return finish(id, v, "FAILED", e.code() + ": " + e.getMessage());
            }
        }
        if ("VOUCHER".equals(type)) {
            v.put("voucher_code", Lifecycles.code(4) + "-" + Lifecycles.code(4) + "-" + Lifecycles.code(4));
            v.put("partner_reference", "VCH-" + id);
            return finish(id, v, "FULFILLED", null);
        }
        v.put("status", "PROCESSING");
        return rows.update(T, id, v);
    }

    private ObjectNode finish(UUID id, ObjectNode v, String status, String failure) {
        v.put("status", status);
        if ("FULFILLED".equals(status)) {
            v.put("fulfilled_at", rows.now().toString());
            v.putNull("failure_reason");
        } else {
            v.put("failure_reason", Json.truncate(failure, 255));
        }
        ObjectNode row = rows.update(T, id, v);
        events.publish("FULFILLED".equals(status) ? "engagement.redemption.fulfilled" : "engagement.redemption.failed",
                T, row, Redemption.class);
        return row;
    }

    @Transactional
    public ObjectNode cancel(UUID id, String reason) {
        ObjectNode r = rows.lock(T, id);
        String to = Lifecycles.REDEMPTION.apply("cancel", text(r, "status"));
        UUID accountId = Json.uuid(r, "points_account_id");
        ObjectNode account = ledger.lockAccount(accountId);
        ObjectNode program = rows.get("rewards_program", Json.uuid(account, "rewards_program_id"));
        long points = r.path("points_spent").asLong();
        ledger.append(account, new PointsLedger.Move("REVERSE", points, "redeem-cancel:" + id)
                .reverses(Json.uuid(r, "points_ledger_entry_id")).source("REDEMPTION", id).reason(reason)
                .expires(PointsLedger.expiry(program, rows.now().toLocalDate())).event("engagement.points.reversed"));
        rows.execute("update reward_catalog_item set stock = stock + ? where id = ? and stock is not null",
                r.path("quantity").asInt(1), Json.uuid(r, "reward_catalog_item_id"));
        ObjectNode v = rows.object();
        v.put("status", to);
        v.put("cancelled_at", rows.now().toString());
        v.put("cancel_reason", Json.truncate(reason, 255));
        ObjectNode row = rows.update(T, id, v);
        events.publish("engagement.redemption.cancelled", T, row, Redemption.class);
        return row;
    }

    @Transactional
    public ObjectNode retry(UUID id) {
        ObjectNode r = rows.lock(T, id);
        Lifecycles.REDEMPTION.apply("retry", text(r, "status"));
        ObjectNode item = rows.get("reward_catalog_item", Json.uuid(r, "reward_catalog_item_id"));
        ObjectNode account = rows.get("points_account", Json.uuid(r, "points_account_id"));
        ObjectNode program = rows.get("rewards_program", Json.uuid(account, "rewards_program_id"));
        return fulfilAutomatically(r, item, program);
    }

    @Transactional
    public ObjectNode fulfil(UUID id, String partnerReference) {
        ObjectNode r = rows.lock(T, id);
        String to = Lifecycles.REDEMPTION.apply("fulfil", text(r, "status"));
        ObjectNode v = rows.object();
        v.put("partner_reference", Json.truncate(partnerReference, 128));
        return finish(id, v, to, null);
    }

    @Transactional
    public ObjectNode fail(UUID id, String reason) {
        ObjectNode r = rows.lock(T, id);
        String to = Lifecycles.REDEMPTION.apply("fail", text(r, "status"));
        return finish(id, rows.object(), to, reason);
    }
}
