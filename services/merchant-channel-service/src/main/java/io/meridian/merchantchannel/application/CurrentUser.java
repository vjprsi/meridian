package io.meridian.merchantchannel.application;

import io.meridian.common.error.ApiException;
import io.meridian.events.OutboxWriter;
import io.meridian.merchantchannel.config.MerchantChannelProperties;
import io.meridian.web.security.MerchantContext;
import io.meridian.web.security.MerchantContext.MerchantPrincipal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The app user record of the current request. The token only names a merchant and a user id (admin-only identity
 * provider attributes); this record is authoritative: the user must exist for that very merchant and be ACTIVE, else
 * 403 before any controller runs. The role and the store scope always come from here, never from the token.
 *
 * <p>Bootstrap: when the merchant has no app user at all and {@code meridian.merchant.bootstrap-first-owner} is on, the
 * first user who signs in becomes its OWNER, provided the merchant exists in merchant-service and is ACTIVE (serialised
 * per merchant with an advisory lock, so two first logins never make two owners).
 */
@Component
public class CurrentUser {
    public static final String USERS = "merchant_user";
    static final String MERCHANTS = "merchant-service";
    private static final ThreadLocal<ObjectNode> CURRENT = new ThreadLocal<>();

    private final Rows rows;
    private final Downstream down;
    private final ActionAudit audit;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final MerchantChannelProperties props;

    public CurrentUser(Rows rows, Downstream down, ActionAudit audit, OutboxWriter outbox, TransactionTemplate tx,
                       MerchantChannelProperties props) {
        this.rows = rows;
        this.down = down;
        this.audit = audit;
        this.outbox = outbox;
        this.tx = tx;
        this.props = props;
    }

    /** The merchant of the request (from the token). */
    public static UUID merchant() {
        return MerchantContext.merchantId();
    }

    /** The user id of the request (from the token, verified against the user record). */
    public static UUID userId() {
        return MerchantContext.merchantUserId();
    }

    /** The verified user record of the request. */
    public static ObjectNode user() {
        ObjectNode u = CURRENT.get();
        if (u == null) {
            throw new ApiException(403, "merchant_identity_required", "No merchant app user is bound");
        }
        return u;
    }

    public static String role() {
        return user().path("role").asString("");
    }

    public static boolean owner() {
        return "OWNER".equals(role());
    }

    /** Whether the user works in every store of the merchant (OWNER, or all_stores). */
    public static boolean allStores() {
        return owner() || user().path("all_stores").asBoolean(false);
    }

    /** Resolves and binds the user record (web layer, before every /v1/merchant handler). */
    public void bind() {
        MerchantPrincipal p = MerchantContext.require();
        ObjectNode user = rows.find(USERS, p.merchantId(), p.merchantUserId()).orElseGet(() -> bootstrapOrRefuse(p));
        String status = user.path("status").asString("");
        if (!"ACTIVE".equals(status)) {
            audit.denied("merchantSignIn", "USER", p.merchantUserId(), "merchant_user_inactive");
            throw new ApiException(403, "merchant_user_inactive", "This app user is " + status.toLowerCase()
                    + "; ask the owner of the merchant");
        }
        CURRENT.set(user);
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** Re-reads the bound user (after the user changed their own record). */
    public void refresh() {
        MerchantPrincipal p = MerchantContext.require();
        rows.find(USERS, p.merchantId(), p.merchantUserId()).ifPresent(CURRENT::set);
    }

    private ObjectNode bootstrapOrRefuse(MerchantPrincipal p) {
        Optional<ObjectNode> elsewhere = rows.one("select row_to_json(t)::text from merchant_user t where t.id = ?",
                p.merchantUserId());
        if (elsewhere.isPresent()) {
            // the user exists, but for another merchant: the token's two claims do not belong together
            audit.denied("merchantSignIn", "USER", p.merchantUserId(), "merchant_user_mismatch");
            throw new ApiException(403, "merchant_user_unknown", "This app user is not registered for this merchant");
        }
        if (!props.bootstrapFirstOwner()) {
            audit.denied("merchantSignIn", "USER", p.merchantUserId(), "merchant_user_unknown");
            throw new ApiException(403, "merchant_user_unknown", "This app user is not registered for this merchant");
        }
        ObjectNode created = tx.execute(s -> {
            rows.lockKey("merchant-users:" + p.merchantId());
            long existing = rows.scalar("select count(*) from merchant_user where merchant_id = ?", Long.class,
                    p.merchantId()).orElse(0L);
            if (existing > 0) {
                return null;
            }
            JsonNode merchant = eligibleMerchant(p.merchantId());
            ObjectNode u = rows.object();
            u.put("id", p.merchantUserId().toString());
            u.put("merchant_id", p.merchantId().toString());
            String name = p.username() != null ? p.username() : merchant.path("trading_name").asString("Owner");
            u.put("display_name", name.length() > 160 ? name.substring(0, 160) : name);
            u.put("role", "OWNER");
            u.put("all_stores", true);
            u.put("status", "ACTIVE");
            ObjectNode saved = rows.insert(USERS, u);
            outbox.publish("channels.merchant_user.changed", USERS, p.merchantUserId(),
                    rows.object().put("change", "BOOTSTRAPPED").set("user", saved));
            return saved;
        });
        if (created == null) {
            audit.denied("merchantSignIn", "USER", p.merchantUserId(), "merchant_user_unknown");
            throw new ApiException(403, "merchant_user_unknown", "This app user is not registered for this merchant");
        }
        audit.succeeded("merchantBootstrapOwner", "USER", p.merchantUserId(), p.merchantUserId());
        return created;
    }

    /** The merchant: must exist in merchant-service and be ACTIVE to get its first owner. */
    JsonNode eligibleMerchant(UUID id) {
        JsonNode merchant;
        try {
            merchant = down.get(MERCHANTS, "/v1/merchants/" + id);
        } catch (ApiException e) {
            if (e.status() == 404) {
                throw new ApiException(403, "merchant_not_eligible", "The merchant is not known to the bank");
            }
            throw e;
        }
        if (!"ACTIVE".equals(merchant.path("status").asString(""))) {
            throw new ApiException(403, "merchant_not_eligible", "The merchant app is for active merchants");
        }
        return merchant;
    }
}
