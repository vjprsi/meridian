package io.meridian.customerchannel.application;

import io.meridian.common.error.ApiException;
import io.meridian.customerchannel.application.Downstream.Query;
import io.meridian.web.security.CustomerContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Object-level authorization: every object a customer request references is fetched from its owning service and
 * checked against the signed-in customer before anything is shown or done. An object of another customer answers
 * 404 {@code not_found} exactly like a missing one (existence is never revealed), and the refusal is audited.
 *
 * <ul>
 *   <li>Account: owner ({@code deposit_account.customer_id}) or an ACTIVE account holder with {@code can_view}
 *       (reading) / {@code can_transact} (paying from it).</li>
 *   <li>Card, loan, wallet, payment, payee, budget, insight card, points account, thread, KYC profile: their
 *       {@code customer_id}. Notification: {@code recipient_type = CUSTOMER} and {@code recipient_id}. Document:
 *       {@code owner_type = CUSTOMER} and {@code owner_id}. Standing order and statement: their deposit account.
 *       Contact and device: fetched under the customer's own path and their {@code customer_id}.</li>
 * </ul>
 */
@Component
public class Ownership {
    public enum Access { VIEW, TRANSACT }

    static final Set<String> HIDDEN_ACCOUNT_STATUSES = Set.of("CLOSED", "REJECTED");

    private final Downstream down;
    private final ActionAudit audit;

    public Ownership(Downstream down, ActionAudit audit) {
        this.down = down;
        this.audit = audit;
    }

    public static UUID me() {
        return CustomerContext.customerId();
    }

    public static boolean mine(JsonNode row, String field) {
        return me().toString().equals(row.path(field).asString(null));
    }

    // ------------------------------------------------------------------ accounts

    /** An account the customer owns or holds with the given access; 404 otherwise. */
    public JsonNode account(UUID id, Access access, String action) {
        JsonNode account = fetch("deposit-service", "/v1/deposit-accounts/" + id, "ACCOUNT", id);
        if (mine(account, "customer_id")) {
            return account;
        }
        JsonNode holder = holding(id);
        if (holder == null || !holder.path("can_view").asBoolean(false)) {
            throw deny(action, "ACCOUNT", id);
        }
        if (access == Access.TRANSACT && !holder.path("can_transact").asBoolean(false)) {
            // the customer can see this account (joint holder / viewer), so its existence is no secret
            audit.denied(action, "ACCOUNT", id, "account_not_transactable");
            throw new ApiException(403, "account_not_transactable",
                    "You may view this account but not make payments from it");
        }
        return account;
    }

    /** The customer's ACTIVE holder record on an account, or null. */
    private JsonNode holding(UUID accountId) {
        JsonNode page = down.get("deposit-service", "/v1/account-holders", Query.of("deposit_account_id", accountId)
                .with("customer_id", me()).with("status", "ACTIVE").with("limit", 10));
        for (JsonNode h : page.path("data")) {
            if (mine(h, "customer_id") && accountId.toString().equals(h.path("deposit_account_id").asString(null))
                    && "ACTIVE".equals(h.path("status").asString(null))) {
                return h;
            }
        }
        return null;
    }

    /** The customer's ACTIVE holder records (accounts owned by someone else that the customer may see). */
    public List<JsonNode> holdings() {
        return down.all("deposit-service", "/v1/account-holders",
                        Query.of("customer_id", me()).with("status", "ACTIVE"), 500).stream()
                .filter(h -> mine(h, "customer_id") && "ACTIVE".equals(h.path("status").asString(null)))
                .toList();
    }

    /** Accounts the customer owns, plus accounts held with view rights; closed / rejected ones left out. */
    public List<JsonNode> accounts() {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        for (JsonNode a : down.all("deposit-service", "/v1/deposit-accounts", Query.of("customer_id", me()), 500)) {
            if (mine(a, "customer_id")) {
                out.put(a.path("id").asString(), a);
            }
        }
        for (JsonNode h : holdings()) {
            String accountId = h.path("deposit_account_id").asString(null);
            if (accountId == null || out.containsKey(accountId) || !h.path("can_view").asBoolean(false)) {
                continue;
            }
            try {
                out.put(accountId, down.get("deposit-service", "/v1/deposit-accounts/" + accountId));
            } catch (ApiException e) {
                if (e.status() != 404) {
                    throw e;
                }
            }
        }
        List<JsonNode> list = new ArrayList<>();
        for (JsonNode a : out.values()) {
            if (!HIDDEN_ACCOUNT_STATUSES.contains(a.path("status").asString(""))) {
                list.add(a);
            }
        }
        return list;
    }

    // ------------------------------------------------------------------ objects owned through customer_id

    public JsonNode card(UUID id, String action) {
        return owned("card-service", "/v1/cards/" + id, "CARD", id, action, r -> mine(r, "customer_id"));
    }

    public JsonNode loan(UUID id, String action) {
        return owned("loan-service", "/v1/loans/" + id, "LOAN", id, action, r -> mine(r, "customer_id"));
    }

    public JsonNode wallet(UUID id, String action) {
        return owned("wallet-service", "/v1/wallets/" + id, "WALLET", id, action, r -> mine(r, "customer_id"));
    }

    public JsonNode payment(UUID id, String action) {
        return owned("payment-service", "/v1/payments/" + id, "PAYMENT", id, action, r -> mine(r, "customer_id"));
    }

    /** A payee of the customer that has not been deleted. */
    public JsonNode payee(UUID id, String action) {
        return owned("payment-service", "/v1/payees/" + id, "PAYEE", id, action,
                r -> mine(r, "customer_id") && !"DELETED".equals(r.path("status").asString("")));
    }

    public JsonNode budget(UUID id, String action) {
        return owned("insights-service", "/v1/insights/budgets/" + id, "BUDGET", id, action,
                r -> mine(r, "customer_id"));
    }

    public JsonNode insight(UUID id, String action) {
        return owned("insights-service", "/v1/insights/cards/" + id, "INSIGHT", id, action,
                r -> mine(r, "customer_id"));
    }

    public JsonNode pointsAccount(UUID id, String action) {
        return owned("rewards-service", "/v1/rewards/points-accounts/" + id, "POINTS_ACCOUNT", id, action,
                r -> mine(r, "customer_id"));
    }

    public JsonNode thread(UUID id, String action) {
        return owned("inbox-service", "/v1/inbox/threads/" + id, "THREAD", id, action, r -> mine(r, "customer_id"));
    }

    public JsonNode notification(UUID id, String action) {
        return owned("notification-service", "/v1/notifications/" + id, "NOTIFICATION", id, action,
                r -> "CUSTOMER".equals(r.path("recipient_type").asString(null)) && mine(r, "recipient_id"));
    }

    public JsonNode document(UUID id, String action) {
        return owned("document-service", "/v1/documents/" + id, "DOCUMENT", id, action,
                r -> "CUSTOMER".equals(r.path("owner_type").asString(null)) && mine(r, "owner_id"));
    }

    public JsonNode contact(UUID id, String action) {
        return owned("customer-service", "/v1/customers/" + me() + "/contacts/" + id, "CONTACT", id, action,
                r -> mine(r, "customer_id"));
    }

    public JsonNode device(UUID id, String action) {
        return owned("customer-service", "/v1/customers/" + me() + "/devices/" + id, "DEVICE", id, action,
                r -> mine(r, "customer_id"));
    }

    /** A standing order on one of the customer's accounts (with the given access on that account). */
    public JsonNode standingOrder(UUID id, Access access, String action) {
        JsonNode order = fetch("deposit-service", "/v1/standing-orders/" + id, "STANDING_ORDER", id);
        String accountId = order.path("deposit_account_id").asString(null);
        if (accountId == null) {
            throw deny(action, "STANDING_ORDER", id);
        }
        try {
            account(UUID.fromString(accountId), access, action);
        } catch (ApiException e) {
            if (e.status() == 404) {
                throw deny(action, "STANDING_ORDER", id);
            }
            throw e;
        }
        return order;
    }

    /** A statement of the given account (the account itself already checked by the caller). */
    public JsonNode statement(UUID accountId, UUID statementId, String action) {
        return owned("statement-service", "/v1/statements/" + statementId, "STATEMENT", statementId, action,
                r -> accountId.toString().equals(r.path("deposit_account_id").asString(null)));
    }

    /** One related object of a thread (ACCOUNT, CARD, LOAN, PAYMENT, WALLET), checked like any other reference. */
    public void related(String type, UUID id, String action) {
        switch (type == null ? "" : type) {
            case "ACCOUNT" -> account(id, Access.VIEW, action);
            case "CARD" -> card(id, action);
            case "LOAN" -> loan(id, action);
            case "PAYMENT" -> payment(id, action);
            case "WALLET" -> wallet(id, action);
            default -> throw ApiException.unprocessable("invalid_related_type",
                    "related_type must be ACCOUNT, CARD, LOAN, PAYMENT or WALLET");
        }
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode owned(String service, String path, String type, UUID id, String action, Predicate<JsonNode> isMine) {
        JsonNode row = fetch(service, path, type, id);
        if (!isMine.test(row)) {
            throw deny(action, type, id);
        }
        return row;
    }

    /** Downstream fetch; a downstream 404 is answered with the same neutral 404. */
    private JsonNode fetch(String service, String path, String type, UUID id) {
        try {
            return down.get(service, path);
        } catch (ApiException e) {
            if (e.status() == 404) {
                throw notFound(type, id);
            }
            throw e;
        }
    }

    /** Audits the refusal and returns the 404 to throw (same body as a missing object). */
    public ApiException deny(String action, String type, UUID id) {
        audit.denied(action, type, id, "not_owned");
        return notFound(type, id);
    }

    public static ApiException notFound(String type, UUID id) {
        return ApiException.notFound(type.toLowerCase().replace('_', ' '), id);
    }
}
