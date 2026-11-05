package io.meridian.analytics.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Whitelisted analytics datasets. Each is read from the owning service's list API (tenant-scoped by the service's
 * row-level security) and projected to its declared fields only; personal data (names, account numbers, addresses)
 * is never part of a dataset.
 */
public final class DatasetCatalog {
    private DatasetCatalog() {}

    /** Field type: STRING, NUMBER, DATE, TIMESTAMP or BOOL. */
    public record Dataset(String name, String description, String service, String path, String timeField,
                          Map<String, String> fields) {}

    private static final Map<String, Dataset> DATASETS = new LinkedHashMap<>();

    static {
        add(new Dataset("deposits.accounts", "Deposit accounts", "deposit-service", "/v1/deposit-accounts", "created_at",
                fields("id", "STRING", "account_type", "STRING", "customer_id", "STRING", "product_id", "STRING",
                        "legal_entity_id", "STRING", "branch_id", "STRING", "currency", "STRING", "status", "STRING",
                        "created_at", "TIMESTAMP")));
        add(new Dataset("loans.loans", "Loan portfolio", "loan-service", "/v1/loans", "start_date",
                fields("id", "STRING", "customer_id", "STRING", "product_id", "STRING", "legal_entity_id", "STRING",
                        "branch_id", "STRING", "loan_type", "STRING", "currency", "STRING", "principal_amount", "NUMBER",
                        "outstanding_principal", "NUMBER", "total_outstanding", "NUMBER", "overdue_amount", "NUMBER",
                        "interest_rate", "NUMBER", "term_months", "NUMBER", "days_past_due", "NUMBER", "status", "STRING",
                        "start_date", "DATE", "maturity_date", "DATE", "created_at", "TIMESTAMP")));
        add(new Dataset("payments.payments", "Payments", "payment-service", "/v1/payments", "created_at",
                fields("id", "STRING", "payment_type", "STRING", "direction", "STRING", "status", "STRING", "channel", "STRING",
                        "customer_id", "STRING", "amount", "NUMBER", "currency", "STRING", "debtor_country", "STRING",
                        "creditor_country", "STRING", "created_at", "TIMESTAMP")));
        add(new Dataset("customers.customers", "Customers (no personal data)", "customer-service", "/v1/customers",
                "created_at", fields("id", "STRING", "customer_type", "STRING", "status", "STRING", "kyc_tier", "STRING",
                        "risk_level", "STRING", "legal_entity_id", "STRING", "residence_country", "STRING",
                        "onboarding_channel", "STRING", "created_at", "TIMESTAMP")));
        add(new Dataset("gl.balances", "General ledger balances per period", "gl-service", "/v1/gl/balances", null,
                fields("id", "STRING", "gl_account_id", "STRING", "accounting_period_id", "STRING", "currency", "STRING",
                        "opening_balance", "NUMBER", "debits", "NUMBER", "credits", "NUMBER", "closing_balance", "NUMBER")));

        // Cards (card-service): one row per issued card, lifecycle status and linkage (no PAN / cardholder data).
        add(new Dataset("cards.cards", "Payment cards (no PAN or cardholder data)", "card-service", "/v1/cards", "created_at",
                fields("id", "STRING", "card_program_id", "STRING", "card_product_id", "STRING", "customer_id", "STRING",
                        "form_factor", "STRING", "status", "STRING", "linked_account_type", "STRING", "pin_set", "BOOL",
                        "created_at", "TIMESTAMP", "activated_at", "TIMESTAMP", "frozen_at", "TIMESTAMP", "closed_at", "TIMESTAMP")));

        // Agent network (agent-network-service): agents and their float-position configuration.
        add(new Dataset("agents.agents", "Agents", "agent-network-service", "/v1/agents", "created_at",
                fields("id", "STRING", "agent_code", "STRING", "agent_level", "STRING", "status", "STRING",
                        "risk_rating", "STRING", "kyc_status", "STRING", "currency", "STRING", "category_id", "STRING",
                        "grade_id", "STRING", "branch_id", "STRING", "distribution_domain_id", "STRING",
                        "commission_plan_id", "STRING", "max_float_amount", "NUMBER", "contract_start", "DATE",
                        "contract_end", "DATE", "created_at", "TIMESTAMP", "onboarded_at", "TIMESTAMP",
                        "terminated_at", "TIMESTAMP")));
        add(new Dataset("agents.floats", "Agent float positions (last-seen balance, not a live ledger balance)",
                "agent-network-service", "/v1/agent-floats", "created_at",
                fields("id", "STRING", "agent_id", "STRING", "agent_outlet_id", "STRING", "wallet_id", "STRING",
                        "currency", "STRING", "auto_rebalance", "BOOL", "min_balance", "NUMBER", "max_balance", "NUMBER",
                        "low_float_alert_threshold", "NUMBER", "rebalance_amount", "NUMBER", "last_balance", "NUMBER",
                        "created_at", "TIMESTAMP", "last_checked_at", "TIMESTAMP", "last_alert_at", "TIMESTAMP")));

        // Merchants (merchant-service).
        add(new Dataset("merchants.merchants", "Merchants", "merchant-service", "/v1/merchants", "created_at",
                fields("id", "STRING", "merchant_code", "STRING", "merchant_type", "STRING", "mcc", "STRING",
                        "status", "STRING", "settlement_frequency", "STRING", "risk_rating", "STRING", "country", "STRING",
                        "currency", "STRING", "daily_limit", "NUMBER", "customer_id", "STRING", "category_id", "STRING",
                        "grade_id", "STRING", "acquiring_agent_id", "STRING", "created_at", "TIMESTAMP",
                        "onboarded_at", "TIMESTAMP")));

        // Biller catalogue and the bill-payment / airtime-topup transaction books (biller-service).
        add(new Dataset("billers.billers", "Biller catalogue", "biller-service", "/v1/billers", "created_at",
                fields("id", "STRING", "biller_code", "STRING", "category", "STRING", "country", "STRING",
                        "currency", "STRING", "integration_type", "STRING", "supports_inquiry", "BOOL",
                        "supports_partial_payment", "BOOL", "supports_overpayment", "BOOL", "min_amount", "NUMBER",
                        "max_amount", "NUMBER", "settlement_frequency", "STRING", "fee_bearer", "STRING", "status", "STRING",
                        "created_at", "TIMESTAMP", "updated_at", "TIMESTAMP")));
        add(new Dataset("billers.bill_payments", "Bill-payment transactions (no account reference / payee data)",
                "biller-service", "/v1/bill-payments", "created_at",
                fields("id", "STRING", "biller_id", "STRING", "biller_product_id", "STRING", "payer_wallet_id", "STRING",
                        "channel", "STRING", "amount", "NUMBER", "currency", "STRING", "fee_amount", "NUMBER",
                        "status", "STRING", "retry_count", "NUMBER", "created_at", "TIMESTAMP", "completed_at", "TIMESTAMP",
                        "reversed_at", "TIMESTAMP")));
        add(new Dataset("billers.airtime_topups", "Airtime / data top-up transactions (no MSISDN)",
                "biller-service", "/v1/airtime-topups", "created_at",
                fields("id", "STRING", "biller_id", "STRING", "biller_product_id", "STRING", "payer_wallet_id", "STRING",
                        "channel", "STRING", "amount", "NUMBER", "currency", "STRING", "status", "STRING",
                        "retry_count", "NUMBER", "created_at", "TIMESTAMP", "completed_at", "TIMESTAMP")));

        // Risk / investigation cases (case-management-service).
        add(new Dataset("cases.cases", "Risk investigation cases (AML / fraud / sanctions)", "case-management-service",
                "/v1/risk/cases", "opened_at",
                fields("id", "STRING", "case_number", "STRING", "case_type", "STRING", "status", "STRING",
                        "priority", "STRING", "customer_id", "STRING", "risk_score", "NUMBER", "total_amount", "NUMBER",
                        "currency", "STRING", "assigned_to", "STRING", "team", "STRING", "escalated_to", "STRING",
                        "opened_at", "TIMESTAMP", "created_at", "TIMESTAMP", "due_at", "TIMESTAMP", "closed_at", "TIMESTAMP")));

        // Treasury money-market deals (treasury-deal-service).
        add(new Dataset("treasury.money_market_deals", "Money-market treasury deals", "treasury-deal-service",
                "/v1/treasury/money-market-deals", "trade_date",
                fields("id", "STRING", "legal_entity_id", "STRING", "treasury_counterparty_id", "STRING",
                        "deal_type", "STRING", "status", "STRING", "currency", "STRING", "rate_type", "STRING",
                        "rate_index", "STRING", "rollover_type", "STRING", "is_call", "BOOL", "principal", "NUMBER",
                        "interest_rate", "NUMBER", "spread", "NUMBER", "interest_amount", "NUMBER",
                        "maturity_amount", "NUMBER", "accrued_interest", "NUMBER", "trade_date", "DATE",
                        "value_date", "DATE", "maturity_date", "DATE", "created_at", "TIMESTAMP", "settled_at", "TIMESTAMP",
                        "matured_at", "TIMESTAMP")));

        // FX conversion deals and firm quotes (fx-service).
        add(new Dataset("fx.deals", "Booked FX conversion deals", "fx-service", "/v1/fx/deals", "created_at",
                fields("id", "STRING", "fx_quote_id", "STRING", "payment_id", "STRING", "side", "STRING",
                        "sell_currency", "STRING", "sell_amount", "NUMBER", "buy_currency", "STRING", "buy_amount", "NUMBER",
                        "rate", "NUMBER", "value_date", "DATE", "liquidity_provider", "STRING", "status", "STRING",
                        "created_at", "TIMESTAMP")));
        add(new Dataset("fx.quotes", "FX price quotes (incl. remittance corridor)", "fx-service", "/v1/fx/quotes", "created_at",
                fields("id", "STRING", "customer_id", "STRING", "segment", "STRING", "channel", "STRING",
                        "sell_currency", "STRING", "buy_currency", "STRING", "sell_amount", "NUMBER", "buy_amount", "NUMBER",
                        "mid_rate", "NUMBER", "customer_rate", "NUMBER", "margin_bps", "NUMBER", "fee_amount", "NUMBER",
                        "fee_currency", "STRING", "remittance_corridor_id", "STRING", "remittance_partner_id", "STRING",
                        "total_debit", "NUMBER", "rate_source", "STRING", "status", "STRING", "created_at", "TIMESTAMP",
                        "expires_at", "TIMESTAMP")));
    }

    private static void add(Dataset d) {
        DATASETS.put(d.name(), d);
    }

    private static Map<String, String> fields(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    public static Optional<Dataset> find(String name) {
        return Optional.ofNullable(name == null ? null : DATASETS.get(name));
    }

    public static Map<String, Dataset> all() {
        return DATASETS;
    }
}
