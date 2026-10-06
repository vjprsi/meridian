package io.meridian.treasurydeal.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.treasurydeal.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Treasury end-of-day jobs of the current tenant for one business date, called by business-day-service
 * ({@code POST /v1/treasury/eod-runs/...?business_date=}). Each execution is recorded in {@code treasury_eod_run}
 * (one row per tenant, job and date): COMPLETED runs are replayed with {@code replayed=true}, COMPLETED_WITH_ERRORS
 * runs may be re-run (every item operation is idempotent: deterministic ledger keys and status guards), and a RUNNING
 * job is refused (409) unless stale. Items run in their own transactions so one failure does not stop the job.
 */
@Component
public class EndOfDayJobs {
    private static final Logger log = LoggerFactory.getLogger(EndOfDayJobs.class);
    private static final int STALE_HOURS = 2;
    private static final int MAX_ERRORS = 50;

    public enum Job { MARK_TO_MARKET, INTEREST_ACCRUAL, MATURITY_PROCESSING, LIMIT_UTILISATION_REFRESH }

    private final Rows rows;
    private final Events events;
    private final TransactionTemplate tx;
    private final MoneyMarketService moneyMarket;
    private final FxDealService fx;
    private final SecuritiesService securities;
    private final LimitService limits;

    public EndOfDayJobs(Rows rows, Events events, TransactionTemplate tx, MoneyMarketService moneyMarket,
                        FxDealService fx, SecuritiesService securities, LimitService limits) {
        this.rows = rows;
        this.events = events;
        this.tx = tx;
        this.moneyMarket = moneyMarket;
        this.fx = fx;
        this.securities = securities;
        this.limits = limits;
    }

    public ObjectNode run(Job job, LocalDate businessDate) {
        if (businessDate == null) {
            throw ApiException.badRequest("missing_parameter", "business_date is required");
        }
        Optional<ObjectNode> claimed = claim(job, businessDate);
        if (claimed.isEmpty()) {
            ObjectNode existing = rows.queryOne("""
                    select row_to_json(r)::text from treasury_eod_run r where job = ? and business_date = ?""",
                    job.name(), businessDate).orElseThrow();
            if ("RUNNING".equals(Rows.str(existing, "status"))) {
                throw ApiException.conflict("eod_job_running", job + " for " + businessDate + " is already running");
            }
            return response(existing, true);
        }
        UUID runId = Rows.uuid(claimed.get(), "id");
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        try {
            switch (job) {
                case MARK_TO_MARKET -> markToMarket(businessDate, counts, errors);
                case INTEREST_ACCRUAL -> accruals(businessDate, counts, errors);
                case MATURITY_PROCESSING -> maturities(businessDate, counts, errors);
                case LIMIT_UTILISATION_REFRESH -> limitRefresh(businessDate, counts, errors);
            }
        } catch (RuntimeException e) {
            log.error("treasury end-of-day job {} for {} failed", job, businessDate, e);
            errors.add(describe(e));
        }
        ObjectNode result = rows.object();
        ObjectNode c = result.putObject("counts");
        counts.forEach(c::put);
        ArrayNode errs = result.putArray("errors");
        errors.stream().limit(MAX_ERRORS).forEach(errs::add);
        result.put("error_count", errors.size());
        ObjectNode done = tx.execute(s -> {
            ObjectNode row = rows.queryOne("""
                    update treasury_eod_run set status = ?, result = ?::jsonb, completed_at = now(), updated_at = now()
                    where id = ? returning row_to_json(treasury_eod_run)::text""",
                    errors.isEmpty() ? "COMPLETED" : "COMPLETED_WITH_ERRORS", result.toString(), runId).orElseThrow();
            events.publish("treasury.eod_run.completed", "treasury_eod_run", row);
            return row;
        });
        return response(done, false);
    }

    private void markToMarket(LocalDate date, Map<String, Integer> counts, List<String> errors) {
        int revalued = 0;
        for (ObjectNode deal : rows.query("""
                select row_to_json(d)::text from treasury_fx_deal d
                where status in ('CAPTURED', 'VERIFIED', 'CONFIRMED') and trade_date <= ? order by id""", date)) {
            UUID id = Rows.uuid(deal, "id");
            revalued += guarded("revalue FX " + Rows.str(deal, "deal_ref"), errors, () -> {
                fx.revalue(id, date, null);
                return 1;
            });
        }
        counts.put("fx_deals_revalued", revalued);
        ObjectNode holdings = securities.revalue(date, null);
        counts.put("holdings_valued", holdings.get("holdings_valued").asInt());
        counts.put("holdings_without_price", holdings.get("holdings_without_price").asInt());
    }

    private void accruals(LocalDate date, Map<String, Integer> counts, List<String> errors) {
        int accrued = 0;
        List<ObjectNode> deals = rows.query("""
                select row_to_json(d)::text from money_market_deal d
                where status = 'SETTLED' and value_date <= ? and (accrued_through is null or accrued_through < ?)
                order by id""", date, date);
        for (ObjectNode deal : deals) {
            UUID id = Rows.uuid(deal, "id");
            accrued += guarded("accrue " + Rows.str(deal, "deal_ref"), errors, () -> {
                moneyMarket.accrue(id, date);
                return 1;
            });
        }
        counts.put("money_market_deals_accrued", accrued);
        int coupons = tx.execute(s -> {
            int n = 0;
            for (ObjectNode h : rows.query("select row_to_json(h)::text from securities_holding h where nominal > 0")) {
                ObjectNode sec = rows.get(SecuritiesService.SECURITY, Rows.uuid(h, "treasury_security_id"));
                ObjectNode v = rows.object();
                v.put("accrued_interest", io.meridian.treasurydeal.domain.BondMath.accruedAmount(
                        SecuritiesService.terms(sec), Rows.dec(h, "nominal"), date.plusDays(1), Rows.str(sec, "currency"))
                        .toPlainString());
                rows.update(SecuritiesService.HOLDING, Rows.uuid(h, "id"), v);
                n++;
            }
            return n;
        });
        counts.put("holdings_coupon_accrued", coupons);
    }

    private void maturities(LocalDate date, Map<String, Integer> counts, List<String> errors) {
        int matured = 0;
        int rolled = 0;
        for (ObjectNode deal : rows.query("""
                select row_to_json(d)::text from money_market_deal d
                where status = 'SETTLED' and maturity_date is not null and maturity_date <= ? order by maturity_date, id""",
                date)) {
            UUID id = Rows.uuid(deal, "id");
            String rollover = Rows.str(deal, "rollover_type");
            if ("NONE".equals(rollover)) {
                matured += guarded("mature " + Rows.str(deal, "deal_ref"), errors, () -> {
                    moneyMarket.mature(id, date);
                    return 1;
                });
            } else {
                BigDecimal rate = Rows.dec(deal, "interest_rate");
                rolled += guarded("rollover " + Rows.str(deal, "deal_ref"), errors, () -> {
                    moneyMarket.rollover(id, rate, null, rollover, date);
                    return 1;
                });
            }
        }
        counts.put("money_market_matured", matured);
        counts.put("money_market_rolled_over", rolled);
    }

    private void limitRefresh(LocalDate date, Map<String, Integer> counts, List<String> errors) {
        counts.put("limits_expired", tx.execute(s -> limits.refreshExpiries(date)).get("expired").asInt());
        int released = 0;
        int pse = 0;
        for (ObjectNode held : rows.query("""
                select json_build_object('deal_type', deal_type, 'deal_id', deal_id)::text
                from treasury_limit_utilisation group by deal_type, deal_id having sum(amount) <> 0""")) {
            String type = Rows.str(held, "deal_type");
            UUID dealId = Rows.uuid(held, "deal_id");
            Optional<ObjectNode> deal = rows.find(table(type), dealId);
            boolean dead = deal.isEmpty() || switch (type) {
                case "MONEY_MARKET" -> List.of("MATURED", "CANCELLED").contains(Rows.str(deal.get(), "status"));
                case "FX" -> List.of("SETTLED", "CANCELLED").contains(Rows.str(deal.get(), "status"));
                default -> "CANCELLED".equals(Rows.str(deal.get(), "status")) || securityMatured(deal.get(), date);
            };
            if (dead) {
                released += guarded("release " + dealId, errors, () -> tx.execute(s -> limits.release(type, dealId, false)));
            } else if ("FX".equals(type)) {
                pse += guarded("pre-settlement " + dealId, errors, () -> tx.execute(s -> {
                    fx.refreshPreSettlement(rows.lock(FxDealService.TABLE, dealId), date);
                    return 1;
                }));
            }
        }
        counts.put("deals_released", released);
        counts.put("pre_settlement_refreshed", pse);
        counts.put("limit_totals_corrected", tx.execute(s -> limits.rebuildTotals()));
    }

    private boolean securityMatured(ObjectNode trade, LocalDate date) {
        return rows.find(SecuritiesService.SECURITY, Rows.uuid(trade, "treasury_security_id"))
                .map(s -> Rows.date(s, "maturity_date").isBefore(date)).orElse(true);
    }

    private static String table(String dealType) {
        return switch (dealType) {
            case "MONEY_MARKET" -> MoneyMarketService.TABLE;
            case "FX" -> FxDealService.TABLE;
            default -> SecuritiesService.TRADE;
        };
    }

    private int guarded(String what, List<String> errors, Supplier<Integer> work) {
        try {
            Integer n = work.get();
            return n == null ? 0 : n;
        } catch (RuntimeException e) {
            log.warn("treasury end-of-day item {} failed: {}", what, e.getMessage());
            errors.add(what + ": " + describe(e));
            return 0;
        }
    }

    private static String describe(RuntimeException e) {
        return e instanceof ApiException a ? a.code() + " - " + a.getMessage() : e.getClass().getSimpleName() + " - " + e.getMessage();
    }

    private Optional<ObjectNode> claim(Job job, LocalDate date) {
        String principal = TenantContext.principal();
        return rows.queryOne("""
                insert into treasury_eod_run as r (id, tenant_id, job, business_date, status, attempts, started_at,
                                                   created_by, updated_by)
                values (?, ?, ?, ?, 'RUNNING', 1, now(), ?, ?)
                on conflict (tenant_id, job, business_date) do update
                  set status = 'RUNNING', attempts = r.attempts + 1, started_at = now(), completed_at = null,
                      result = null, updated_at = now(), updated_by = excluded.updated_by
                  where r.status = 'COMPLETED_WITH_ERRORS'
                     or (r.status = 'RUNNING' and r.started_at < now() - make_interval(hours => ?))
                returning row_to_json(r)::text""",
                Ids.newId(), TenantContext.require(), job.name(), date, principal, principal, STALE_HOURS);
    }

    private ObjectNode response(ObjectNode run, boolean replayed) {
        ObjectNode out = rows.object();
        out.put("run_id", Rows.str(run, "id"));
        out.put("job", Rows.str(run, "job"));
        out.put("business_date", Rows.str(run, "business_date"));
        out.put("status", Rows.str(run, "status"));
        out.put("attempts", Rows.integer(run, "attempts"));
        out.put("replayed", replayed);
        out.set("result", run.get("result"));
        return out;
    }
}
