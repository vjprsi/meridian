package io.meridian.treasurydeal.application;

import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.common.error.ApiException;
import io.meridian.treasurydeal.domain.Exposures.Exposure;
import io.meridian.treasurydeal.domain.Lifecycle;
import io.meridian.treasurydeal.infrastructure.Rows;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Counterparty limits: maker-checker set-up, pre-deal checks, reservation on capture, release on settlement /
 * maturity / cancellation, and the end-of-day rebuild. Limits of the counterparty and of its group parent apply.
 * Utilisation is an append-only ledger ({@code treasury_limit_utilisation}); the limit row carries the running total.
 */
@Service
public class LimitService {
    static final String TABLE = "treasury_counterparty_limit";
    static final String UTIL = "treasury_limit_utilisation";
    static final MathContext MC = MathContext.DECIMAL128;

    private final Rows rows;
    private final Events events;
    private final Ports.MarketData market;
    private final Approvals approvals;

    /** Approval resource type for counterparty limits in workflow-service (G11). */
    public static final String APPROVAL_RESOURCE = "treasury_counterparty_limit";

    public LimitService(Rows rows, Events events, Ports.MarketData market, Approvals approvals) {
        this.rows = rows;
        this.events = events;
        this.market = market;
        this.approvals = approvals;
    }

    // ------------------------------------------------------------------ set-up

    @Transactional
    public ObjectNode create(JsonNode req) {
        UUID cp = Rows.uuid(req, "treasury_counterparty_id");
        Support.required(cp, "treasury_counterparty_id");
        rows.get(CounterpartyService.TABLE, cp);
        validate(req);
        ObjectNode v = rows.columns(TABLE, req);
        v.put("utilised_amount", "0");
        v.put("available_amount", Rows.dec(req, "limit_amount").toPlainString());
        v.put("status", "PENDING_APPROVAL");
        if (rows.scalar("""
                select count(*) from treasury_counterparty_limit where treasury_counterparty_id = ? and limit_type = ?
                  and currency = ? and valid_from = ?""", Integer.class, cp, Rows.str(req, "limit_type"),
                Rows.str(req, "currency"), Rows.date(req, "valid_from")).orElse(0) > 0) {
            throw ApiException.conflict("duplicate_limit",
                    "A limit of this type and currency already starts on that date for the counterparty");
        }
        ObjectNode out = rows.insert(TABLE, v);
        requestApproval(out);
        return out;
    }

    /** PATCH: an increase of limit_amount needs a new approval; a decrease applies immediately. */
    @Transactional
    public ObjectNode update(UUID id, int expectedVersion, JsonNode req) {
        ObjectNode limit = rows.lock(TABLE, id);
        if (Rows.integer(limit, "row_version") != expectedVersion) {
            throw ApiException.conflict("version_conflict", "If-Match does not match the current version; reload and retry");
        }
        if (req.hasNonNull("treasury_counterparty_id") || req.hasNonNull("limit_type") || req.hasNonNull("currency")) {
            throw ApiException.unprocessable("field_not_updatable",
                    "treasury_counterparty_id, limit_type and currency cannot be changed; create a new limit");
        }
        ObjectNode merged = limit.deepCopy();
        req.propertyNames().forEach(n -> {
            if (!req.get(n).isNull()) {
                merged.set(n, req.get(n));
            }
        });
        validate(merged);
        ObjectNode v = rows.columns(TABLE, req);
        BigDecimal newAmount = Rows.dec(merged, "limit_amount");
        BigDecimal oldAmount = Rows.dec(limit, "limit_amount");
        v.put("available_amount", newAmount.subtract(Rows.dec(limit, "utilised_amount")).toPlainString());
        if (newAmount.compareTo(oldAmount) > 0 && !"PENDING_APPROVAL".equals(Rows.str(limit, "status"))) {
            v.put("status", "PENDING_APPROVAL");
            v.putNull("approved_by");
            v.putNull("approved_at");
        }
        ObjectNode out = rows.update(TABLE, id, v);
        if ("PENDING_APPROVAL".equals(Rows.str(out, "status"))) {
            requestApproval(out);
        }
        return out;
    }

    /** Central maker-checker: the checker decides in the workflow inbox; LimitApprovalHandler applies it. */
    private void requestApproval(ObjectNode limit) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (String f : new String[] {"treasury_counterparty_id", "limit_type", "currency", "limit_amount",
                "warning_threshold_pct", "max_tenor_days", "valid_from", "valid_to"}) {
            payload.put(f, Rows.str(limit, f));
        }
        payload.put("row_version", Rows.integer(limit, "row_version"));
        String ccy = Rows.str(limit, "currency");
        approvals.request(ApprovalSpec.of(APPROVAL_RESOURCE, Rows.uuid(limit, "id"), "approve",
                Rows.str(limit, "limit_type") + " limit " + Rows.dec(limit, "limit_amount").toPlainString() + " " + ccy, payload)
                .withAmount(Rows.dec(limit, "limit_amount"), ccy),
                "submit:" + Rows.str(limit, "id") + ":" + Rows.integer(limit, "row_version"));
    }

    /** True while the limit is PENDING_APPROVAL with the submitted content (row_version) unchanged. */
    @Transactional(readOnly = true)
    public boolean awaitsApproval(UUID id, Integer rowVersion) {
        return rows.find(TABLE, id)
                .filter(l -> "PENDING_APPROVAL".equals(Rows.str(l, "status")))
                .filter(l -> rowVersion == null || rowVersion.equals(Rows.integer(l, "row_version")))
                .isPresent();
    }

    private static void validate(JsonNode req) {
        Support.currency(Rows.str(req, "currency"), "currency");
        Support.positive(Rows.dec(req, "limit_amount"), "limit_amount");
        BigDecimal warn = Rows.dec(req, "warning_threshold_pct");
        if (warn != null && (warn.signum() <= 0 || warn.compareTo(BigDecimal.ONE) > 0)) {
            throw ApiException.unprocessable("invalid_threshold", "warning_threshold_pct must be in (0, 1]");
        }
        Integer tenor = Rows.integer(req, "max_tenor_days");
        if (tenor != null && tenor <= 0) {
            throw ApiException.unprocessable("invalid_tenor", "max_tenor_days must be positive");
        }
        if ("TENOR".equals(Rows.str(req, "limit_type")) && tenor == null) {
            throw ApiException.unprocessable("missing_field", "max_tenor_days is required for TENOR limits");
        }
        Support.required(Rows.date(req, "valid_from"), "valid_from");
        Support.ordered(Rows.date(req, "valid_from"), Rows.date(req, "valid_to"), "valid_to must not be before valid_from");
    }

    @Transactional
    public ObjectNode approve(UUID id) {
        ObjectNode limit = rows.lock(TABLE, id);
        Support.transition(Lifecycle.LIMIT, limit, "ACTIVE", "limit");
        Support.fourEyes(limit, Rows.str(limit, "updated_by"));
        ObjectNode v = rows.object();
        v.put("status", "ACTIVE");
        v.put("approved_by", io.meridian.common.tenancy.TenantContext.principal());
        v.put("approved_at", rows.now());
        ObjectNode out = rows.update(TABLE, id, v);
        events.publish("treasury.limit.approved", TABLE, out);
        approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "approved directly");
        return out;
    }

    // ------------------------------------------------------------------ check / reserve / release

    /** One limit affected by an exposure. */
    record Impact(ObjectNode limit, BigDecimal amount, BigDecimal after, boolean amountBreach, boolean tenorBreach) {}

    /** Pre-deal check of one prospective exposure (no reservation). */
    @Transactional(readOnly = true)
    public ObjectNode check(UUID counterpartyId, String limitType, BigDecimal amount, String currency, Integer tenorDays) {
        rows.get(CounterpartyService.TABLE, counterpartyId);
        Support.currency(currency, "currency");
        Support.positive(amount, "amount");
        LocalDate today = rows.today();
        List<Impact> impacts = impacts(counterpartyId,
                List.of(new Exposure(limitType, amount, currency, tenorDays == null ? 0 : tenorDays, false)), today);
        ObjectNode out = rows.object();
        out.put("treasury_counterparty_id", counterpartyId.toString());
        out.put("limit_type", limitType);
        out.put("amount", amount.toPlainString());
        out.put("currency", currency);
        boolean breach = impacts.stream().anyMatch(i -> i.amountBreach() || i.tenorBreach());
        out.put("approved", !impacts.isEmpty() && !breach);
        out.put("limit_found", !impacts.isEmpty());
        out.put("breach", breach);
        ArrayNode arr = out.putArray("limits");
        for (Impact i : impacts) {
            ObjectNode n = arr.addObject();
            n.put("limit_id", Rows.str(i.limit(), "id"));
            n.put("treasury_counterparty_id", Rows.str(i.limit(), "treasury_counterparty_id"));
            n.put("limit_currency", Rows.str(i.limit(), "currency"));
            n.put("limit_amount", Rows.str(i.limit(), "limit_amount"));
            n.put("utilised_amount", Rows.str(i.limit(), "utilised_amount"));
            n.put("exposure_in_limit_currency", i.amount().toPlainString());
            n.put("utilised_after", i.after().toPlainString());
            n.put("available_after", Rows.dec(i.limit(), "limit_amount").subtract(i.after()).toPlainString());
            n.put("warning", i.after().compareTo(threshold(i.limit())) >= 0);
            n.put("amount_breach", i.amountBreach());
            n.put("tenor_breach", i.tenorBreach());
        }
        return out;
    }

    /**
     * Reserves the exposures of a deal; refuses the deal (422 limit_breached / tenor_limit_exceeded / no_limit) when
     * any limit would be exceeded or no limit covers it. Emits warning events when the threshold is crossed.
     */
    public void reserve(String dealType, UUID dealId, UUID counterpartyId, List<Exposure> exposures, LocalDate asOf) {
        if (exposures.isEmpty()) {
            return;
        }
        List<Impact> impacts = impacts(counterpartyId, exposures, asOf);
        if (impacts.isEmpty()) {
            throw ApiException.unprocessable("no_limit",
                    "The counterparty has no active, approved limit covering this deal");
        }
        List<String> tenor = new ArrayList<>();
        List<String> amount = new ArrayList<>();
        for (Impact i : impacts) {
            String d = Rows.str(i.limit(), "limit_type") + " " + Rows.str(i.limit(), "currency") + " limit "
                    + Rows.str(i.limit(), "limit_amount");
            if (i.tenorBreach()) {
                tenor.add(d + " (max tenor " + Rows.integer(i.limit(), "max_tenor_days") + " days)");
            }
            if (i.amountBreach()) {
                amount.add(d + " (utilisation after deal " + i.after().toPlainString() + ")");
            }
        }
        if (!tenor.isEmpty()) {
            throw ApiException.unprocessable("tenor_limit_exceeded", "Deal tenor exceeds " + String.join("; ", tenor));
        }
        if (!amount.isEmpty()) {
            throw ApiException.unprocessable("limit_breached", "Deal would breach " + String.join("; ", amount));
        }
        for (Impact i : impacts) {
            record(dealType, dealId, lock(i.limit()), i.amount(), "RESERVE");
        }
    }

    /** Releases what a deal still holds on its limits (all limit types, or only SETTLEMENT limits). */
    public int release(String dealType, UUID dealId, boolean settlementOnly) {
        List<ObjectNode> open = rows.query("""
                select json_build_object('limit_id', u.treasury_counterparty_limit_id, 'net', sum(u.amount))::text
                from treasury_limit_utilisation u join treasury_counterparty_limit l on l.id = u.treasury_counterparty_limit_id
                where u.deal_id = ? and (? = false or l.limit_type = 'SETTLEMENT')
                group by u.treasury_counterparty_limit_id having sum(u.amount) <> 0""", dealId, settlementOnly);
        for (ObjectNode o : open) {
            ObjectNode limit = rows.lock(TABLE, Rows.uuid(o, "limit_id"));
            record(dealType, dealId, limit, Rows.dec(o, "net").negate(), "RELEASE");
        }
        return open.size();
    }

    /** Writes one utilisation change against a locked limit row, updates its totals and emits warning / breach events. */
    ObjectNode record(String dealType, UUID dealId, ObjectNode limit, BigDecimal amount, String reason) {
        BigDecimal limitAmount = Rows.dec(limit, "limit_amount");
        BigDecimal before = Rows.dec(limit, "utilised_amount");
        BigDecimal after = before.add(amount);
        boolean breach = after.compareTo(limitAmount) > 0 && amount.signum() > 0;
        ObjectNode u = rows.object();
        u.put("treasury_counterparty_limit_id", Rows.str(limit, "id"));
        u.put("deal_type", dealType);
        u.put("deal_id", dealId.toString());
        u.put("reason", reason);
        u.put("amount", amount.toPlainString());
        u.put("currency", Rows.str(limit, "currency"));
        u.put("utilised_after", after.toPlainString());
        u.put("breach", breach);
        ObjectNode util = rows.insert(UTIL, u);
        ObjectNode v = rows.object();
        v.put("utilised_amount", after.toPlainString());
        v.put("available_amount", limitAmount.subtract(after).toPlainString());
        rows.update(TABLE, Rows.uuid(limit, "id"), v);
        if (breach) {
            events.publish("treasury.limit.breached", UTIL, util);
        } else if (amount.signum() > 0 && before.compareTo(threshold(limit)) < 0 && after.compareTo(threshold(limit)) >= 0) {
            events.publish("treasury.limit.warning_reached", UTIL, util);
        }
        return util;
    }

    private static BigDecimal threshold(JsonNode limit) {
        BigDecimal pct = Rows.dec(limit, "warning_threshold_pct");
        return Rows.dec(limit, "limit_amount").multiply(pct == null ? new BigDecimal("0.8") : pct, MC);
    }

    private ObjectNode lock(ObjectNode limit) {
        return rows.lock(TABLE, Rows.uuid(limit, "id"));
    }

    /** Limits (counterparty and group parent) touched by the exposures, with amounts in the limit currency. */
    List<Impact> impacts(UUID counterpartyId, List<Exposure> exposures, LocalDate asOf) {
        List<ObjectNode> limits = applicable(counterpartyId, asOf);
        Map<String, BigDecimal> pending = new HashMap<>();
        List<Impact> out = new ArrayList<>();
        for (Exposure e : exposures) {
            for (ObjectNode l : limits) {
                if (!e.limitType().equals(Rows.str(l, "limit_type"))) {
                    continue;
                }
                String limitCcy = Rows.str(l, "currency");
                BigDecimal amt = convert(e.amount(), e.currency(), limitCcy);
                String id = Rows.str(l, "id");
                BigDecimal after = Rows.dec(l, "utilised_amount").add(pending.getOrDefault(id, BigDecimal.ZERO)).add(amt);
                pending.merge(id, amt, BigDecimal::add);
                Integer maxTenor = Rows.integer(l, "max_tenor_days");
                out.add(new Impact(l, amt, after, after.compareTo(Rows.dec(l, "limit_amount")) > 0,
                        maxTenor != null && e.tenorDays() > maxTenor));
            }
        }
        return out;
    }

    private List<ObjectNode> applicable(UUID counterpartyId, LocalDate asOf) {
        return rows.query("""
                select row_to_json(l)::text from treasury_counterparty_limit l
                where l.status = 'ACTIVE' and l.valid_from <= ? and (l.valid_to is null or l.valid_to >= ?)
                  and (l.treasury_counterparty_id = ? or l.treasury_counterparty_id =
                       (select parent_counterparty_id from treasury_counterparty where id = ?))
                order by l.id""", asOf, asOf, counterpartyId, counterpartyId);
    }

    /** Converts into the limit currency with the reference MID rate (422 fx_rate_unavailable without one). */
    BigDecimal convert(BigDecimal amount, String from, String to) {
        if (from.equals(to)) {
            return amount;
        }
        BigDecimal rate = market.rate(from, to, "MID")
                .or(() -> market.rate(to, from, "MID").map(r -> BigDecimal.ONE.divide(r, MC)))
                .orElseThrow(() -> ApiException.unprocessable("fx_rate_unavailable",
                        "No " + from + "/" + to + " rate to express the exposure in the limit currency"));
        return io.meridian.treasurydeal.domain.Currencies.round(amount.multiply(rate, MC), to);
    }

    // ------------------------------------------------------------------ end of day

    /** Expires lapsed limits, releases what dead deals still hold and rebuilds limit totals from the utilisation ledger. */
    public ObjectNode refreshExpiries(LocalDate businessDate) {
        int expired = rows.execute("""
                update treasury_counterparty_limit set status = 'EXPIRED', updated_at = now()
                where status in ('ACTIVE', 'SUSPENDED') and valid_to is not null and valid_to < ?""", businessDate);
        ObjectNode out = rows.object();
        out.put("expired", expired);
        return out;
    }

    /** Re-sets a deal's PRE_SETTLEMENT utilisation to {@code target} (REFRESH row with the delta). */
    public void refreshPreSettlement(String dealType, UUID dealId, UUID counterpartyId, BigDecimal target,
                                     String currency, LocalDate asOf) {
        for (ObjectNode l : applicable(counterpartyId, asOf)) {
            if (!"PRE_SETTLEMENT".equals(Rows.str(l, "limit_type"))) {
                continue;
            }
            BigDecimal held = rows.scalar("""
                    select coalesce(sum(amount), 0) from treasury_limit_utilisation
                    where deal_id = ? and treasury_counterparty_limit_id = ?""", BigDecimal.class, dealId,
                    Rows.uuid(l, "id")).orElse(BigDecimal.ZERO);
            BigDecimal wanted = convert(target, currency, Rows.str(l, "currency"));
            BigDecimal delta = wanted.subtract(held);
            if (delta.abs().compareTo(new BigDecimal("0.01")) >= 0) {
                record(dealType, dealId, rows.lock(TABLE, Rows.uuid(l, "id")), delta, "REFRESH");
            }
        }
    }

    /** Recomputes every limit's totals from its utilisation ledger; returns the number of corrected limits. */
    public int rebuildTotals() {
        int n = rows.execute("""
                update treasury_counterparty_limit l set utilised_amount = s.total,
                       available_amount = l.limit_amount - s.total, refreshed_at = now()
                from (select l2.id, coalesce(sum(u.amount), 0) as total from treasury_counterparty_limit l2
                      left join treasury_limit_utilisation u on u.treasury_counterparty_limit_id = l2.id group by l2.id) s
                where s.id = l.id and (l.utilised_amount <> s.total or l.available_amount <> l.limit_amount - s.total)""");
        rows.execute("update treasury_counterparty_limit set refreshed_at = now()");
        return n;
    }
}
