package io.meridian.agentnetwork.config;

import io.meridian.web.config.TenantSettings;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * agent-network-service settings ({@code meridian.agent-network.*}).
 *
 * <p>Wallet and ledger ids are tenant data: a tenant's config-service setting (namespace {@code ledger}, key
 * {@code agent-network-service}) wins over the deployment value, exactly like wallet-service's GL routing.
 *
 * @param treasuryWalletId     platform treasury wallet funding bank-funded float top-ups and receiving withdrawals
 * @param capitalWalletId      WE-FLOAT-1: capital pool wallet (IND01); defaults to the treasury wallet
 * @param ledgerId             WE-FLOAT-1: ledger book for GL&lt;-&gt;GL unclaimed-settlement journals
 * @param gl                   WE-FLOAT-1: e-money system ledger accounts (trust 2700, suspense 2710, unclaimed 2720,
 *                             bank clearing 1150, income IND03)
 * @param sweep                WE-FLOAT-1: auto-deallocation sweep scheduler settings
 * @param lowFloatAlertMinutes minimum minutes between two low-float alerts of the same float
 */
@ConfigurationProperties(prefix = "meridian.agent-network")
public record AgentNetworkProperties(UUID treasuryWalletId, UUID capitalWalletId, UUID ledgerId, Gl gl, Sweep sweep,
                                     Integer lowFloatAlertMinutes) {
    public AgentNetworkProperties {
        lowFloatAlertMinutes = lowFloatAlertMinutes == null ? 60 : lowFloatAlertMinutes;
        gl = gl == null ? new Gl(null, null, null, null, null, null, null) : gl;
        sweep = sweep == null ? new Sweep(null, null, null) : sweep;
    }

    /** The tenant's treasury wallet (wallets are tenant data): config-service setting first, deployment value second. */
    @Override
    public UUID treasuryWalletId() {
        return TenantSettings.uuid("agent-network-service", "treasury_wallet_id", treasuryWalletId);
    }

    /** WE-FLOAT-1: the capital pool wallet (IND01); the tenant's setting, else the treasury wallet (which doubles as it). */
    @Override
    public UUID capitalWalletId() {
        return TenantSettings.uuid("agent-network-service", "capital_wallet_id", treasuryWalletId());
    }

    /** WE-FLOAT-1: the ledger book for GL&lt;-&gt;GL journals; the tenant's setting (or its default book), else this value. */
    @Override
    public UUID ledgerId() {
        return TenantSettings.uuid("agent-network-service", "ledger_id", ledgerId);
    }

    /** WE-FLOAT-1: e-money system accounts, per tenant from config-service (namespace {@code ledger}, key {@code agent-network-service}). */
    @Override
    public Gl gl() {
        return new Gl(TenantSettings.uuid("agent-network-service", "gl.trust", gl.trust()),
                TenantSettings.uuid("agent-network-service", "gl.suspense", gl.suspense()),
                TenantSettings.uuid("agent-network-service", "gl.unclaimed", gl.unclaimed()),
                TenantSettings.uuid("agent-network-service", "gl.bank_clearing", gl.bankClearing()),
                TenantSettings.uuid("agent-network-service", "gl.capital", gl.capital()),
                TenantSettings.uuid("agent-network-service", "gl.income", gl.income()),
                TenantSettings.uuid("agent-network-service", "gl.escheatment", gl.escheatment()));
    }

    /**
     * WE-FLOAT-1 e-money system ledger accounts (GL counterparties).
     *
     * @param trust        customer trust liability (2700 / IND04): DEALLOCATE and sweeps credit it
     * @param suspense     unregistered / pending-claim suspense (2710 / IND02): ages to unclaimed
     * @param unclaimed    unclaimed funds (2720 / IND06): a customer liability that ages to escheatment by default
     * @param bankClearing bank clearing / trust bank (1150): stock creation debits it, reimbursement credits it
     * @param capital      capital pool GL account (IND01): explicit, operator-selected destination of aged unclaimed funds
     * @param income       service-charge income (IND03): explicit, operator-selected destination of aged unclaimed funds
     * @param escheatment  e-money due to regulator / escheatment liability (2760): D-ED-07 default destination of aged
     *                     unclaimed funds — value parked for/after lawful transfer to the regulator, never income/capital
     */
    public record Gl(UUID trust, UUID suspense, UUID unclaimed, UUID bankClearing, UUID capital, UUID income,
                     UUID escheatment) {}

    /**
     * Auto-deallocation sweep scheduler (WE-FLOAT-1, MQ-STK-6). The scheduler only builds PENDING_APPROVAL plans; a
     * checker still approves before any money moves.
     *
     * @param scheduleEnabled    build sweep plans on a schedule (default false; opt-in per deployment)
     * @param defaultMinResidual residual kept per float wallet when neither the plan nor {@code agent_float.min_balance} sets one
     * @param intervalMs         delay between scheduled planning passes
     */
    public record Sweep(Boolean scheduleEnabled, java.math.BigDecimal defaultMinResidual, Long intervalMs) {
        public Sweep {
            scheduleEnabled = scheduleEnabled != null && scheduleEnabled;
            intervalMs = intervalMs == null ? 86_400_000L : intervalMs;
        }
    }
}
