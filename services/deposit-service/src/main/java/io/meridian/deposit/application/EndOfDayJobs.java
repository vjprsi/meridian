package io.meridian.deposit.application;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.deposit.config.DepositProperties;
import io.meridian.deposit.infrastructure.Rows;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * End-of-day batch per tenant: dormancy, scheduled product changes, daily accrual for the previous business date,
 * capitalisation when due, term deposit maturities and notices, standing orders, pot rules and sweeps. Each account
 * is processed in its own transaction so one failure does not stop the run. Disabled unless
 * {@code meridian.deposit.batch.enabled=true}; tenants come from {@code meridian.deposit.batch.tenants}.
 */
@Component
public class EndOfDayJobs {
    private static final Logger log = LoggerFactory.getLogger(EndOfDayJobs.class);
    private static final int MATURITY_NOTICE_DAYS = 7;

    private final DepositProperties props;
    private final Rows rows;
    private final AccountSupport accounts;
    private final InterestService interest;
    private final DepositAccountService deposits;
    private final TermDepositService termDeposits;
    private final StandingOrderService standingOrders;
    private final SavingsPotService pots;
    private final SweepService sweeps;

    public EndOfDayJobs(DepositProperties props, Rows rows, AccountSupport accounts, InterestService interest,
                        DepositAccountService deposits, TermDepositService termDeposits,
                        StandingOrderService standingOrders, SavingsPotService pots, SweepService sweeps) {
        this.props = props;
        this.rows = rows;
        this.accounts = accounts;
        this.interest = interest;
        this.deposits = deposits;
        this.termDeposits = termDeposits;
        this.standingOrders = standingOrders;
        this.pots = pots;
        this.sweeps = sweeps;
    }

    @Scheduled(cron = "${meridian.deposit.batch.cron:0 30 0 * * *}", zone = "UTC")
    public void scheduled() {
        if (!props.batch().enabled()) {
            return;
        }
        for (UUID tenant : props.batch().tenants()) {
            try {
                TenantContext.callAs(tenant, "system:end-of-day", () -> run(accounts.today()));
            } catch (Exception e) {
                log.error("end-of-day run failed for tenant {}", tenant, e);
            }
        }
    }

    /** Runs every job for the current tenant and business date; returns counts per job. */
    public Map<String, Integer> run(LocalDate today) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        LocalDate yesterday = today.minusDays(1);
        counts.put("product_changes", safe("product changes", () -> deposits.applyScheduledProductChanges(today)));
        int accrued = 0;
        int capitalised = 0;
        List<UUID> ids = rows.query("""
                select row_to_json(a)::text from deposit_account a where ledger_account_id is not null
                  and status not in ('CLOSED', 'REJECTED', 'PENDING_APPROVAL', 'PENDING_FUNDING')""")
                .stream().map(a -> Rows.uuid(a, "id")).toList();
        for (UUID id : ids) {
            accrued += safe("accrual " + id, () -> interest.accrueThrough(id, yesterday));
            boolean due = rows.scalar("select next_capitalisation_date <= ? from deposit_account where id = ?",
                    Boolean.class, today, id).orElse(false);
            if (due) {
                capitalised += safe("capitalisation " + id, () -> interest.capitalise(id, false).capitalisations().size());
            }
        }
        counts.put("accrual_rows", accrued);
        counts.put("capitalisations", capitalised);
        counts.put("dormancy_changes", safe("dormancy", () -> deposits.runDormancy(today)));
        counts.put("maturing_notices", safe("maturing", () -> termDeposits.notifyMaturing(today, MATURITY_NOTICE_DAYS)));
        counts.put("maturities", safe("maturities", () -> termDeposits.processMaturities(today).size()));
        counts.put("standing_orders", safe("standing orders", () -> standingOrders.executeDue(today).size()));
        counts.put("pot_rules", safe("pot rules", () -> pots.runScheduledRules(today)));
        counts.put("sweeps", safe("sweeps", () -> sweeps.runDue(accounts.now())));
        return counts;
    }

    private static int safe(String what, java.util.function.IntSupplier job) {
        try {
            return job.getAsInt();
        } catch (RuntimeException e) {
            log.error("end-of-day job '{}' failed", what, e);
            return 0;
        }
    }
}
