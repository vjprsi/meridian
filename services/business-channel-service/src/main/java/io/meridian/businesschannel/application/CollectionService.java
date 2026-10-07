package io.meridian.businesschannel.application;

import static io.meridian.businesschannel.application.CurrentUser.business;

import io.meridian.businesschannel.application.Access.Need;
import io.meridian.businesschannel.application.Downstream.Query;
import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Collections of the business: direct-debit mandates (the business collects as creditor) and their collections,
 * virtual accounts routing credits to its accounts, and payment links. Every write needs a MAKER or ADMIN entitled to
 * initiate on the account credited; bodies are rebuilt here (customer, currency and direction are never taken from the
 * request). Creating a mandate, a collection, a virtual account or a payment link goes through the approval matrix
 * when a rule of its type covers it ({@link OperationApprovals}); otherwise it acts immediately.
 */
@Service
public class CollectionService {
    static final String PAYMENTS = "payment-service";
    static final String DEPOSITS = "deposit-service";
    static final Set<String> SEQUENCE_TYPES = Set.of("FRST", "RCUR", "FNAL", "OOFF");

    private final Downstream down;
    private final Access access;
    private final StepUp stepUp;
    private final ActionAudit audit;
    private final OperationApprovals approvals;

    public CollectionService(Downstream down, Access access, StepUp stepUp, ActionAudit audit,
                             OperationApprovals approvals) {
        this.down = down;
        this.access = access;
        this.stepUp = stepUp;
        this.audit = audit;
        this.approvals = approvals;
    }

    // ------------------------------------------------------------------ mandates

    public JsonNode mandates(Integer limit, String cursor) {
        return down.page(down.get(PAYMENTS, "/v1/payment-mandates", Query.of("customer_id", business()).paging(limit, cursor)),
                r -> Access.ofBusiness(r, "customer_id"));
    }

    public JsonNode mandate(UUID id) {
        return access.mandate(id, "businessMandatesGet");
    }

    public OperationApprovals.Outcome createMandate(JsonNode req, String key) {
        String action = "businessMandatesCreate";
        access.requireRole(action, "MANDATE", null, "ADMIN", "MAKER");
        UUID creditorAccount = Req.uuid(req, "creditor_account_id", true);
        JsonNode account = access.account(creditorAccount, Need.INITIATE, action);
        JsonNode customer = down.get("customer-service", "/v1/customers/" + business());
        String creditorName = customer.path("legal_name").asString(customer.path("display_name").asString("Business"));
        ObjectNode body = down.json().createObjectNode()
                .put("scheme", Req.required(req, "scheme"))
                .put("mandate_reference", Req.required(req, "mandate_reference"))
                .put("direction", "OUTBOUND")
                .put("customer_id", business().toString())
                .put("debtor_name", Req.required(req, "debtor_name"))
                .put("debtor_account_number", Req.required(req, "debtor_account_number").replaceAll("\\s+", "").toUpperCase())
                .put("debtor_agent_bic", Req.text(req, "debtor_agent_bic"))
                .put("creditor_name", creditorName.length() > 140 ? creditorName.substring(0, 140) : creditorName)
                .put("creditor_id", Req.required(req, "creditor_id"))
                .put("creditor_account_id", creditorAccount.toString())
                .put("frequency", Req.text(req, "frequency") == null ? "ADHOC" : Req.text(req, "frequency"))
                .put("currency", account.path("currency").asString())
                .put("signature_channel", "ONLINE");
        BigDecimal max = Req.decimal(req, "max_amount");
        if (max != null) {
            body.put("max_amount", Req.amount(req, "max_amount").toPlainString());
        }
        for (String f : List.of("first_collection_date", "final_collection_date")) {
            LocalDate d = Req.date(req, f);
            if (d != null) {
                body.put(f, d.toString());
            }
        }
        if (Req.text(req, "signed_at") != null) {
            body.put("signed_at", Req.text(req, "signed_at"));
        }
        stepUp.require(action, "ACCOUNT", creditorAccount);
        OperationApprovals.Outcome created = approvals.run("MANDATE", account, max, "Mandate "
                + body.path("mandate_reference").asString(), PAYMENTS, "/v1/payment-mandates", body, key, "create");
        audit.succeeded(action, "ACCOUNT", creditorAccount, created.body().path("id").asString(null));
        return created;
    }

    public JsonNode cancelMandate(UUID id, JsonNode req, String key) {
        String action = "businessMandatesCancel";
        access.requireRole(action, "MANDATE", id, "ADMIN", "MAKER");
        JsonNode m = access.mandate(id, action);
        requireCreditorAccount(m, action);
        JsonNode r = down.post(PAYMENTS, "/v1/payment-mandates/" + id + "/cancel",
                down.json().createObjectNode().put("reason", Req.required(req, "reason")), key, "cancel");
        audit.succeeded(action, "MANDATE", id, null);
        return r;
    }

    public JsonNode collections(UUID id, Integer limit, String cursor) {
        access.mandate(id, "businessMandatesCollections");
        return down.page(down.get(PAYMENTS, "/v1/payment-mandates/" + id + "/collections", Query.none().paging(limit, cursor)),
                r -> id.toString().equals(r.path("mandate_id").asString(null)));
    }

    public OperationApprovals.Outcome addCollection(UUID id, JsonNode req, String key) {
        String action = "businessMandatesAddCollections";
        access.requireRole(action, "MANDATE", id, "ADMIN", "MAKER");
        JsonNode m = access.mandate(id, action);
        JsonNode creditAccount = requireCreditorAccount(m, action);
        if (!"OUTBOUND".equals(m.path("direction").asString(""))) {
            throw ApiException.unprocessable("not_a_collection_mandate", "The business is not the creditor of this mandate");
        }
        if (!"ACTIVE".equals(m.path("status").asString(""))) {
            throw ApiException.unprocessable("mandate_not_active", "The mandate is " + m.path("status").asString());
        }
        BigDecimal amount = Req.amount(req, "amount");
        BigDecimal max = Req.rowDecimal(m, "max_amount");
        if (max != null && amount.compareTo(max) > 0) {
            throw ApiException.unprocessable("amount_above_mandate", "The mandate allows at most " + max.toPlainString());
        }
        LocalDate due = Req.date(req, "due_date");
        if (due == null) {
            throw ApiException.unprocessable("missing_field", "due_date is required");
        }
        String seq = Req.text(req, "sequence_type") == null ? "RCUR" : Req.text(req, "sequence_type").toUpperCase();
        if (!SEQUENCE_TYPES.contains(seq)) {
            throw ApiException.unprocessable("invalid_value", "sequence_type must be one of " + SEQUENCE_TYPES);
        }
        stepUp.require(action, "MANDATE", id);
        ObjectNode body = down.json().createObjectNode()
                .put("mandate_id", id.toString())
                .put("amount", amount.toPlainString())
                .put("currency", m.path("currency").asString())
                .put("due_date", due.toString())
                .put("sequence_type", seq);
        OperationApprovals.Outcome created = creditAccount == null
                ? new OperationApprovals.Outcome(down.post(PAYMENTS, "/v1/direct-debit-collections", body, key, "collection"), false)
                : approvals.run("COLLECTION", creditAccount, amount, "Collection " + m.path("mandate_reference").asString(""),
                        PAYMENTS, "/v1/direct-debit-collections", body, key, "collection");
        audit.succeeded(action, "MANDATE", id, created.body().path("id").asString(null));
        return created;
    }

    /** The mandate's credited account (the user must be entitled to initiate on it); null for an admin when none. */
    private JsonNode requireCreditorAccount(JsonNode mandate, String action) {
        UUID account = Req.rowUuid(mandate, "creditor_account_id");
        if (account != null) {
            return access.account(account, Need.INITIATE, action);
        } else if (!CurrentUser.admin()) {
            throw access.notEntitled(action, "MANDATE", Req.rowUuid(mandate, "id"),
                    "Only an administrator can manage a mandate without a credited account");
        }
        return null;
    }

    // ------------------------------------------------------------------ virtual accounts

    public JsonNode virtualAccounts(UUID masterAccountId) {
        List<UUID> masters = new ArrayList<>();
        if (masterAccountId != null) {
            access.account(masterAccountId, Need.VIEW, "businessVirtualAccountsList");
            masters.add(masterAccountId);
        } else {
            masters.addAll(access.viewableAccountIds());
        }
        List<JsonNode> out = new ArrayList<>();
        for (UUID m : masters) {
            for (JsonNode va : down.all(DEPOSITS, "/v1/virtual-accounts", Query.of("master_account_id", m), 1000)) {
                if (m.toString().equals(va.path("master_account_id").asString(null))) {
                    out.add(va);
                }
            }
        }
        return down.page(out);
    }

    public JsonNode virtualAccount(UUID id) {
        return access.virtualAccount(id, Need.VIEW, "businessVirtualAccountsGet");
    }

    public JsonNode virtualAccountTransactions(UUID id, Integer limit, String cursor) {
        access.virtualAccount(id, Need.VIEW, "businessVirtualAccountsTransactions");
        return down.get(DEPOSITS, "/v1/virtual-accounts/" + id + "/transactions", Query.none().paging(limit, cursor));
    }

    public OperationApprovals.Outcome createVirtualAccount(JsonNode req, String key) {
        String action = "businessVirtualAccountsCreate";
        access.requireRole(action, "VIRTUAL_ACCOUNT", null, "ADMIN", "MAKER");
        UUID master = Req.uuid(req, "master_account_id", true);
        JsonNode account = access.account(master, Need.INITIATE, action);
        ObjectNode body = down.json().createObjectNode()
                .put("master_account_id", master.toString())
                .put("purpose", Req.required(req, "purpose"))
                .put("owner_reference", Req.text(req, "owner_reference"))
                .put("currency", account.path("currency").asString())
                .put("single_use", Req.bool(req, "single_use", false));
        BigDecimal expected = Req.decimal(req, "expected_amount") != null ? Req.amount(req, "expected_amount") : null;
        if (expected != null) {
            body.put("expected_amount", expected.toPlainString());
        }
        if (Req.text(req, "expires_at") != null) {
            body.put("expires_at", Req.text(req, "expires_at"));
        }
        OperationApprovals.Outcome created = approvals.run("VIRTUAL_ACCOUNT", account, expected, "Virtual account "
                + Req.required(req, "purpose"), DEPOSITS, "/v1/virtual-accounts", body, key, "create");
        audit.succeeded(action, "ACCOUNT", master, created.body().path("id").asString(null));
        return created;
    }

    public JsonNode closeVirtualAccount(UUID id, JsonNode req, String key) {
        String action = "businessVirtualAccountsClose";
        access.requireRole(action, "VIRTUAL_ACCOUNT", id, "ADMIN", "MAKER");
        access.virtualAccount(id, Need.INITIATE, action);
        String reason = Req.text(req, "reason");
        JsonNode r = down.post(DEPOSITS, "/v1/virtual-accounts/" + id + "/close",
                down.json().createObjectNode().put("reason", reason == null ? "Closed in the business portal" : reason),
                key, "close");
        audit.succeeded(action, "VIRTUAL_ACCOUNT", id, null);
        return r;
    }

    // ------------------------------------------------------------------ payment links

    public JsonNode paymentLinks(Integer limit, String cursor) {
        java.util.Set<UUID> viewable = access.viewableAccountIds();
        return down.page(down.get(PAYMENTS, "/v1/payment-links", Query.of("customer_id", business()).paging(limit, cursor)),
                r -> Access.ofBusiness(r, "customer_id") && (Req.rowUuid(r, "credit_account_id") == null
                        ? CurrentUser.admin() : viewable.contains(Req.rowUuid(r, "credit_account_id"))));
    }

    public JsonNode paymentLink(UUID id) {
        return access.paymentLink(id, "businessPaymentLinksGet");
    }

    public JsonNode paymentLinkPayments(UUID id, Integer limit, String cursor) {
        access.paymentLink(id, "businessPaymentLinksPayments");
        return down.get(PAYMENTS, "/v1/payment-links/" + id + "/payments", Query.none().paging(limit, cursor));
    }

    public OperationApprovals.Outcome createPaymentLink(JsonNode req, String key) {
        String action = "businessPaymentLinksCreate";
        access.requireRole(action, "PAYMENT_LINK", null, "ADMIN", "MAKER");
        UUID credit = Req.uuid(req, "credit_account_id", true);
        JsonNode account = access.account(credit, Need.INITIATE, action);
        ObjectNode body = down.json().createObjectNode()
                .put("customer_id", business().toString())
                .put("credit_account_id", credit.toString())
                .put("title", Req.required(req, "title"))
                .put("currency", account.path("currency").asString())
                .put("reusable", Req.bool(req, "reusable", false));
        for (String f : List.of("amount", "min_amount", "max_amount")) {
            if (Req.decimal(req, f) != null) {
                body.put(f, Req.amount(req, f).toPlainString());
            }
        }
        Integer maxPayments = Req.integer(req, "max_payments");
        if (maxPayments != null) {
            if (maxPayments < 1) {
                throw ApiException.unprocessable("invalid_value", "max_payments must be at least 1");
            }
            body.put("max_payments", maxPayments);
        }
        if (Req.text(req, "expires_at") != null) {
            body.put("expires_at", Req.text(req, "expires_at"));
        }
        BigDecimal value = body.hasNonNull("amount") ? new BigDecimal(body.get("amount").asString())
                : body.hasNonNull("max_amount") ? new BigDecimal(body.get("max_amount").asString()) : null;
        OperationApprovals.Outcome created = approvals.run("PAYMENT_LINK", account, value, "Payment link "
                + body.path("title").asString(), PAYMENTS, "/v1/payment-links", body, key, "create");
        audit.succeeded(action, "ACCOUNT", credit, created.body().path("id").asString(null));
        return created;
    }

    public JsonNode disablePaymentLink(UUID id, JsonNode req, String key) {
        String action = "businessPaymentLinksDisable";
        access.requireRole(action, "PAYMENT_LINK", id, "ADMIN", "MAKER");
        JsonNode link = access.paymentLink(id, action);
        UUID credit = Req.rowUuid(link, "credit_account_id");
        if (credit != null) {
            access.account(credit, Need.INITIATE, action);
        }
        String reason = Req.text(req, "reason");
        JsonNode r = down.post(PAYMENTS, "/v1/payment-links/" + id + "/disable",
                down.json().createObjectNode().put("reason", reason == null ? "Disabled in the business portal" : reason),
                key, "disable");
        audit.succeeded(action, "PAYMENT_LINK", id, null);
        return r;
    }
}
