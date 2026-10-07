package io.meridian.businesschannel.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.web.security.AgentContext;
import io.meridian.web.security.BusinessContext;
import io.meridian.web.security.CustomerContext;
import io.meridian.web.security.MerchantContext;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Bank staff operations on business portal users ({@code /v1/business-provisioning}, scope {@code channels:admin} or
 * {@code meridian:admin}). Provisioning the first ADMIN of a business removes the reliance on the identity provider's
 * admin-only attributes and on first-sign-in bootstrap: staff check the business (an ACTIVE customer that is not an
 * individual), create its ADMIN here and set the returned id as the person's {@code business_user_id} attribute.
 *
 * <p>Only while the business has no ACTIVE ADMIN (first admin, or recovery after the last admin left); serialised per
 * business with the same advisory lock as bootstrap and user administration. Channel identities (business, customer,
 * agent, merchant) are refused even in dev security mode, where scopes are not checked. Every provisioning is
 * published ({@code channels.business_user.changed}, change PROVISIONED, with the staff principal) and recorded in the
 * business's activity log.
 */
@Service
public class ProvisioningService {
    private final Rows rows;
    private final CurrentUser current;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;

    public ProvisioningService(Rows rows, CurrentUser current, OutboxWriter outbox, TransactionTemplate tx) {
        this.rows = rows;
        this.current = current;
        this.outbox = outbox;
        this.tx = tx;
    }

    public JsonNode provisionAdmin(JsonNode req) {
        requireStaff();
        UUID business = Req.uuid(req, "business_customer_id", true);
        String name = Req.required(req, "display_name").trim();
        String email = Req.required(req, "email").trim().toLowerCase();
        if (name.length() > 160) {
            throw ApiException.unprocessable("too_long", "display_name has at most 160 characters");
        }
        current.businessCustomer(business);   // exists, ACTIVE, not an individual (403 business_not_eligible)
        String staff = TenantContext.principal();
        return tx.execute(s -> {
            rows.lockKey("business-users:" + business);
            long admins = rows.scalar("select count(*) from business_user where business_customer_id = ? "
                    + "and role = 'ADMIN' and status = 'ACTIVE'", Long.class, business).orElse(0L);
            if (admins > 0) {
                throw ApiException.conflict("business_has_admin", "The business already has an active administrator, "
                        + "who adds further users in the portal");
            }
            ObjectNode u = rows.object()
                    .put("business_customer_id", business.toString())
                    .put("display_name", name)
                    .put("email", email)
                    .put("role", "ADMIN")
                    .put("status", "ACTIVE")
                    .put("user_type", "HUMAN")
                    .put("provisioned_by", staff == null ? null : staff.length() > 64 ? staff.substring(0, 64) : staff);
            ObjectNode saved = rows.insert(CurrentUser.USERS, u);
            UUID id = UUID.fromString(saved.path("id").asString());
            outbox.publish("channels.business_user.changed", CurrentUser.USERS, id,
                    rows.object().put("change", "PROVISIONED").put("provisioned_by", staff).set("user", saved));
            rows.insert(ActionAudit.TABLE, rows.object()
                    .put("business_customer_id", business.toString())
                    .put("action", "businessProvisioningAdmins")
                    .put("object_type", "USER")
                    .put("object_id", id.toString())
                    .put("outcome", "SUCCEEDED")
                    .put("result_id", id.toString()));
            return saved;
        });
    }

    public JsonNode users(UUID business, Integer limit, String cursor) {
        requireStaff();
        int n = Rows.limit(limit);
        UUID after = Rows.cursor(cursor);
        return rows.page(rows.list("select row_to_json(t)::text from business_user t where t.business_customer_id = ? "
                + "and (?::uuid is null or t.id < ?::uuid) order by t.id desc limit ?", business, after, after, n + 1), n);
    }

    /** Staff only: never a business, customer, agent or merchant identity (also in dev mode, where scopes are off). */
    static void requireStaff() {
        TenantContext.require();
        if (BusinessContext.current().isPresent() || CustomerContext.current().isPresent()
                || AgentContext.current().isPresent() || MerchantContext.current().isPresent()) {
            throw new ApiException(403, "staff_identity_required", "This operation is for bank staff only");
        }
    }
}
