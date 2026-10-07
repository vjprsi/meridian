package io.meridian.businesschannel.application;

import static io.meridian.businesschannel.application.CurrentUser.business;
import static io.meridian.businesschannel.application.CurrentUser.userId;

import io.meridian.businesschannel.application.Downstream.Query;
import io.meridian.common.error.ApiException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Object-level and function-level authorization of the business channel.
 *
 * <ul>
 *   <li><b>Ownership</b>: every object a request references is fetched from its owning service and checked against
 *       the business of the token before anything is shown or done. An object of another business answers 404
 *       {@code not_found} exactly like a missing one (existence is never revealed); the refusal is audited and
 *       published. Account: {@code deposit_account.customer_id}. Payee, payment, mandate, payment link, loan, credit
 *       line, FX quote: their {@code customer_id}. Card: {@code business_customer_id} (or {@code customer_id}).
 *       Virtual account: its master account. Collection: its mandate. Statement: its account. API client and webhook
 *       endpoint: the channel's own ownership table (their services do not record the business).</li>
 *   <li><b>Entitlements</b> (per user and account, table business_entitlement): VIEW needs {@code can_view} (ADMIN
 *       users see every account of the business); INITIATE needs role MAKER or ADMIN and {@code can_initiate};
 *       APPROVE needs role APPROVER or ADMIN and {@code can_approve}. An account the user may not see answers 404; an
 *       account the user sees but may not act on answers 403 {@code not_entitled}.</li>
 *   <li><b>Roles</b>: administration (users, entitlements, matrix, cards, integrations) is ADMIN only (403
 *       {@code role_not_allowed}).</li>
 * </ul>
 */
@Component
public class Access {
    public enum Need { VIEW, INITIATE, APPROVE }

    static final Set<String> HIDDEN_ACCOUNT_STATUSES = Set.of("CLOSED", "REJECTED");
    static final String DEPOSITS = "deposit-service";

    private final Downstream down;
    private final Rows rows;
    private final ActionAudit audit;

    public Access(Downstream down, Rows rows, ActionAudit audit) {
        this.down = down;
        this.rows = rows;
        this.audit = audit;
    }

    public static boolean ofBusiness(JsonNode row, String field) {
        return business().toString().equals(row.path(field).asString(null));
    }

    // ------------------------------------------------------------------ roles

    /** 403 {@code role_not_allowed} (audited) unless the user has one of the roles. */
    public void requireRole(String action, String objectType, Object objectId, String... roles) {
        if (!Arrays.asList(roles).contains(CurrentUser.role())) {
            audit.denied(action, objectType, objectId, "role_not_allowed");
            throw new ApiException(403, "role_not_allowed", "Your role (" + CurrentUser.role()
                    + ") may not do this; it needs " + String.join(" or ", roles));
        }
    }

    public void requireAdmin(String action, String objectType, Object objectId) {
        requireRole(action, objectType, objectId, "ADMIN");
    }

    // ------------------------------------------------------------------ accounts and entitlements

    /** The user's entitlement on an account, if any. */
    public Optional<ObjectNode> entitlement(UUID userId, UUID accountId) {
        return rows.one("select row_to_json(t)::text from business_entitlement t where t.business_customer_id = ? "
                + "and t.business_user_id = ? and t.deposit_account_id = ?", business(), userId, accountId);
    }

    /** Whether the current user holds {@code need} on the account (role and entitlement; no downstream call). */
    public boolean entitled(UUID accountId, Need need) {
        return entitled(CurrentUser.user(), accountId, need);
    }

    /** Whether a user (record) holds {@code need} on the account. */
    public boolean entitled(JsonNode user, UUID accountId, Need need) {
        String role = user.path("role").asString("");
        UUID uid = UUID.fromString(user.path("id").asString());
        Optional<ObjectNode> e = entitlement(uid, accountId);
        return switch (need) {
            case VIEW -> "ADMIN".equals(role) || e.map(x -> x.path("can_view").asBoolean(false)).orElse(false);
            case INITIATE -> ("ADMIN".equals(role) || "MAKER".equals(role))
                    && e.map(x -> x.path("can_initiate").asBoolean(false)).orElse(false);
            case APPROVE -> ("ADMIN".equals(role) || "APPROVER".equals(role))
                    && e.map(x -> x.path("can_approve").asBoolean(false)).orElse(false);
        };
    }

    /**
     * An account of the business the user holds {@code need} on. Another business's (or a missing) account, or one the
     * user may not see: 404. Seen but not entitled to initiate / approve: 403 {@code not_entitled}.
     */
    public JsonNode account(UUID id, Need need, String action) {
        JsonNode account = fetch(DEPOSITS, "/v1/deposit-accounts/" + id, "ACCOUNT", id);
        if (!ofBusiness(account, "customer_id")) {
            throw deny(action, "ACCOUNT", id);
        }
        if (!entitled(id, Need.VIEW)) {
            audit.denied(action, "ACCOUNT", id, "not_entitled");
            throw notFound("ACCOUNT", id);
        }
        if (need != Need.VIEW && !entitled(id, need)) {
            throw notEntitled(action, "ACCOUNT", id, need == Need.INITIATE
                    ? "You are not entitled to initiate payments from this account"
                    : "You are not entitled to approve payments from this account");
        }
        return account;
    }

    /** An account that belongs to the business (no entitlement needed: e.g. an own-transfer credit account). */
    public JsonNode businessAccount(UUID id, String action) {
        JsonNode account = fetch(DEPOSITS, "/v1/deposit-accounts/" + id, "ACCOUNT", id);
        if (!ofBusiness(account, "customer_id")) {
            throw deny(action, "ACCOUNT", id);
        }
        return account;
    }

    /** All accounts of the business (downstream, filtered again here), closed / rejected left out. */
    public List<JsonNode> businessAccounts() {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode a : down.all(DEPOSITS, "/v1/deposit-accounts", Query.of("customer_id", business()), 1000)) {
            if (ofBusiness(a, "customer_id") && !HIDDEN_ACCOUNT_STATUSES.contains(a.path("status").asString(""))) {
                out.add(a);
            }
        }
        return out;
    }

    /** Accounts the current user may see (ADMIN: all of the business's; others: their can_view entitlements). */
    public List<JsonNode> viewableAccounts() {
        if (CurrentUser.admin()) {
            return businessAccounts();
        }
        Map<String, JsonNode> out = new LinkedHashMap<>();
        for (UUID id : viewableAccountIds()) {
            try {
                JsonNode a = down.get(DEPOSITS, "/v1/deposit-accounts/" + id);
                if (ofBusiness(a, "customer_id") && !HIDDEN_ACCOUNT_STATUSES.contains(a.path("status").asString(""))) {
                    out.put(id.toString(), a);
                }
            } catch (ApiException e) {
                if (e.status() != 404) {
                    throw e;
                }
            }
        }
        return new ArrayList<>(out.values());
    }

    /**
     * Ids of the accounts the current user may see. ADMIN users: every account of the business (one downstream list);
     * others: their can_view entitlements (no downstream call).
     */
    public Set<UUID> viewableAccountIds() {
        Set<UUID> ids = new LinkedHashSet<>();
        if (CurrentUser.admin()) {
            businessAccounts().forEach(a -> ids.add(UUID.fromString(a.path("id").asString())));
            return ids;
        }
        for (ObjectNode e : rows.list("select row_to_json(t)::text from business_entitlement t where "
                + "t.business_customer_id = ? and t.business_user_id = ? and t.can_view", business(), userId())) {
            ids.add(UUID.fromString(e.path("deposit_account_id").asString()));
        }
        return ids;
    }

    // ------------------------------------------------------------------ objects owned through customer_id

    public JsonNode payee(UUID id, String action) {
        return owned("payment-service", "/v1/payees/" + id, "PAYEE", id, action,
                r -> ofBusiness(r, "customer_id") && !"DELETED".equals(r.path("status").asString("")));
    }

    public JsonNode mandate(UUID id, String action) {
        return owned("payment-service", "/v1/payment-mandates/" + id, "MANDATE", id, action,
                r -> ofBusiness(r, "customer_id"));
    }

    public JsonNode paymentLink(UUID id, String action) {
        JsonNode link = owned("payment-service", "/v1/payment-links/" + id, "PAYMENT_LINK", id, action,
                r -> ofBusiness(r, "customer_id"));
        UUID account = Req.rowUuid(link, "credit_account_id");
        if (account != null && !entitled(account, Need.VIEW)) {
            audit.denied(action, "PAYMENT_LINK", id, "not_entitled");
            throw notFound("PAYMENT_LINK", id);
        }
        return link;
    }

    public JsonNode loan(UUID id, String action) {
        return owned("loan-service", "/v1/loans/" + id, "LOAN", id, action, r -> ofBusiness(r, "customer_id"));
    }

    public JsonNode creditLine(UUID id, String action) {
        return owned("loan-service", "/v1/credit-lines/" + id, "CREDIT_LINE", id, action,
                r -> ofBusiness(r, "customer_id"));
    }

    public JsonNode fxQuote(UUID id, String action) {
        return owned("fx-service", "/v1/fx/quotes/" + id, "FX_QUOTE", id, action, r -> ofBusiness(r, "customer_id"));
    }

    /** A card issued under the business, linked to an account the user may see. */
    public JsonNode card(UUID id, String action) {
        JsonNode card = owned("card-service", "/v1/cards/" + id, "CARD", id, action, Access::businessCard);
        UUID account = Req.rowUuid(card, "deposit_account_id");
        if (!CurrentUser.admin() && (account == null || !entitled(account, Need.VIEW))) {
            audit.denied(action, "CARD", id, "not_entitled");
            throw notFound("CARD", id);
        }
        return card;
    }

    static boolean businessCard(JsonNode card) {
        String biz = card.path("business_customer_id").asString(null);
        return biz != null ? business().toString().equals(biz) : ofBusiness(card, "customer_id");
    }

    /** A virtual account whose master account belongs to the business and is visible to the user (with need). */
    public JsonNode virtualAccount(UUID id, Need need, String action) {
        JsonNode va = fetch(DEPOSITS, "/v1/virtual-accounts/" + id, "VIRTUAL_ACCOUNT", id);
        UUID master = Req.rowUuid(va, "master_account_id");
        if (master == null) {
            throw deny(action, "VIRTUAL_ACCOUNT", id);
        }
        try {
            account(master, need, action);
        } catch (ApiException e) {
            if (e.status() == 404) {
                throw deny(action, "VIRTUAL_ACCOUNT", id);
            }
            throw e;
        }
        return va;
    }

    /** A statement of the given account (the account itself already checked by the caller). */
    public JsonNode statement(UUID accountId, UUID statementId, String action) {
        return owned("statement-service", "/v1/statements/" + statementId, "STATEMENT", statementId, action,
                r -> accountId.toString().equals(r.path("deposit_account_id").asString(null)));
    }

    /** An integration object (API client, webhook endpoint) the channel created for the business. */
    public ObjectNode ownedObject(String objectType, UUID id, String action) {
        return rows.one("select row_to_json(t)::text from business_object t where t.object_id = ? "
                        + "and t.business_customer_id = ? and t.object_type = ?", id, business(), objectType)
                .orElseThrow(() -> deny(action, objectType, id));
    }

    // ------------------------------------------------------------------ helpers

    public JsonNode owned(String service, String path, String type, UUID id, String action, Predicate<JsonNode> isOurs) {
        JsonNode row = fetch(service, path, type, id);
        if (!isOurs.test(row)) {
            throw deny(action, type, id);
        }
        return row;
    }

    /** Downstream fetch; a downstream 404 is answered with the same neutral 404. */
    public JsonNode fetch(String service, String path, String type, UUID id) {
        try {
            return down.get(service, path);
        } catch (ApiException e) {
            if (e.status() == 404) {
                throw notFound(type, id);
            }
            throw e;
        }
    }

    /** Audits the refusal (and publishes it) and returns the 404 to throw (same body as a missing object). */
    public ApiException deny(String action, String type, UUID id) {
        audit.denied(action, type, id, "not_owned");
        return notFound(type, id);
    }

    /** Audits an entitlement refusal and returns the 403 to throw. */
    public ApiException notEntitled(String action, String type, UUID id, String message) {
        audit.denied(action, type, id, "not_entitled");
        return new ApiException(403, "not_entitled", message);
    }

    public static ApiException notFound(String type, UUID id) {
        return ApiException.notFound(type.toLowerCase().replace('_', ' '), id);
    }
}
