package io.meridian.card.application;

import io.meridian.card.domain.OrderLifecycle;
import io.meridian.card.infrastructure.Rows;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Physical card orders: bureau submission, cancellation, reship and bureau status callbacks. */
@Service
public class OrderService {
    /** Order fields a PATCH may change, and only while the order is PENDING. */
    public static final Set<String> ADDRESS_FIELDS = Set.of("shipping_method", "recipient_name", "address_line1",
            "address_line2", "city", "region", "postal_code", "country", "phone");

    private final Rows rows;
    private final Events events;
    private final CardService cards;

    public OrderService(Rows rows, Events events, CardService cards) {
        this.rows = rows;
        this.events = events;
        this.cards = cards;
    }

    @Transactional(readOnly = true)
    public void checkUpdate(UUID id, JsonNode req) {
        ObjectNode o = rows.get("card_order", id);
        req.propertyNames().forEach(f -> {
            if (!req.get(f).isNull() && !ADDRESS_FIELDS.contains(f) && !f.startsWith("custom_field_")) {
                throw ApiException.unprocessable("field_not_updatable", f + " cannot be changed on an order");
            }
        });
        if (!"PENDING".equals(Rows.str(o, "status"))) {
            throw ApiException.conflict("order_not_pending", "only a PENDING order's delivery details can be changed");
        }
    }

    @Transactional
    public JsonNode submit(UUID id) {
        ObjectNode o = rows.lock("card_order", id);
        String to = OrderLifecycle.submit(Rows.str(o, "status"));
        ObjectNode bureau = rows.get("card_bureau", Rows.uuid(o, "card_bureau_id"));
        if (!Rows.bool(bureau, "is_active")) {
            throw ApiException.unprocessable("bureau_inactive", "the card bureau is not active");
        }
        String ref = ("SFTP_FILE".equals(Rows.str(bureau, "protocol")) ? "FILE-" : "API-")
                + rows.dbToday().toString().replace("-", "") + "-"
                + id.toString().replace("-", "").substring(20).toUpperCase(Locale.ROOT);
        rows.execute("update card_order set status = ?, bureau_reference = ?, sent_to_bureau_at = now(), updated_by = ? "
                + "where id = ?", to, ref, TenantContext.principal(), id);
        ObjectNode row = rows.get("card_order", id);
        events.publish("cards.card_order.status_changed", "card_order", row);
        return row;
    }

    @Transactional
    public JsonNode cancel(UUID id, String reason) {
        ObjectNode o = rows.lock("card_order", id);
        String to = OrderLifecycle.cancel(Rows.str(o, "status"));
        rows.execute("update card_order set status = ?, failure_reason = ?, updated_by = ? where id = ?",
                to, reason == null ? "Cancelled" : reason, TenantContext.principal(), id);
        ObjectNode row = rows.get("card_order", id);
        events.publish("cards.card_order.status_changed", "card_order", row);
        return row;
    }

    @Transactional
    public JsonNode reship(UUID id, JsonNode req) {
        ObjectNode o = rows.lock("card_order", id);
        OrderLifecycle.reship(Rows.str(o, "status"));
        if (rows.scalar("select count(*) from card_order where reship_of_order_id = ?", Long.class, id).orElse(0L) > 0) {
            throw ApiException.conflict("already_reshipped", "the returned order was already reshipped");
        }
        ObjectNode card = rows.get("card", Rows.uuid(o, "card_id"));
        if (!Set.of("CREATED", "ACTIVE", "FROZEN").contains(Rows.str(card, "status"))) {
            throw ApiException.conflict("invalid_transition", "cannot reship a card in status " + Rows.str(card, "status"));
        }
        JsonNode address = req.get("address");
        if (address == null || address.isNull()) {
            address = o;
        } else if (Rows.str(address, "address_line1") == null || Rows.str(address, "city") == null
                || Rows.str(address, "country") == null) {
            throw ApiException.unprocessable("address_required", "address needs address_line1, city and country");
        }
        String method = Rows.str(req, "shipping_method") != null ? Rows.str(req, "shipping_method") : Rows.str(o, "shipping_method");
        ObjectNode bureau = rows.get("card_bureau", Rows.uuid(o, "card_bureau_id"));
        return cards.createOrder(Rows.uuid(o, "card_id"), bureau, address, Rows.str(card, "cardholder_name"), method, id);
    }

    /** Bureau status update (tenant already bound from the signed callback). Duplicates are no-ops. */
    @Transactional
    public JsonNode bureauUpdate(JsonNode req) {
        String ref = Rows.str(req, "bureau_reference");
        String status = Rows.str(req, "status");
        if (ref == null || status == null) {
            throw ApiException.badRequest("missing_parameter", "bureau_reference and status are required");
        }
        ObjectNode o = rows.queryOne("select row_to_json(t)::text from card_order t where bureau_reference = ? for update", ref)
                .orElseThrow(() -> ApiException.notFound("card order with bureau_reference", ref));
        UUID id = Rows.uuid(o, "id");
        String to = OrderLifecycle.bureauUpdate(Rows.str(o, "status"), status);
        if (to == null) {
            return o;
        }
        rows.execute("""
                update card_order set status = ?, updated_by = ?,
                  tracking_number = coalesce(?, tracking_number), carrier = coalesce(?, carrier),
                  failure_reason = case when ? in ('FAILED','RETURNED') then coalesce(?, failure_reason) else failure_reason end,
                  shipped_at = case when ? = 'SHIPPED' then now() else shipped_at end,
                  delivered_at = case when ? = 'DELIVERED' then now() else delivered_at end
                where id = ?""", to, TenantContext.principal(), Rows.str(req, "tracking_number"), Rows.str(req, "carrier"),
                to, Rows.str(req, "failure_reason"), to, to, id);
        ObjectNode row = rows.get("card_order", id);
        events.publish(switch (to) {
            case "SHIPPED" -> "cards.card_order.shipped";
            case "DELIVERED" -> "cards.card_order.delivered";
            default -> "cards.card_order.status_changed";
        }, "card_order", row);
        return row;
    }
}
