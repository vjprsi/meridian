package io.meridian.wallet.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.wallet.config.WalletProperties;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Adapters to the non-ledger services wallet-service depends on: limits-service (usage reservations), fraud-service
 * (synchronous scoring), notification-service (OTPs, statements), deposit-service (on-us bank accounts) and
 * payment-service (outbound bank transfers).
 */
@Component
public class Downstream {
    private static final Logger log = LoggerFactory.getLogger(Downstream.class);

    private final ServiceClient client;
    private final WalletProperties props;
    private final JsonMapper json;

    public Downstream(ServiceClient client, WalletProperties props, JsonMapper json) {
        this.client = client;
        this.props = props;
        this.json = json;
    }

    // ------------------------------------------------------------------ risk

    /** Outcome of a limit reservation: allowed + reservation id (null when the check was skipped). */
    public record LimitCheck(boolean allowed, UUID id, String reason) {}

    public LimitCheck reserveLimits(String key, UUID walletId, UUID customerId, String txnType, String channel,
                                    BigDecimal amount, String currency, String correlationId) {
        return reserveLimits(key, walletId, customerId, txnType, channel, amount, currency, correlationId,
                null, null, null);
    }

    /**
     * WE-LIMIT-1: reserves the payer (debit) side and, when a payee wallet is given (wallet-to-wallet), also the
     * payee (credit) side so the central engine counts both against their own cumulative/instrument TCP. The wallet
     * is always a {@code WALLET} instrument; all new fields are additive, so when no TCP is configured the request
     * and result are identical to the single-sided debit check. {@code wallet_tier_limit} stays a local non-counting
     * pre-check (authoritative reservation is central) to avoid double-counting during cut-over.
     */
    public LimitCheck reserveLimits(String key, UUID walletId, UUID customerId, String txnType, String channel,
                                    BigDecimal amount, String currency, String correlationId, UUID payeeWalletId,
                                    UUID payeeCustomerId, BigDecimal payeeBalance) {
        if (!props.risk().limitsEnabled()) {
            return new LimitCheck(true, null, null);
        }
        ObjectNode body = json.createObjectNode();
        body.put("subject_type", "WALLET");
        body.put("subject_id", walletId.toString());
        if (customerId != null) {
            body.put("customer_id", customerId.toString());
        }
        body.put("transaction_type", txnType);
        String rc = riskChannel(channel);
        if (rc != null) {
            body.put("channel", rc);
        }
        body.put("amount", amount.toPlainString());
        body.put("currency", currency);
        body.put("correlation_id", correlationId);
        body.put("direction", "DEBIT");
        body.put("payment_instrument", "WALLET");
        if (payeeWalletId != null) {
            body.put("payee_subject_type", "WALLET");
            body.put("payee_subject_id", payeeWalletId.toString());
            body.put("payee_payment_instrument", "WALLET");
            if (payeeCustomerId != null) {
                body.put("payee_customer_id", payeeCustomerId.toString());
            }
            if (payeeBalance != null) {
                body.put("payee_current_balance", payeeBalance.toPlainString());
            }
        }
        body.put("allowed", true);
        body.put("status", "RESERVED");
        body.putObject("evaluated_rule_ids");
        try {
            JsonNode r = client.post("limits-service", "/v1/risk/limits/checks", body, key);
            boolean allowed = r.path("allowed").asBoolean(true) && !"REJECTED".equals(r.path("status").asString(""));
            String id = r.path("id").asString(null);
            return new LimitCheck(allowed, id == null ? null : UUID.fromString(id),
                    allowed ? null : "Transaction exceeds a risk limit" + breached(r));
        } catch (ApiException e) {
            if (e.status() >= 500 && props.risk().failOpen()) {
                log.warn("limits-service unavailable, failing open: {}", e.getMessage());
                return new LimitCheck(true, null, null);
            }
            throw e;
        }
    }

    public void commitLimits(UUID checkId, BigDecimal amount, String key) {
        if (checkId == null) {
            return;
        }
        ObjectNode body = json.createObjectNode();
        body.put("amount", amount.toPlainString());
        safely(() -> client.post("limits-service", "/v1/risk/limits/checks/" + checkId + "/commit", body, key));
    }

    public void releaseLimits(UUID checkId, String reason, String key) {
        if (checkId == null) {
            return;
        }
        ObjectNode body = json.createObjectNode();
        body.put("reason", reason == null ? "released" : reason.length() > 255 ? reason.substring(0, 255) : reason);
        safely(() -> client.post("limits-service", "/v1/risk/limits/checks/" + checkId + "/release", body, key));
    }

    /** Fraud decision: ALLOW, REVIEW, BLOCK or STEP_UP plus score (0..1) and the evaluation id. */
    public record FraudResult(String decision, BigDecimal score, UUID id) {
        public static final FraudResult SKIPPED = new FraudResult(null, null, null);
    }

    public FraudResult score(String key, UUID transactionId, UUID customerId, UUID walletLedgerAccount, BigDecimal amount,
                             String currency, String channel, String ip, JsonNode context) {
        if (!props.risk().fraudEnabled()) {
            return FraudResult.SKIPPED;
        }
        ObjectNode body = json.createObjectNode();
        body.put("subject_type", "WALLET_TRANSACTION");
        body.put("subject_id", transactionId.toString());
        if (customerId != null) {
            body.put("customer_id", customerId.toString());
        }
        if (walletLedgerAccount != null) {
            body.put("account_id", walletLedgerAccount.toString());
        }
        body.put("amount", amount.toPlainString());
        body.put("currency", currency);
        String rc = riskChannel(channel);
        if (rc != null) {
            body.put("channel", rc);
        }
        if (ip != null) {
            body.put("ip_address", ip);
        }
        if (context != null) {
            body.set("context", context);
        }
        try {
            JsonNode r = client.post("fraud-service", "/v1/risk/fraud/scores/evaluate", body, key);
            String id = r.path("fraud_score_id").asString(r.path("id").asString(null));
            BigDecimal score = r.hasNonNull("score")
                    ? new BigDecimal(r.get("score").asString()).divide(BigDecimal.valueOf(1000), 4, java.math.RoundingMode.HALF_EVEN)
                    : null;
            return new FraudResult(r.path("decision").asString("ALLOW"), score, id == null ? null : UUID.fromString(id));
        } catch (ApiException e) {
            if (e.status() >= 500 && props.risk().failOpen()) {
                log.warn("fraud-service unavailable, failing open: {}", e.getMessage());
                return FraudResult.SKIPPED;
            }
            throw e;
        }
    }

    static String riskChannel(String walletChannel) {
        if (walletChannel == null) {
            return null;
        }
        return switch (walletChannel) {
            case "USSD", "SMS" -> "USSD";
            case "APP", "QR" -> "MOBILE_APP";
            case "WEB" -> "WEB";
            case "AGENT_APP" -> "AGENT";
            case "POS" -> "POS";
            default -> "API";
        };
    }

    private static String breached(JsonNode r) {
        JsonNode b = r.get("breached_rule_ids");
        return b == null || b.isNull() || b.isEmpty() ? "" : " (" + b + ")";
    }

    // ------------------------------------------------------------------ notifications

    /** Sends an SMS through notification-service and returns the notification id. */
    public UUID sendSms(String key, String recipientType, UUID recipientId, String msisdn, String category, String body) {
        ObjectNode n = json.createObjectNode();
        n.put("recipient_type", recipientType);
        if (recipientId != null) {
            n.put("recipient_id", recipientId.toString());
        }
        n.put("channel", "SMS");
        n.put("destination", msisdn);
        n.put("category", category);
        n.put("body", body);
        JsonNode r = client.post("notification-service", "/v1/notifications", n, key);
        String id = r.path("id").asString(null);
        return id == null ? null : UUID.fromString(id);
    }

    // ------------------------------------------------------------------ hierarchy

    /**
     * WE-TXN-1: the tenant's singleton un-registered grade (WE-HIER-1) classifying an unregistered beneficiary, from
     * agent-network-service (which lazily bootstraps it). Best-effort: null when the lookup fails, so voucher issuance
     * is never blocked by a classification call.
     */
    public UUID unregisteredGrade() {
        try {
            JsonNode g = client.get("agent-network-service", "/v1/grades/unregistered");
            String id = g.path("id").asString(null);
            return id == null ? null : UUID.fromString(id);
        } catch (RuntimeException e) {
            log.warn("un-registered grade lookup failed, leaving voucher grade unset: {}", e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------ banks

    public JsonNode depositAccount(UUID id) {
        return client.get("deposit-service", "/v1/deposit-accounts/" + id);
    }

    /**
     * Creates an outbound credit transfer in payment-service for a wallet-to-bank transfer to another bank, with
     * wallet-service's own identity (service token + X-Meridian-Service): payment-service debits the bank clearing
     * account the wallet transaction credited, and reuses the wallet's ALLOW evaluation ({@code upstream_fraud_score_id})
     * instead of scoring the same money movement twice. Both fields are honoured only from wallet-service.
     *
     * @param fraudScoreId the wallet transaction's evaluation when it was an ALLOW (null = payment-service scores it)
     */
    public UUID outboundPayment(String key, JsonNode link, BigDecimal amount, String currency, String reference,
                                UUID walletId, String debtorName, UUID fraudScoreId) {
        ObjectNode p = json.createObjectNode();
        p.put("payment_type", "DOMESTIC");
        p.put("direction", "OUTBOUND");
        p.put("channel", "API");
        p.put("creditor_name", Optional.ofNullable(Rows.str(link, "account_name")).orElse(debtorName));
        p.put("creditor_account_id_type", Rows.str(link, "account_number").matches("[A-Z]{2}\\d{2}.*") ? "IBAN" : "ACCOUNT_NUMBER");
        p.put("creditor_account_number", Rows.str(link, "account_number"));
        p.put("creditor_agent_clearing_id", Rows.str(link, "bank_code"));
        p.put("amount", amount.toPlainString());
        p.put("currency", currency);
        p.put("end_to_end_id", reference);
        p.put("reference", reference);
        p.put("debtor_wallet_id", walletId.toString());
        p.put("debtor_name", debtorName);
        if (props.gl().bankClearing() != null) {
            p.put("debtor_clearing_ledger_account_id", props.gl().bankClearing().toString());
        }
        if (fraudScoreId != null) {
            p.put("upstream_fraud_score_id", fraudScoreId.toString());
        }
        p.put("remittance_unstructured", "Wallet transfer " + reference);
        JsonNode r = ServiceClient.asService(() -> client.post("payment-service", "/v1/payments", p, key));
        String id = r.path("id").asString(null);
        return id == null ? null : UUID.fromString(id);
    }

    private static void safely(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            log.warn("risk reservation update failed (reservation expires automatically): {}", e.getMessage());
        }
    }
}
