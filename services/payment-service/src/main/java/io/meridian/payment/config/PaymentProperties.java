package io.meridian.payment.config;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * payment-service settings ({@code meridian.payment.*}).
 *
 * @param ledgerId        ledger book holding customer accounts and the internal accounts below
 * @param bankBic         our BIC (debtor agent on outbound payments)
 * @param bankCode        bank code embedded in our IBANs (on-us detection for Confirmation of Payee)
 * @param bankName        name printed on receipts
 * @param gl              internal ledger accounts
 * @param fees            flat fee per payment type in the payment currency (debtor-borne unless charge bearer CRED)
 * @param approval        business approval matrix (four-eyes thresholds)
 * @param cop             Confirmation of Payee thresholds
 * @param otp             alias ownership OTP settings
 * @param payeeCoolingOffHours new-payee limit window
 * @param linkBaseUrl     public base URL for payment links
 * @param qrGuid          globally unique id placed in EMVCo merchant account information (tag 26 sub-tag 00)
 * @param screeningEnabled call sanctions screening for non-internal payments
 * @param limitsEnabled   reserve limits for customer payments
 * @param refundDays      debtor's right-to-refund window for direct debits (SEPA Core: 8 weeks)
 * @param fraud           fraud scoring of outbound and internal payments before release (fraud-service)
 * @param walletFundingServices calling services (internal token + X-Meridian-Service) that may initiate wallet-funded
 *                        payouts (debtor_wallet_id + debtor_clearing_ledger_account_id, no deposit account)
 * @param etm             Order &amp; Pay (ETM, WE-ETM-1 / G-08): order-vs-payment-vs-fulfilment segregation and the
 *                        ambiguous-case settlement resolver; disabled by default (preserves current behaviour)
 */
@ConfigurationProperties(prefix = "meridian.payment")
public record PaymentProperties(UUID ledgerId, String bankBic, String bankCode, String bankName, Gl gl,
                                Map<String, BigDecimal> fees, Approval approval, Cop cop, Otp otp,
                                Integer payeeCoolingOffHours, String linkBaseUrl, String qrGuid,
                                Boolean screeningEnabled, Boolean limitsEnabled, Integer refundDays, Fraud fraud,
                                List<String> walletFundingServices, Etm etm) {

    public PaymentProperties {
        bankBic = bankBic == null ? "MERIGB2LXXX" : bankBic;
        bankCode = bankCode == null ? "MERI" : bankCode;
        bankName = bankName == null ? "Meridian Bank" : bankName;
        gl = gl == null ? new Gl(null, null, null) : gl;
        fees = fees == null ? Map.of() : Map.copyOf(fees);
        approval = approval == null ? new Approval(null, null, null) : approval;
        cop = cop == null ? new Cop(null, null) : cop;
        otp = otp == null ? new Otp(null, null) : otp;
        payeeCoolingOffHours = payeeCoolingOffHours == null ? 24 : payeeCoolingOffHours;
        linkBaseUrl = linkBaseUrl == null ? "https://pay.meridian.example" : linkBaseUrl;
        qrGuid = qrGuid == null ? "IO.MERIDIAN.PAY" : qrGuid;
        screeningEnabled = screeningEnabled == null || screeningEnabled;
        limitsEnabled = limitsEnabled == null || limitsEnabled;
        refundDays = refundDays == null ? 56 : refundDays;
        fraud = fraud == null ? new Fraud(null, null, null, null, null, null, null, null) : fraud;
        walletFundingServices = walletFundingServices == null ? List.of("wallet-service") : List.copyOf(walletFundingServices);
        etm = etm == null ? new Etm(null, null, null, null, null, null, null, null, null, null, null) : etm;
    }

    /** Ledger book: the tenant's setting in config-service (namespace {@code ledger}) wins over this deployment value. */
    @Override
    public UUID ledgerId() {
        return io.meridian.web.config.TenantSettings.uuid("payment-service", "ledger_id", ledgerId);
    }

    /**
     * Fees per payment type per tenant: {@code fees} of the tenant's payment-service settings (e.g.
     * {@code {"INSTANT": "0", "CROSS_BORDER": "60000"}}) override this deployment map, in the tenant's currency.
     */
    @Override
    public Map<String, BigDecimal> fees() {
        java.util.Optional<tools.jackson.databind.JsonNode> t =
                io.meridian.web.config.TenantSettings.json("payment-service", "fees");
        if (t.isPresent() && t.get().isObject()) {
            Map<String, BigDecimal> m = new java.util.LinkedHashMap<>(fees == null ? Map.of() : fees);
            for (String type : t.get().propertyNames()) {
                try {
                    m.put(type, new BigDecimal(t.get().get(type).asString()));
                } catch (RuntimeException ignored) {
                    // a malformed tenant value keeps the deployment fee for that type
                }
            }
            return m;
        }
        return fees;
    }

    /**
     * Approval levels per tenant: {@code approval.levels} of the tenant's payment-service settings (config-service
     * namespace {@code ledger}, key {@code payment-service}; e.g. {@code [{"min_amount": "10000000", "approvals": 1}]})
     * win over this deployment value, so thresholds are expressed in the tenant's own currency (COP vs KES vs USD).
     */
    @Override
    public Approval approval() {
        java.util.Optional<tools.jackson.databind.JsonNode> t =
                io.meridian.web.config.TenantSettings.json("payment-service", "approval.levels");
        if (t.isPresent() && t.get().isArray() && !t.get().isEmpty()) {
            java.util.List<Level> levels = new java.util.ArrayList<>();
            for (tools.jackson.databind.JsonNode l : t.get()) {
                if (l.hasNonNull("min_amount")) {
                    levels.add(new Level(new BigDecimal(l.get("min_amount").asString()), l.path("approvals").asInt(1)));
                }
            }
            if (!levels.isEmpty()) {
                return new Approval(levels, approval.bulkMinApprovals(), approval.expiryHours());
            }
        }
        return approval;
    }

    /** Transaction-tax settings of the tenant ({@code taxes} of its payment-service settings); null = no taxes. */
    public tools.jackson.databind.JsonNode taxes() {
        return io.meridian.web.config.TenantSettings.json("payment-service", "taxes").orElse(null);
    }

    /** Tax payable GL of the tenant ({@code gl.tax_payable}); null when not configured. */
    public UUID taxPayable() {
        return io.meridian.web.config.TenantSettings.uuid("payment-service", "gl.tax_payable", null);
    }

    /** Time zone of the tenant's tax month ({@code taxes.timezone}, default UTC). */
    public java.time.ZoneId taxZone() {
        return io.meridian.web.config.TenantSettings.json("payment-service", "taxes.timezone")
                .map(n -> {
                    try {
                        return java.time.ZoneId.of(n.asString());
                    } catch (RuntimeException e) {
                        return java.time.ZoneOffset.UTC;
                    }
                }).orElse(java.time.ZoneOffset.UTC);
    }

    /** Internal accounts, per tenant from config-service (namespace {@code ledger}, key {@code payment-service}). */
    @Override
    public Gl gl() {
        return new Gl(io.meridian.web.config.TenantSettings.uuid("payment-service", "gl.clearing", gl.clearing()),
                io.meridian.web.config.TenantSettings.uuid("payment-service", "gl.fee_income", gl.feeIncome()),
                io.meridian.web.config.TenantSettings.uuid("payment-service", "gl.direct_debit_clearing", gl.directDebitClearing()));
    }

    /**
     * @param clearing          default rail clearing account when a rail has no settlement account
     * @param feeIncome         fee income account
     * @param directDebitClearing clearing account for direct-debit collections
     */
    public record Gl(UUID clearing, UUID feeIncome, UUID directDebitClearing) {}

    /**
     * @param levels        approval levels: a payment at or above {@code minAmount} needs that level's approvals
     * @param bulkMinApprovals approvals always required for bulk payments
     * @param expiryHours   approval window
     */
    public record Approval(List<Level> levels, Integer bulkMinApprovals, Integer expiryHours) {
        public Approval {
            levels = levels == null ? List.of() : List.copyOf(levels);
            bulkMinApprovals = bulkMinApprovals == null ? 1 : bulkMinApprovals;
            expiryHours = expiryHours == null ? 72 : expiryHours;
        }
    }

    public record Level(BigDecimal minAmount, Integer approvals) {}

    public record Cop(BigDecimal matchThreshold, BigDecimal closeMatchThreshold) {
        public Cop {
            matchThreshold = matchThreshold == null ? new BigDecimal("0.98") : matchThreshold;
            closeMatchThreshold = closeMatchThreshold == null ? new BigDecimal("0.80") : closeMatchThreshold;
        }
    }

    public record Otp(Integer ttlMinutes, Integer maxAttempts) {
        public Otp {
            ttlMinutes = ttlMinutes == null ? 10 : ttlMinutes;
            maxAttempts = maxAttempts == null ? 5 : maxAttempts;
        }
    }

    /**
     * Fraud scoring (POST /v1/risk/fraud/scores/evaluate, subject PAYMENT) after sanctions screening and before limits,
     * approvals and funds reservation.
     *
     * @param enabled            score payments (inbound credits are never scored)
     * @param timeoutMs          time budget of the evaluation; a slower answer counts as unavailable
     * @param failClosedAmount   when scoring is unavailable, a payment of at least this amount (payment currency) is
     *                           held for review (fail-closed); a smaller one continues (fail-open). 0 = always closed
     * @param channelFailClosedAmount per payment channel override of {@code failClosedAmount} (e.g. BULK_FILE: 0)
     * @param stepUpAction       what a STEP_UP decision does to a payment that cannot step up any more: REVIEW (hold,
     *                           default) or ALLOW
     * @param alertConsumerEnabled apply {@code risk.fraud_alert.resolved} of PAYMENT alerts to held payments
     * @param autoRescore        automatic re-scoring of payments held because scoring was unavailable
     * @param upstream           reuse of an evaluation made by the originating service (wallet payouts)
     */
    public record Fraud(Boolean enabled, Integer timeoutMs, BigDecimal failClosedAmount,
                        Map<String, BigDecimal> channelFailClosedAmount, String stepUpAction, Boolean alertConsumerEnabled,
                        AutoRescore autoRescore, Upstream upstream) {
        public Fraud {
            enabled = enabled == null || enabled;
            timeoutMs = timeoutMs == null ? 2000 : timeoutMs;
            failClosedAmount = failClosedAmount == null ? new BigDecimal("1000") : failClosedAmount;
            channelFailClosedAmount = channelFailClosedAmount == null ? Map.of() : Map.copyOf(channelFailClosedAmount);
            stepUpAction = stepUpAction == null ? "REVIEW" : stepUpAction;
            alertConsumerEnabled = alertConsumerEnabled == null || alertConsumerEnabled;
            autoRescore = autoRescore == null ? new AutoRescore(null, null, null, null, null, null) : autoRescore;
            upstream = upstream == null ? new Upstream(null, null) : upstream;
        }

        /**
         * Re-scoring of degraded fraud holds (same idempotency key {@code pay-fraud:<id>}) by the internal scheduler
         * (every tenant with activity) and by {@code POST /v1/payments/execute-due}, with exponential backoff.
         *
         * @param enabled        run the internal scheduler (execute-due always rescores due holds)
         * @param intervalMs     scheduler period
         * @param initialBackoff delay before the first automatic attempt after the hold
         * @param maxBackoff     cap of the doubling delay
         * @param maxAttempts    automatic attempts before the hold is left to an analyst (fraud-rescore / review)
         * @param batchSize      holds re-scored per tenant and run
         */
        public record AutoRescore(Boolean enabled, Long intervalMs, java.time.Duration initialBackoff,
                                  java.time.Duration maxBackoff, Integer maxAttempts, Integer batchSize) {
            public AutoRescore {
                enabled = enabled == null || enabled;
                intervalMs = intervalMs == null ? 60_000L : intervalMs;
                initialBackoff = initialBackoff == null ? java.time.Duration.ofMinutes(1) : initialBackoff;
                maxBackoff = maxBackoff == null ? java.time.Duration.ofHours(1) : maxBackoff;
                maxAttempts = maxAttempts == null ? 12 : maxAttempts;
                batchSize = batchSize == null ? 100 : batchSize;
            }

            /** Delay before attempt {@code attempt + 1} (attempt 0 = the first automatic one). */
            public java.time.Duration backoff(int attempt) {
                java.time.Duration d = initialBackoff.multipliedBy(1L << Math.min(Math.max(attempt, 0), 20));
                return d.compareTo(maxBackoff) > 0 ? maxBackoff : d;
            }
        }

        /**
         * Evaluations of the originating service reused instead of scoring again: accepted only from these calling
         * services (internal token + X-Meridian-Service, never a channel or user token), only a recent ALLOW of the
         * same customer, amount and currency, used by one payment at most.
         *
         * @param services    calling services whose evaluation may be reused (default wallet-service)
         * @param maxAgeSeconds how old the reused evaluation may be
         */
        public record Upstream(java.util.List<String> services, Integer maxAgeSeconds) {
            public Upstream {
                services = services == null ? java.util.List.of("wallet-service") : java.util.List.copyOf(services);
                maxAgeSeconds = maxAgeSeconds == null ? 900 : maxAgeSeconds;
            }
        }

        /** Fail-closed (hold for review) when scoring is unavailable for this channel and amount. */
        public boolean failClosed(String channel, BigDecimal amount) {
            BigDecimal threshold = channel == null ? failClosedAmount
                    : channelFailClosedAmount.getOrDefault(channel, failClosedAmount);
            return amount != null && amount.compareTo(threshold) >= 0;
        }
    }

    /**
     * Order &amp; Pay (ETM, WE-ETM-1 / G-08). Entirely opt-in: with {@code enabled} false the payment-orders resource
     * and the ambiguous-settlement resolver do nothing, so existing payment behaviour is untouched.
     *
     * @param enabled           turn Order &amp; Pay on for the tenant (default off)
     * @param scheduleEnabled   run the internal per-tenant ambiguous-settlement resolver (resolve-ambiguous is always
     *                          callable over HTTP regardless)
     * @param intervalMs        resolver scheduler period
     * @param paymentTimeout    how long an unpaid order waits before its payment deadline
     * @param fulfilmentTimeout how long after payment fulfilment may take before it is treated as timed out (a
     *                          fulfilment timeout forces the ambiguous case to auto-resolve)
     * @param heartbeatStaleAfter a payment-gateway heartbeat older than this counts as UNKNOWN
     * @param maxSettlementAttempts automatic resolver passes before an ambiguous order is left to manual intervention
     * @param settlementBackoff base delay between resolver passes (doubled per attempt, capped at one hour)
     * @param settlementSuspense WE-FIN-1 / D-ED-10: the ETM settlement-suspense GL a roll-forward's not-yet-confirmed
     *                          amount is parked in (a tracked, reconciling merchant exposure cleared by WE-RECON-1 against
     *                          the rail settlement file; a later confirmed failure clears it via the compensating reversal)
     * @param rollForwardSuspense WE-FIN-1 / D-ED-10: on ROLL_FORWARD, record the not-yet-confirmed amount as a settlement
     *                          -suspense exposure on the order (a field + event) instead of a silent write-off; default
     *                          true. Observational only (no money moves here; the GL leg is a documented host wiring note)
     * @param autoOpenRepair    WE-FIN-1 / D-ED-10: when an order's alert reaches RED (manual intervention), auto-open a
     *                          payment_repair item in addition to the alert (reuses the existing repair flow); default true
     */
    public record Etm(Boolean enabled, Boolean scheduleEnabled, Long intervalMs, java.time.Duration paymentTimeout,
                      java.time.Duration fulfilmentTimeout, java.time.Duration heartbeatStaleAfter,
                      Integer maxSettlementAttempts, java.time.Duration settlementBackoff, UUID settlementSuspense,
                      Boolean rollForwardSuspense, Boolean autoOpenRepair) {
        public Etm {
            enabled = enabled != null && enabled;
            scheduleEnabled = scheduleEnabled != null && scheduleEnabled;
            intervalMs = intervalMs == null ? 60_000L : intervalMs;
            paymentTimeout = paymentTimeout == null ? java.time.Duration.ofMinutes(30) : paymentTimeout;
            fulfilmentTimeout = fulfilmentTimeout == null ? java.time.Duration.ofHours(24) : fulfilmentTimeout;
            heartbeatStaleAfter = heartbeatStaleAfter == null ? java.time.Duration.ofMinutes(5) : heartbeatStaleAfter;
            maxSettlementAttempts = maxSettlementAttempts == null ? 5 : maxSettlementAttempts;
            settlementBackoff = settlementBackoff == null ? java.time.Duration.ofMinutes(2) : settlementBackoff;
            rollForwardSuspense = rollForwardSuspense == null || rollForwardSuspense;
            autoOpenRepair = autoOpenRepair == null || autoOpenRepair;
        }

        /** The ETM settlement-suspense GL, overridable per tenant via config-service (namespace {@code ledger}). */
        public UUID settlementSuspense() {
            return io.meridian.web.config.TenantSettings.uuid("payment-service", "gl.etm_settlement_suspense",
                    settlementSuspense);
        }
    }
}
