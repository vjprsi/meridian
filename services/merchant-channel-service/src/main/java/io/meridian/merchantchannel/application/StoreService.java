package io.meridian.merchantchannel.application;

import static io.meridian.merchantchannel.application.CurrentUser.merchant;
import static io.meridian.merchantchannel.application.CurrentUser.userId;

import io.meridian.common.error.ApiException;
import io.meridian.common.time.Clocks;
import io.meridian.merchantchannel.application.Downstream.Query;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** The signed-in user, the merchant, its stores and terminals within the user's store scope. */
@Service
public class StoreService {
    static final String MERCHANTS = Access.MERCHANTS;

    private final Access access;
    private final Rows rows;
    private final Downstream down;
    private final StepUp stepUp;
    private final ActionAudit audit;

    public StoreService(Access access, Rows rows, Downstream down, StepUp stepUp, ActionAudit audit) {
        this.access = access;
        this.rows = rows;
        this.down = down;
        this.stepUp = stepUp;
        this.audit = audit;
    }

    /** The signed-in user, the merchant (public fields only) and the stores the user may work in; records sign-in. */
    public JsonNode me() {
        ObjectNode user = rows.update(CurrentUser.USERS, userId(),
                rows.object().put("last_login_at", Clocks.now().toString()));
        ObjectNode out = rows.object();
        out.set("user", user);
        JsonNode m = access.merchantRecord();
        ObjectNode merchant = rows.object();
        for (String f : List.of("id", "merchant_code", "trading_name", "legal_name", "merchant_type", "mcc", "country",
                "currency", "city", "status", "settlement_frequency")) {
            if (m.has(f)) {
                merchant.set(f, m.get(f));
            }
        }
        out.set("merchant", merchant);
        ArrayNode stores = out.putArray("stores");
        access.scopeStores().forEach(stores::add);
        return out;
    }

    public JsonNode stores() {
        return down.page(access.scopeStores());
    }

    public JsonNode store(UUID id) {
        return access.store(id, "merchantAppStoresGet");
    }

    /** Terminals of the user's stores (one store when given; it must be in scope). */
    public JsonNode terminals(UUID storeId) {
        List<JsonNode> out = new ArrayList<>();
        if (storeId != null) {
            access.store(storeId, "merchantAppTerminalsList");
        }
        Set<UUID> scope = access.scopeStoreIds();
        for (JsonNode t : down.all(MERCHANTS, "/v1/merchant-terminals", Query.of("merchant_id", merchant())
                .with("merchant_store_id", storeId), 1000)) {
            if (!Access.ofMerchant(t)) {
                continue;
            }
            UUID store = Req.rowUuid(t, "merchant_store_id");
            if (storeId != null && !storeId.equals(store)) {
                continue;
            }
            if (scope == null || (store != null && scope.contains(store))) {
                out.add(t);
            }
        }
        return down.page(out);
    }

    public JsonNode terminal(UUID id) {
        return access.terminal(id, "merchantAppTerminalsGet");
    }

    public JsonNode deactivateTerminal(UUID id, JsonNode req, String key) {
        String action = "merchantAppTerminalsDeactivate";
        access.terminal(id, action);
        access.requireRole(action, "TERMINAL", id, "OWNER", "MANAGER");
        String reason = Req.required(req, "reason");
        stepUp.require(action, "TERMINAL", id);
        try {
            JsonNode r = down.post(MERCHANTS, "/v1/merchant-terminals/" + id + "/deactivate",
                    rows.object().put("reason", reason.length() > 255 ? reason.substring(0, 255) : reason), key,
                    "deactivate");
            audit.succeeded(action, "TERMINAL", id, null);
            return r;
        } catch (ApiException e) {
            audit.failed(action, "TERMINAL", id, e.code());
            throw e;
        }
    }
}
