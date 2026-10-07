package io.meridian.merchantchannel.application;

import static io.meridian.merchantchannel.application.CurrentUser.merchant;
import static io.meridian.merchantchannel.application.CurrentUser.userId;

import io.meridian.common.error.ApiException;
import io.meridian.merchantchannel.application.Downstream.Query;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
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
 * Object-level and function-level authorization of the merchant channel.
 *
 * <ul>
 *   <li><b>Ownership</b>: every object a request references is fetched from its owning service (or the channel's own
 *       tables) and checked against the merchant of the token before anything is shown or done. An object of another
 *       merchant answers 404 {@code not_found} exactly like a missing one (existence is never revealed); the refusal
 *       is audited and published. Store, terminal, payment link, settlement, wallet transaction: their
 *       {@code merchant_id}. Sale, refund request, payout change, user: the channel's rows of the merchant. API client
 *       and webhook endpoint: the channel's ownership table (their services do not record the merchant).</li>
 *   <li><b>Store scope</b>: OWNER users and users with {@code all_stores} work in every store; the others only in the
 *       stores assigned to them (merchant_user_store). An object of a store outside the scope answers the same 404
 *       (audited as {@code not_in_scope}). Objects not attributable to a store (a payment through a static QR without
 *       store, a payment link payment) are visible to all-stores users only.</li>
 *   <li><b>Roles</b>: OWNER administers (staff, payout account, integrations), MANAGER runs stores and approves
 *       refunds, CASHIER sells and requests refunds, VIEWER reads; anything else is 403 {@code role_not_allowed}.</li>
 * </ul>
 */
@Component
public class Access {
    static final String MERCHANTS = "merchant-service";
    static final String WALLETS = "wallet-service";

    private final Downstream down;
    private final Rows rows;
    private final ActionAudit audit;

    public Access(Downstream down, Rows rows, ActionAudit audit) {
        this.down = down;
        this.rows = rows;
        this.audit = audit;
    }

    public static boolean ofMerchant(JsonNode row) {
        return merchant().toString().equals(row.path("merchant_id").asString(null));
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

    public void requireOwner(String action, String objectType, Object objectId) {
        requireRole(action, objectType, objectId, "OWNER");
    }

    // ------------------------------------------------------------------ merchant and stores

    /** The merchant record in merchant-service. */
    public JsonNode merchantRecord() {
        return down.get(MERCHANTS, "/v1/merchants/" + merchant());
    }

    /** Every store of the merchant (downstream, filtered again here). */
    public List<JsonNode> merchantStores() {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode s : down.all(MERCHANTS, "/v1/merchants/" + merchant() + "/stores", Query.none(), 1000)) {
            if (ofMerchant(s)) {
                out.add(s);
            }
        }
        return out;
    }

    /** Store ids assigned to a user (the channel's table). */
    public Set<UUID> assignedStores(UUID user) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (ObjectNode a : rows.list("select row_to_json(t)::text from merchant_user_store t where t.merchant_id = ? "
                + "and t.merchant_user_id = ? order by t.created_at", merchant(), user)) {
            ids.add(UUID.fromString(a.path("merchant_store_id").asString()));
        }
        return ids;
    }

    /** Whether the current user works in the store (null store: all-stores users only). */
    public boolean inScope(UUID storeId) {
        if (CurrentUser.allStores()) {
            return true;
        }
        return storeId != null && assignedStores(userId()).contains(storeId);
    }

    /** Whether a user (record) works in the store (null store: all-stores users only). */
    public boolean inScope(JsonNode user, UUID storeId) {
        if ("OWNER".equals(user.path("role").asString("")) || user.path("all_stores").asBoolean(false)) {
            return true;
        }
        return storeId != null && assignedStores(UUID.fromString(user.path("id").asString())).contains(storeId);
    }

    /** Stores the current user may work in. */
    public List<JsonNode> scopeStores() {
        List<JsonNode> all = merchantStores();
        if (CurrentUser.allStores()) {
            return all;
        }
        Set<UUID> mine = assignedStores(userId());
        return all.stream().filter(s -> mine.contains(UUID.fromString(s.path("id").asString()))).toList();
    }

    /** Ids of the stores the current user may work in, or null for "every store" (all-stores users). */
    public Set<UUID> scopeStoreIds() {
        return CurrentUser.allStores() ? null : assignedStores(userId());
    }

    /** A store of the merchant inside the user's scope; else 404 (audited). */
    public JsonNode store(UUID id, String action) {
        JsonNode store = owned(MERCHANTS, "/v1/merchant-stores/" + id, "STORE", id, action, Access::ofMerchant);
        requireScope(id, action, "STORE", id);
        return store;
    }

    /** A store of the merchant (any scope; used by the owner's administration). */
    public JsonNode merchantStore(UUID id, String action) {
        return owned(MERCHANTS, "/v1/merchant-stores/" + id, "STORE", id, action, Access::ofMerchant);
    }

    /** A terminal of the merchant whose store is in the user's scope. */
    public JsonNode terminal(UUID id, String action) {
        JsonNode t = owned(MERCHANTS, "/v1/merchant-terminals/" + id, "TERMINAL", id, action, Access::ofMerchant);
        requireScope(Req.rowUuid(t, "merchant_store_id"), action, "TERMINAL", id);
        return t;
    }

    /** 404 (audited {@code not_in_scope}) unless the store is in the user's scope. */
    public void requireScope(UUID storeId, String action, String type, UUID objectId) {
        if (!inScope(storeId)) {
            audit.denied(action, type, objectId, "not_in_scope");
            throw notFound(type, objectId);
        }
    }

    // ------------------------------------------------------------------ transactions

    /** A wallet transaction of the merchant attributable to a store of the user's scope. */
    public JsonNode transaction(UUID id, String action) {
        JsonNode t = owned(WALLETS, "/v1/wallet-transactions/" + id, "TRANSACTION", id, action, Access::ofMerchant);
        requireScope(storeOf(t, new HashMap<>()), action, "TRANSACTION", id);
        return t;
    }

    /**
     * The store a wallet transaction of the merchant belongs to, or null when it cannot be attributed: the sale started
     * in the app (by payment or by QR), else the store of the merchant QR it paid, else (refunds) the store of the
     * refunded payment. {@code cache} memoises lookups within one request.
     */
    public UUID storeOf(JsonNode txn, Map<String, Optional<UUID>> cache) {
        String id = txn.path("id").asString(null);
        if (id != null) {
            Optional<ObjectNode> sale = rows.one("select row_to_json(t)::text from merchant_sale t where t.merchant_id = ? "
                    + "and t.wallet_transaction_id = ?", merchant(), UUID.fromString(id));
            if (sale.isPresent()) {
                return UUID.fromString(sale.get().path("merchant_store_id").asString());
            }
        }
        String qr = txn.path("metadata").path("merchant_qr_id").asString(null);
        if (qr != null) {
            return cache.computeIfAbsent("qr:" + qr, k -> Optional.ofNullable(qrStore(qr))).orElse(null);
        }
        String original = txn.path("original_transaction_id").asString(null);
        if (original != null && "REFUND".equals(txn.path("txn_type").asString(""))) {
            return cache.computeIfAbsent("txn:" + original, k -> {
                try {
                    JsonNode o = down.get(WALLETS, "/v1/wallet-transactions/" + original);
                    return ofMerchant(o) && !"REFUND".equals(o.path("txn_type").asString(""))
                            ? Optional.ofNullable(storeOf(o, cache)) : Optional.empty();
                } catch (ApiException e) {
                    if (e.status() == 404) {
                        return Optional.empty();
                    }
                    throw e;
                }
            }).orElse(null);
        }
        return null;
    }

    private UUID qrStore(String qrId) {
        UUID qr;
        try {
            qr = UUID.fromString(qrId);
        } catch (IllegalArgumentException e) {
            return null;
        }
        Optional<ObjectNode> sale = rows.one("select row_to_json(t)::text from merchant_sale t where t.merchant_id = ? "
                + "and t.merchant_qr_id = ?", merchant(), qr);
        if (sale.isPresent()) {
            return UUID.fromString(sale.get().path("merchant_store_id").asString());
        }
        try {
            JsonNode q = down.get(MERCHANTS, "/v1/merchant-qr-codes/" + qr);
            return ofMerchant(q) ? Req.rowUuid(q, "merchant_store_id") : null;
        } catch (ApiException e) {
            if (e.status() == 404) {
                return null;
            }
            throw e;
        }
    }

    // ------------------------------------------------------------------ other merchant-service objects

    public JsonNode settlement(UUID id, String action) {
        return owned(MERCHANTS, "/v1/merchant-settlements/" + id, "SETTLEMENT", id, action, Access::ofMerchant);
    }

    public JsonNode paymentLink(UUID id, String action) {
        return owned(MERCHANTS, "/v1/merchant-payment-links/" + id, "PAYMENT_LINK", id, action, Access::ofMerchant);
    }

    /** An integration object (API client, webhook endpoint) the channel created for the merchant. */
    public ObjectNode ownedObject(String objectType, UUID id, String action) {
        return rows.one("select row_to_json(t)::text from merchant_object t where t.object_id = ? "
                        + "and t.merchant_id = ? and t.object_type = ?", id, merchant(), objectType)
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

    public static ApiException notFound(String type, UUID id) {
        return ApiException.notFound(type.toLowerCase().replace('_', ' '), id);
    }
}
