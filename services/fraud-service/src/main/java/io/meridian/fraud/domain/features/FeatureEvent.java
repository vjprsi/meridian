package io.meridian.fraud.domain.features;

import io.meridian.fraud.domain.ListValues;
import io.meridian.fraud.domain.Pseudonyms;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * One domain event as the feature store sees it: when it happened, its amount and the entities it touches.
 *
 * @param counters entity type -> key whose velocity counters the event increments
 * @param profiles entity type -> key whose behaviour profile the event updates (CUSTOMER with amount, MERCHANT /
 *                 BENEFICIARY without)
 * @param links    entity pairs seen together
 */
public record FeatureEvent(String eventId, String type, Instant occurredAt, BigDecimal amount, String currency,
                           Map<String, String> counters, Map<String, String> profiles, List<Link> links) {
    /** No-amount currency code (ISO 4217 XXX). */
    public static final String NO_CURRENCY = "XXX";

    public record Link(String type, String from, String to) {}

    public static final String PAYMENT_INITIATED = "payments.payment.initiated";
    public static final String CARD_APPROVED = "cards.card_authorization.approved";
    public static final String CARD_DECLINED = "cards.card_authorization.declined";
    public static final String WALLET_INITIATED = "wallets.wallet_transaction.initiated";
    public static final String FRAUD_EVALUATED = "risk.fraud_score.evaluated";

    public static final List<String> TYPES = List.of(PAYMENT_INITIATED, CARD_APPROVED, CARD_DECLINED, WALLET_INITIATED,
            FRAUD_EVALUATED);

    /**
     * Maps a CloudEvent to a feature event; empty when the event is not a feature source (other types, inbound
     * payments, events without any entity).
     *
     * @param eventTime ce_time, used when the payload carries no created_at
     * @param ps        the event tenant's pseudonyms (beneficiary account numbers and MSISDNs are keyed by pseudonym)
     */
    public static Optional<FeatureEvent> from(String eventId, String type, Instant eventTime, JsonNode p, Pseudonyms ps) {
        if (eventId == null || type == null || p == null || !p.isObject()) {
            return Optional.empty();
        }
        Instant at = instant(p, "created_at", eventTime);
        if (at == null) {
            return Optional.empty();
        }
        Map<String, String> counters = new LinkedHashMap<>();
        Map<String, String> profiles = new LinkedHashMap<>();
        List<Link> links = new ArrayList<>();
        BigDecimal amount;
        String currency;
        switch (type) {
            case PAYMENT_INITIATED -> {
                if (p.hasNonNull("direction") && !"OUTBOUND".equals(p.get("direction").asString())) {
                    return Optional.empty();
                }
                amount = dec(p, "amount");
                currency = str(p, "currency");
                String customer = str(p, "customer_id");
                String account = coalesce(str(p, "debtor_account_id"), str(p, "debtor_wallet_id"));
                String merchant = str(p, "creditor_merchant_id");
                String beneficiary = Keys.beneficiary(str(p, "payee_id"), Keys.accountDigest(str(p, "creditor_account_number")),
                        str(p, "creditor_agent_bic"), str(p, "creditor_wallet_id"), null, ps);
                put(counters, "CUSTOMER", customer);
                put(counters, "ACCOUNT", account);
                put(counters, "MERCHANT", merchant);
                put(counters, "BENEFICIARY", beneficiary);
                put(profiles, "CUSTOMER", customer);
                put(profiles, "MERCHANT", merchant);
                put(profiles, "BENEFICIARY", beneficiary);
                link(links, "CUSTOMER_BENEFICIARY", customer, beneficiary);
                link(links, "ACCOUNT_BENEFICIARY", account, beneficiary);
                link(links, "CUSTOMER_COUNTRY", customer, upper(str(p, "creditor_country")));
                link(links, "CUSTOMER_MERCHANT", customer, merchant);
            }
            case CARD_APPROVED, CARD_DECLINED -> {
                amount = coalesce(dec(p, "billing_amount"), dec(p, "transaction_amount"));
                currency = p.hasNonNull("billing_amount") ? str(p, "billing_currency") : str(p, "transaction_currency");
                String customer = str(p, "customer_id");
                String card = str(p, "card_id");
                String merchant = str(p, "merchant_id");
                String country = upper(str(p, "merchant_country"));
                put(counters, "CUSTOMER", customer);
                put(counters, "CARD", card);
                put(counters, "MERCHANT", merchant);
                put(profiles, "CUSTOMER", customer);
                put(profiles, "MERCHANT", merchant);
                link(links, "CARD_MERCHANT", card, merchant);
                link(links, "CARD_COUNTRY", card, country);
                link(links, "CUSTOMER_MERCHANT", customer, merchant);
                link(links, "CUSTOMER_COUNTRY", customer, country);
            }
            case WALLET_INITIATED -> {
                amount = dec(p, "amount");
                currency = str(p, "currency");
                String customer = coalesce(str(p, "customer_id"), str(p.path("metadata"), "customer_id"));
                String wallet = str(p, "payer_wallet_id");
                String merchant = str(p, "merchant_id");
                String beneficiary = Keys.beneficiary(null, null, null, str(p, "payee_wallet_id"), str(p, "payee_msisdn"), ps);
                put(counters, "CUSTOMER", customer);
                put(counters, "ACCOUNT", wallet);
                put(counters, "MERCHANT", merchant);
                put(counters, "BENEFICIARY", beneficiary);
                put(profiles, "CUSTOMER", customer);
                put(profiles, "MERCHANT", merchant);
                put(profiles, "BENEFICIARY", beneficiary);
                link(links, "CUSTOMER_BENEFICIARY", customer, beneficiary);
                link(links, "ACCOUNT_BENEFICIARY", wallet, beneficiary);
                link(links, "CUSTOMER_MERCHANT", customer, merchant);
            }
            case FRAUD_EVALUATED -> {
                // our own scoring events: device velocity, logins and customer-device history
                String customer = str(p, "customer_id");
                String device = str(p, "device_fingerprint_id");
                boolean login = "LOGIN".equals(str(p, "subject_type"));
                amount = login ? null : dec(p, "amount");
                currency = login ? null : str(p, "currency");
                put(counters, "DEVICE", device);
                if (login) {
                    put(counters, "CUSTOMER_LOGIN", customer);
                }
                link(links, "CUSTOMER_DEVICE", customer, device);
            }
            default -> {
                return Optional.empty();
            }
        }
        if (counters.isEmpty() && links.isEmpty()) {
            return Optional.empty();
        }
        if (amount != null && (amount.signum() < 0 || currency == null || currency.length() != 3)) {
            amount = null;
        }
        return Optional.of(new FeatureEvent(eventId, type, at, amount, amount == null ? NO_CURRENCY : currency,
                Map.copyOf(counters), Map.copyOf(profiles), List.copyOf(links)));
    }

    /** Stable keys for entities; entities identified by personal data are keyed by tenant-keyed pseudonyms. */
    public static final class Keys {
        private Keys() {}

        /** Legacy unkeyed beneficiary keys ({@code acct:} / {@code msisdn:} + 40 hex of a plain SHA-256). */
        public static final java.util.regex.Pattern LEGACY = java.util.regex.Pattern.compile("^(acct|msisdn):[0-9a-f]{40}$");

        /**
         * Beneficiary key: the payee id, else the pseudonym of the account number (+ BIC), else the wallet id, else
         * the pseudonym of the MSISDN digits. Scoring derives the same key from the evaluate request's context.
         *
         * @param accountDigest the account number's canonical digest ({@link #accountDigest}), never the clear number
         */
        public static String beneficiary(String payeeId, String accountDigest, String bic, String walletId, String msisdn,
                                         Pseudonyms ps) {
            if (payeeId != null && !payeeId.isBlank()) {
                return "payee:" + payeeId;
            }
            if (accountDigest != null && !accountDigest.isBlank()) {
                return ps.accountKey(accountDigest, bic);
            }
            if (walletId != null && !walletId.isBlank()) {
                return "wallet:" + walletId;
            }
            if (msisdn != null && !msisdn.isBlank()) {
                return ps.msisdnKey(msisdn);
            }
            return null;
        }

        /** Canonical digest of a beneficiary account number (as payment-service sends it), or null. */
        public static String accountDigest(String account) {
            return account == null || account.isBlank() ? null : ListValues.hash("BENEFICIARY_ACCOUNT", account);
        }
    }

    private static void put(Map<String, String> m, String type, String key) {
        if (key != null && !key.isBlank() && key.length() <= 128) {
            m.put(type, key);
        }
    }

    private static void link(List<Link> links, String type, String from, String to) {
        if (from != null && to != null && !from.isBlank() && !to.isBlank() && from.length() <= 128 && to.length() <= 128) {
            links.add(new Link(type, from, to));
        }
    }

    static String str(JsonNode n, String f) {
        return n != null && n.hasNonNull(f) && !n.get(f).isContainer() ? n.get(f).asString() : null;
    }

    private static BigDecimal dec(JsonNode n, String f) {
        String s = str(n, f);
        try {
            return s == null || s.isBlank() ? null : new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Instant instant(JsonNode n, String f, Instant fallback) {
        String s = str(n, f);
        if (s != null) {
            try {
                return java.time.OffsetDateTime.parse(s).toInstant();
            } catch (java.time.format.DateTimeParseException e) {
                // fall through to the CloudEvent time
            }
        }
        return fallback;
    }

    private static String upper(String s) {
        return s == null ? null : s.trim().toUpperCase(Locale.ROOT);
    }

    @SafeVarargs
    private static <T> T coalesce(T... vs) {
        for (T v : vs) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }
}
