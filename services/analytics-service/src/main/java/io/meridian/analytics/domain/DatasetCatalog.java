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
