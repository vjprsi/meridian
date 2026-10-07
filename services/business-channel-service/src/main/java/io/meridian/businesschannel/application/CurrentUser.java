package io.meridian.businesschannel.application;

import io.meridian.businesschannel.config.BusinessChannelProperties;
import io.meridian.common.error.ApiException;
import io.meridian.events.OutboxWriter;
import io.meridian.web.security.BusinessContext;
import io.meridian.web.security.BusinessContext.BusinessPrincipal;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The portal user record of the current request. The token only names a business and a user id (admin-only identity
 * provider attributes); this record is authoritative: the user must exist for that very business and be ACTIVE, else
 * 403 before any controller runs. The role and the entitlements always come from here, never from the token.
 *
 * <p>Bootstrap: when the business has no portal user at all and {@code meridian.business.bootstrap-first-admin} is on,
 * the first user who signs in becomes its ADMIN, provided the customer exists, is not an individual and is ACTIVE
 * (serialised per business with an advisory lock, so two first logins never make two admins).
 */
@Component
public class CurrentUser {
    public static final String USERS = "business_user";
    private static final Set<String> USABLE_BUSINESS = Set.of("ACTIVE");
    private static final ThreadLocal<ObjectNode> CURRENT = new ThreadLocal<>();

    private final Rows rows;
    private final Downstream down;
    private final ActionAudit audit;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final BusinessChannelProperties props;

    public CurrentUser(Rows rows, Downstream down, ActionAudit audit, OutboxWriter outbox, TransactionTemplate tx,
                       BusinessChannelProperties props) {
        this.rows = rows;
        this.down = down;
        this.audit = audit;
        this.outbox = outbox;
        this.tx = tx;
        this.props = props;
    }

    /** The business of the request (from the token). */
    public static UUID business() {
        return BusinessContext.businessCustomerId();
    }

    /** The user id of the request (from the token, verified against the user record). */
    public static UUID userId() {
        return BusinessContext.businessUserId();
    }

    /** The verified user record of the request. */
    public static ObjectNode user() {
        ObjectNode u = CURRENT.get();
        if (u == null) {
            throw new ApiException(403, "business_identity_required", "No business portal user is bound");
        }
        return u;
    }

    public static String role() {
        return user().path("role").asString("");
    }

    public static boolean admin() {
        return "ADMIN".equals(role());
    }

    /** Resolves and binds the user record (web layer, before every /v1/business handler). */
    public void bind() {
        BusinessPrincipal p = BusinessContext.require();
        ObjectNode user = rows.find(USERS, p.businessCustomerId(), p.businessUserId())
                .orElseGet(() -> bootstrapOrRefuse(p));
        String status = user.path("status").asString("");
        if (!"ACTIVE".equals(status)) {
            audit.denied("businessSignIn", "USER", p.businessUserId(), "business_user_inactive");
            throw new ApiException(403, "business_user_inactive", "This portal user is " + status.toLowerCase()
                    + "; ask an administrator of the business");
        }
        CURRENT.set(user);
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** Re-reads the bound user (after the user changed their own record). */
    public void refresh() {
        BusinessPrincipal p = BusinessContext.require();
        rows.find(USERS, p.businessCustomerId(), p.businessUserId()).ifPresent(CURRENT::set);
    }

    private ObjectNode bootstrapOrRefuse(BusinessPrincipal p) {
        Optional<ObjectNode> elsewhere = rows.one("select row_to_json(t)::text from business_user t where t.id = ?",
                p.businessUserId());
        if (elsewhere.isPresent()) {
            // the user exists, but for another business: the token's two claims do not belong together
            audit.denied("businessSignIn", "USER", p.businessUserId(), "business_user_mismatch");
            throw new ApiException(403, "business_user_unknown", "This portal user is not registered for this business");
        }
        if (!props.bootstrapFirstAdmin()) {
            audit.denied("businessSignIn", "USER", p.businessUserId(), "business_user_unknown");
            throw new ApiException(403, "business_user_unknown", "This portal user is not registered for this business");
        }
        ObjectNode created = tx.execute(s -> {
            rows.lockKey("business-users:" + p.businessCustomerId());
            long existing = rows.scalar("select count(*) from business_user where business_customer_id = ?", Long.class,
                    p.businessCustomerId()).orElse(0L);
            if (existing > 0) {
                return null;
            }
            JsonNode customer = businessCustomer(p.businessCustomerId());
            ObjectNode u = rows.object();
            u.put("id", p.businessUserId().toString());
            u.put("business_customer_id", p.businessCustomerId().toString());
            String name = p.username() != null ? p.username() : customer.path("display_name").asString("Administrator");
            u.put("display_name", name.length() > 160 ? name.substring(0, 160) : name);
            u.put("role", "ADMIN");
            u.put("status", "ACTIVE");
            ObjectNode saved = rows.insert(USERS, u);
            outbox.publish("channels.business_user.changed", USERS, p.businessUserId(),
                    rows.object().put("change", "BOOTSTRAPPED").set("user", saved));
            return saved;
        });
        if (created == null) {
            audit.denied("businessSignIn", "USER", p.businessUserId(), "business_user_unknown");
            throw new ApiException(403, "business_user_unknown", "This portal user is not registered for this business");
        }
        audit.succeeded("businessBootstrapAdmin", "USER", p.businessUserId(), p.businessUserId());
        return created;
    }

    /** The business customer: must exist, be an organisation and be ACTIVE. */
    JsonNode businessCustomer(UUID id) {
        JsonNode customer;
        try {
            customer = down.get("customer-service", "/v1/customers/" + id);
        } catch (ApiException e) {
            if (e.status() == 404) {
                throw new ApiException(403, "business_not_eligible", "The business is not a customer of the bank");
            }
            throw e;
        }
        if ("INDIVIDUAL".equals(customer.path("customer_type").asString("INDIVIDUAL"))
                || !USABLE_BUSINESS.contains(customer.path("status").asString(""))) {
            throw new ApiException(403, "business_not_eligible",
                    "Business banking is for active business customers (not individuals)");
        }
        return customer;
    }
}
