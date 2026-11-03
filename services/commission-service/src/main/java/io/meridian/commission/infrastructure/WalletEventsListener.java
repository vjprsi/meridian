package io.meridian.commission.infrastructure;

import io.meridian.commission.application.AccrualService;
import io.meridian.commission.application.MilestoneService;
import io.meridian.commission.application.PendingRewardService;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Accrues commissions from wallet-service events (binary CloudEvents on {@code meridian.wallets.v1}): completed
 * transactions accrue, reversed ones are clawed back. Accrual is idempotent per transaction, so redeliveries are harmless.
 */
@Component
@ConditionalOnProperty(name = "meridian.commission.listener-enabled", havingValue = "true", matchIfMissing = true)
public class WalletEventsListener {
    private static final Logger log = LoggerFactory.getLogger(WalletEventsListener.class);
    static final Set<String> TYPES = Set.of("wallets.wallet_transaction.completed", "wallets.wallet_transaction.reversed",
            "wallets.wallet.activated");

    private final AccrualService accruals;
    private final MilestoneService milestones;
    private final PendingRewardService pendingRewards;
    private final JsonMapper json;

    public WalletEventsListener(AccrualService accruals, MilestoneService milestones, PendingRewardService pendingRewards,
                                JsonMapper json) {
        this.accruals = accruals;
        this.milestones = milestones;
        this.pendingRewards = pendingRewards;
        this.json = json;
    }

    @KafkaListener(topics = "${meridian.commission.wallet-topic:meridian.wallets.v1}", groupId = "commission-service-accrual")
    public void onEvent(ConsumerRecord<String, String> record) throws Exception {
        String type = header(record, "ce_type");
        String tenant = header(record, "ce_tenantid");
        if (type == null || tenant == null || !TYPES.contains(type)) {
            return;
        }
        JsonNode payload = json.readTree(record.value());
        if ("wallets.wallet.activated".equals(type)) {
            register(tenant, payload);
            return;
        }
        if (type.endsWith(".completed") && !"COMPLETED".equals(payload.path("status").asString())) {
            return;
        }
        AccrualService.TxnEvent te = AccrualService.TxnEvent.from(payload, "id");
        try {
            TenantContext.callAs(UUID.fromString(tenant), "wallet-events", () -> accruals.accrue(te));
        } catch (ApiException e) {
            log.warn("Commission accrual for {} skipped: {} {}", te.walletTransactionId(), e.code(), e.getMessage());
        }
        if ("wallets.wallet_transaction.completed".equals(type)) {
            milestonesForTxn(tenant, te);
        }
    }

    /** WE-PRICE-1 (PE-11): the REGISTER joining-commission milestone, driven by the first wallet activation. */
    private void register(String tenant, JsonNode wallet) {
        if (!"CUSTOMER".equals(wallet.path("wallet_type").asString())) {
            return;
        }
        UUID walletId = uuid(wallet, "id");
        UUID openedBy = uuid(wallet, "opened_by_agent_id");
        String currency = text(wallet, "currency");
        try {
            TenantContext.callAs(UUID.fromString(tenant), "wallet-events", () -> {
                milestones.fire(MilestoneService.Milestone.REGISTER, walletId, currency, uuid(wallet, "grade_id"),
                        uuid(wallet, "category_id"), openedBy, null, null, null);
                return null;
            });
        } catch (Exception e) {
            log.warn("REGISTER milestone for wallet {} skipped: {}", walletId, e.getMessage());
        }
    }

    /** PE-11/PE-13: FIRST_CASH_IN / FIRST_DEBIT joining milestones and AFTER_FIRST_DEBIT reward release. */
    private void milestonesForTxn(String tenant, AccrualService.TxnEvent te) {
        if (te.customerWalletId() == null) {
            return;
        }
        try {
            TenantContext.callAs(UUID.fromString(tenant), "wallet-events", () -> {
                if ("CASH_IN".equals(te.txnType())) {
                    milestones.fire(MilestoneService.Milestone.FIRST_CASH_IN, te.customerWalletId(), te.currency(), null,
                            null, te.openedByAgentId(), te.walletTransactionId(), te.txnType(), te.completedAt());
                } else {
                    milestones.fire(MilestoneService.Milestone.FIRST_DEBIT, te.customerWalletId(), te.currency(), null,
                            null, te.openedByAgentId(), te.walletTransactionId(), te.txnType(), te.completedAt());
                    pendingRewards.releaseOnFirstDebit(te.customerWalletId());
                }
                return null;
            });
        } catch (Exception e) {
            log.warn("Milestone handling for {} skipped: {}", te.walletTransactionId(), e.getMessage());
        }
    }

    private static UUID uuid(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() || v.asString().isBlank() ? null : UUID.fromString(v.asString());
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? null : v.asString();
    }

    private static String header(ConsumerRecord<String, String> r, String name) {
        Header h = r.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
