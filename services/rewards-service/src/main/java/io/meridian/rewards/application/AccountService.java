package io.meridian.rewards.application;

import static io.meridian.rewards.application.Json.text;

import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.rewards.api.generated.model.PointsAccount;
import io.meridian.rewards.api.generated.model.PointsAdjustment;
import io.meridian.rewards.domain.Lifecycles;
import io.meridian.rewards.domain.PointsLots;
import io.meridian.rewards.domain.TierEvaluator;
import io.meridian.rewards.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Points accounts: enrolment, status, transfers, maker-checker adjustments, tiers and FIFO lots. */
@Service
public class AccountService {
    static final String T = "points_account";
    static final String ADJ = "points_adjustment";

    /** Approval resource type for manual points adjustments in workflow-service (G11). */
    public static final String APPROVAL_RESOURCE = "points_adjustment";

    private final Rows rows;
    private final Patcher patcher;
    private final PointsLedger ledger;
    private final Events events;
    private final Approvals approvals;

    public AccountService(Rows rows, Patcher patcher, PointsLedger ledger, Events events, Approvals approvals) {
        this.approvals = approvals;
        this.rows = rows;
        this.patcher = patcher;
        this.ledger = ledger;
        this.events = events;
    }

    // ------------------------------------------------------------------ enrolment

    @Transactional
    public ObjectNode create(Object body) {
        ObjectNode v = patcher.columns(T, body);
        return enrol(Json.uuid(v, "rewards_program_id"), Json.uuid(v, "customer_id"), Json.uuid(v, "product_id"),
                Json.uuid(v, "default_credit_account_id"));
    }

    /** Enrols a customer (program not ended, product eligible); assigns the base tier. 409 when already enrolled. */
    public ObjectNode enrol(UUID programId, UUID customerId, UUID productId, UUID creditAccount) {
        ObjectNode program = rows.queryOne("select row_to_json(t)::text from rewards_program t where t.id = ? for share", programId)
                .orElseThrow(() -> ApiException.unprocessable("program_not_found", "Rewards program " + programId + " does not exist"));
        if ("ENDED".equals(text(program, "status"))) {
            throw ApiException.unprocessable("program_not_active", "The program has ended");
        }
        if (!eligible(program, productId)) {
            throw ApiException.unprocessable("not_eligible", "The customer's product is not eligible for this program");
        }
        if (rows.count("select count(*) from points_account where rewards_program_id = ? and customer_id = ?", programId, customerId) > 0) {
            throw ApiException.conflict("already_enrolled", "The customer is already enrolled in this program");
        }
        ObjectNode v = rows.object();
        v.put("rewards_program_id", programId.toString());
        v.put("customer_id", customerId.toString());
        if (productId != null) {
            v.put("product_id", productId.toString());
        }
        if (creditAccount != null) {
            v.put("default_credit_account_id", creditAccount.toString());
        }
        v.put("status", "ACTIVE");
        v.put("enrolled_at", rows.now().toString());
        ObjectNode row = rows.insert(T, v);
        row = evaluateTier(row, program, rows.today(), false);
        events.publish("engagement.points_account.enrolled", T, row, PointsAccount.class);
        return row;
    }

    static boolean eligible(ObjectNode program, UUID productId) {
        List<String> products = Json.strings(program.get("eligible_product_ids"));
        return products.isEmpty() || (productId != null && products.contains(productId.toString()));
    }

    // ------------------------------------------------------------------ status

    @Transactional
    public ObjectNode suspend(UUID id, String reason) {
        return status(id, "suspend", reason);
    }

    @Transactional
    public ObjectNode reactivate(UUID id, String reason) {
        return status(id, "reactivate", reason);
    }

    /** Closes the account; the remaining available points are forfeited lot by lot (EXPIRE entries). */
    @Transactional
    public ObjectNode close(UUID id, String reason) {
        ObjectNode a = ledger.lockAccount(id);
        Lifecycles.ACCOUNT.apply("close", text(a, "status"));
        for (PointsLots.Lot lot : ledger.lots(id).open()) {
            ledger.append(a, new PointsLedger.Move("EXPIRE", -lot.remaining(), "close:" + id + ":" + lot.id())
                    .lot(lot.id()).source("CLOSURE", id).reason(reason).event("engagement.points.expired"));
        }
        return status(id, "close", reason);
    }

    private ObjectNode status(UUID id, String action, String reason) {
        ObjectNode a = rows.lock(T, id);
        ObjectNode v = rows.object();
        v.put("status", Lifecycles.ACCOUNT.apply(action, text(a, "status")));
        if (reason != null) {
            v.put("status_reason", Json.truncate(reason, 255));
        }
        ObjectNode row = rows.update(T, id, v);
        events.publish("engagement.points_account.status_changed", T, row, PointsAccount.class);
        return row;
    }

    static void requireActive(ObjectNode account) {
        if (!"ACTIVE".equals(text(account, "status"))) {
            throw ApiException.unprocessable("account_not_active", "The points account is " + text(account, "status"));
        }
    }

    // ------------------------------------------------------------------ transfers

    @Transactional
    public ObjectNode transfer(UUID fromId, UUID toId, long points, String reason) {
        if (points <= 0) {
            throw ApiException.unprocessable("invalid_points", "points must be > 0");
        }
        if (fromId.equals(toId)) {
            throw ApiException.unprocessable("same_account", "Cannot transfer to the same account");
        }
        boolean fromFirst = fromId.compareTo(toId) < 0;
        ObjectNode first = ledger.lockAccount(fromFirst ? fromId : toId);
        ObjectNode second = rows.queryOne("select row_to_json(t)::text from points_account t where t.id = ? for update",
                fromFirst ? toId : fromId).orElseThrow(() ->
                ApiException.unprocessable("account_not_found", "Points account " + (fromFirst ? toId : fromId) + " does not exist"));
        ObjectNode from = fromFirst ? first : second;
        ObjectNode to = fromFirst ? second : first;
        requireActive(from);
        requireActive(to);
        if (!Objects.equals(text(from, "rewards_program_id"), text(to, "rewards_program_id"))) {
            throw ApiException.unprocessable("program_mismatch", "Points can only move within one program");
        }
        ObjectNode program = rows.get("rewards_program", Json.uuid(from, "rewards_program_id"));
        UUID transferId = Ids.newId();
        ledger.append(from, new PointsLedger.Move("TRANSFER_OUT", -points, "transfer:" + transferId + ":out")
                .counterparty(toId).source("TRANSFER", transferId).reason(reason).event("engagement.points.transferred"));
        ledger.append(to, new PointsLedger.Move("TRANSFER_IN", points, "transfer:" + transferId + ":in")
                .counterparty(fromId).source("TRANSFER", transferId).reason(reason)
                .expires(PointsLedger.expiry(program, rows.today())).event("engagement.points.transferred"));
        return rows.get(T, fromId);
    }

    // ------------------------------------------------------------------ adjustments (maker-checker)

    @Transactional
    public ObjectNode requestAdjustment(UUID accountId, long points, String reason) {
        ObjectNode a = rows.lock(T, accountId);
        if ("CLOSED".equals(text(a, "status"))) {
            throw ApiException.unprocessable("account_not_active", "The points account is CLOSED");
        }
        if (points == 0) {
            throw ApiException.unprocessable("invalid_points", "points must not be 0");
        }
        if (points < 0 && -points > a.path("available_points").asLong()) {
            throw ApiException.unprocessable("insufficient_points", "Only " + a.path("available_points").asLong() + " points are available");
        }
        ObjectNode v = rows.object();
        v.put("points_account_id", accountId.toString());
        v.put("points", points);
        v.put("reason", Json.truncate(reason, 255));
        v.put("status", "PENDING_APPROVAL");
        v.put("requested_by", Json.truncate(TenantContext.principal(), 64));
        ObjectNode row = rows.insert(ADJ, v);
        events.publish("engagement.points_adjustment.requested", ADJ, row, PointsAdjustment.class);
        requestCentralApproval(a, row);
        return row;
    }

    /** Central maker-checker: the checker decides in the workflow inbox; the approval handler applies the decision. */
    private void requestCentralApproval(ObjectNode account, ObjectNode adj) {
        UUID id = Json.uuid(adj, "id");
        long points = adj.path("points").asLong();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("points_account_id", text(account, "id"));
        payload.put("customer_id", text(account, "customer_id"));
        payload.put("rewards_program_id", text(account, "rewards_program_id"));
        payload.put("points", points);
        payload.put("reason", text(adj, "reason"));
        payload.put("available_points", account.path("available_points").asLong());
        ApprovalSpec spec = ApprovalSpec.of(APPROVAL_RESOURCE, id, "approve",
                "Points adjustment " + (points > 0 ? "+" : "") + points + " on account " + text(account, "id"), payload);
        ObjectNode program = rows.find("rewards_program", Json.uuid(account, "rewards_program_id")).orElse(null);
        BigDecimal pointValue = program == null ? null : Json.decimal(program, "point_value");
        String currency = program == null ? null : text(program, "value_currency");
        if (pointValue != null && currency != null) {
            spec = spec.withAmount(pointValue.multiply(BigDecimal.valueOf(Math.abs(points))).stripTrailingZeros(), currency);
        }
        approvals.request(spec, "submit:" + id + ":" + points);
    }

    /** True while the adjustment is PENDING_APPROVAL with the submitted points unchanged. */
    @Transactional(readOnly = true)
    public boolean awaitsApproval(UUID id, Long points) {
        return rows.find(ADJ, id)
                .filter(a -> "PENDING_APPROVAL".equals(text(a, "status")))
                .filter(a -> points == null || points == a.path("points").asLong())
                .isPresent();
    }

    @Transactional
    public ObjectNode approveAdjustment(UUID id, String comment) {
        ObjectNode adj = rows.lock(ADJ, id);
        String to = Lifecycles.ADJUSTMENT.apply("approve", text(adj, "status"));
        String checker = TenantContext.principal();
        if (checker.equals(text(adj, "requested_by"))) {
            throw ApiException.unprocessable("four_eyes_violation", "An adjustment must be approved by a different user than its maker");
        }
        UUID accountId = Json.uuid(adj, "points_account_id");
        ObjectNode a = ledger.lockAccount(accountId);
        if ("CLOSED".equals(text(a, "status"))) {
            throw ApiException.unprocessable("account_not_active", "The points account is CLOSED");
        }
        long points = adj.path("points").asLong();
        ObjectNode program = rows.get("rewards_program", Json.uuid(a, "rewards_program_id"));
        PointsLedger.Move m = new PointsLedger.Move("ADJUST", points, "adjustment:" + id).source("ADJUSTMENT", id)
                .reason(text(adj, "reason")).event("engagement.points.adjusted");
        if (points > 0) {
            m.expires(PointsLedger.expiry(program, rows.today()));
        }
        ObjectNode entry = ledger.append(a, m);
        ObjectNode v = rows.object();
        v.put("status", to);
        v.put("decided_by", Json.truncate(checker, 64));
        v.put("decided_at", rows.now().toString());
        if (comment != null) {
            v.put("decision_comment", Json.truncate(comment, 500));
        }
        v.put("points_ledger_entry_id", text(entry, "id"));
        ObjectNode row = rows.update(ADJ, id, v);
        approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "approved directly");
        return row;
    }

    @Transactional
    public ObjectNode rejectAdjustment(UUID id, String reason) {
        ObjectNode adj = rows.lock(ADJ, id);
        ObjectNode v = rows.object();
        v.put("status", Lifecycles.ADJUSTMENT.apply("reject", text(adj, "status")));
        String checker = TenantContext.principal();
        if (checker.equals(text(adj, "requested_by"))) {
            throw ApiException.unprocessable("four_eyes_violation", "An adjustment must be decided by a different user than its maker");
        }
        v.put("decided_by", Json.truncate(checker, 64));
        v.put("decided_at", rows.now().toString());
        v.put("decision_comment", Json.truncate(reason, 500));
        ObjectNode row = rows.update(ADJ, id, v);
        events.publish("engagement.points_adjustment.rejected", ADJ, row, PointsAdjustment.class);
        approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "rejected directly");
        return row;
    }

    // ------------------------------------------------------------------ tiers

    @Transactional
    public ObjectNode recalculateTier(UUID id) {
        ObjectNode a = rows.lock(T, id);
        ObjectNode program = rows.get("rewards_program", Json.uuid(a, "rewards_program_id"));
        return evaluateTier(a, program, rows.today(), true);
    }

    /** Evaluates the tier as of {@code asOf} over the program's tier period and stores the decision. */
    public ObjectNode evaluateTier(ObjectNode account, ObjectNode program, LocalDate asOf, boolean publishUnchanged) {
        UUID id = Json.uuid(account, "id");
        int months = Json.integer(program, "tier_period_months", 12);
        OffsetDateTime windowStart = asOf.minusMonths(months).atStartOfDay().atOffset(java.time.ZoneOffset.UTC);
        BigDecimal spend = rows.jdbc().sql("""
                select coalesce(sum(amount), 0) from reward_transaction
                where points_account_id = ? and status <> 'REVERSED' and transaction_at >= ?""")
                .params(id, windowStart).query(BigDecimal.class).single();
        Long points = rows.jdbc().sql("""
                select coalesce(sum(points), 0) from points_ledger_entry
                where points_account_id = ? and entry_type in ('EARN', 'BONUS') and not is_pending and points > 0
                  and created_at >= ?""").params(id, windowStart).query(Long.class).single();
        List<TierEvaluator.Tier> tiers = rows.query("select row_to_json(t)::text from reward_tier t where t.rewards_program_id = ?",
                Json.uuid(program, "id")).stream().map(t -> new TierEvaluator.Tier(Json.uuid(t, "id"), t.path("rank").asInt(),
                Json.decimal(t, "qualify_spend"), Json.lng(t, "qualify_points"))).toList();
        TierEvaluator.Decision d = TierEvaluator.evaluate(tiers, spend, points, Json.uuid(account, "reward_tier_id"),
                Json.date(account, "tier_qualified_until"), asOf, months);
        ObjectNode v = rows.object();
        if (d.tierId() == null) {
            v.putNull("reward_tier_id");
            v.putNull("tier_qualified_until");
        } else {
            v.put("reward_tier_id", d.tierId().toString());
            v.put("tier_qualified_until", d.qualifiedUntil().toString());
        }
        v.put("tier_spend", spend.toPlainString());
        v.put("tier_points", points);
        v.put("tier_evaluated_at", rows.now().toString());
        ObjectNode row = rows.update(T, id, v);
        if (d.changed()) {
            events.publish("engagement.points_account.tier_changed", T, row, PointsAccount.class);
        }
        return row;
    }

    // ------------------------------------------------------------------ lots

    public JsonNode lotsView(UUID id) {
        ObjectNode a = rows.get(T, id);
        PointsLots.State s = ledger.lots(id);
        ObjectNode out = rows.object();
        out.put("points_account_id", id.toString());
        out.put("available_points", a.path("available_points").asLong());
        out.put("lots_total", s.balance());
        LocalDate today = rows.today();
        out.put("expiring_points_30d", s.expiringBetween(today, today.plusDays(PointsLedger.EXPIRY_HORIZON_DAYS)));
        LocalDate next = s.nextExpiry(today);
        out.put("next_expiry_date", next == null ? null : next.toString());
        ArrayNode lots = out.putArray("lots");
        for (PointsLots.Lot l : s.open()) {
            ObjectNode n = lots.addObject();
            n.put("entry_id", l.id().toString());
            n.put("earn_date", l.earnDate().toString());
            n.put("expires_on", l.expiresOn() == null ? null : l.expiresOn().toString());
            n.put("original_points", l.original());
            n.put("remaining_points", l.remaining());
        }
        return out;
    }
}
