package io.meridian.businesschannel.application;

import io.meridian.businesschannel.config.BusinessChannelProperties;
import io.meridian.common.error.ApiException;
import io.meridian.events.OutboxWriter;
import io.meridian.web.MeridianProperties;
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
 * <p>Bootstrap: when the business has no portal user at all and {@code meridian.business.bootstrap-first-admin} is on
 * (default: dev mode only; in JWT mode bank staff provision the first admin, {@link ProvisioningService}), the first
 * user who signs in becomes its ADMIN, provided the customer exists, is not an individual and is ACTIVE (serialised
 * per business with an advisory lock, so two first logins never make two admins). Machine-to-machine tokens are never
 * bootstrapped.
 *
 * <p>Machine-to-machine: a token with the m2m marker must name a TECHNICAL user whose {@code client_id} is the token's
 * client; an interactive token must name a HUMAN user. Anything else is 403 {@code business_user_type_mismatch}.
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
    private final boolean devMode;

    public CurrentUser(Rows rows, Downstream down, ActionAudit audit, OutboxWriter outbox, TransactionTemplate tx,
                       BusinessChannelProperties props, MeridianProperties meridian) {
        this.rows = rows;
        this.down = down;
        this.audit = audit;
        this.outbox = outbox;
        this.tx = tx;
        this.props = props;
        this.devMode = meridian.devMode();
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

    /** Whether the request comes from a business API client (machine-to-machine token of a TECHNICAL user). */
    public static boolean technical() {
        return "TECHNICAL".equals(user().path("user_type").asString("HUMAN"));
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
        requireMatchingType(p, user);
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

    /** M2M tokens act only as TECHNICAL users of their own client; interactive tokens only as HUMAN users. */
    private void requireMatchingType(BusinessPrincipal p, ObjectNode user) {
        boolean technical = "TECHNICAL".equals(user.path("user_type").asString("HUMAN"));
        String client = user.path("client_id").asString(null);
        boolean ok = p.m2m() ? technical && client != null && client.equals(p.clientId()) : !technical;
        if (!ok) {
            audit.denied("businessSignIn", "USER", p.businessUserId(), "business_user_type_mismatch");
            throw new ApiException(403, "business_user_type_mismatch", p.m2m()
                    ? "This API client token does not belong to the business's technical user of this client"
                    : "Technical users of API clients cannot sign in interactively");
        }
    }

    private ObjectNode bootstrapOrRefuse(BusinessPrincipal p) {
        Optional<ObjectNode> elsewhere = rows.one("select row_to_json(t)::text from business_user t where t.id = ?",
                p.businessUserId());
        if (elsewhere.isPresent()) {
            // the user exists, but for another business: the token's two claims do not belong together
            audit.denied("businessSignIn", "USER", p.businessUserId(), "business_user_mismatch");
            throw new ApiException(403, "business_user_unknown", "This portal user is not registered for this business");
        }
        if (!props.bootstrapFirstAdmin(devMode) || p.m2m()) {
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
            u.put("user_type", "HUMAN");
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
    public JsonNode businessCustomer(UUID id) {
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
