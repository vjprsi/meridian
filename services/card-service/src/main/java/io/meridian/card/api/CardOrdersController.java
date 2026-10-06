package io.meridian.card.api;

import io.meridian.card.api.generated.CardOrdersApi;
import io.meridian.card.api.generated.model.CardOrder;
import io.meridian.card.api.generated.model.CardOrderUpdate;
import io.meridian.card.api.generated.model.CardOrdersBureauCallbacksRequest;
import io.meridian.card.api.generated.model.CardOrdersCancelRequest;
import io.meridian.card.api.generated.model.CardOrdersReshipRequest;
import io.meridian.card.application.OrderService;
import io.meridian.card.config.CardConfig;
import io.meridian.card.config.CardProperties;
import io.meridian.card.infrastructure.Signatures;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Physical card orders and the public, HMAC-signed bureau status callback. */
@RestController
public class CardOrdersController implements CardOrdersApi {
    private final OrderService orders;
    private final Actions actions;
    private final CrudEngine crud;
    private final CardProperties props;
    private final HttpServletRequest request;
    private final TransactionTemplate tx;

    public CardOrdersController(OrderService orders, Actions actions, CrudEngine crud, CardProperties props,
                                HttpServletRequest request, TransactionTemplate tx) {
        this.orders = orders;
        this.actions = actions;
        this.crud = crud;
        this.props = props;
        this.request = request;
        this.tx = tx;
    }

    @Override
    public ResponseEntity<CardOrder> cardOrdersUpdate(UUID id, String ifMatch, CardOrderUpdate body) {
        TenantContext.require();
        JsonNode req = actions.tree(body);
        return tx.execute(s -> {
            orders.checkUpdate(id, req);
            return crud.update("card_order", CardOrder.class, body, ifMatch, Crud.params().with("id", id));
        });
    }

    @Override
    public ResponseEntity<CardOrder> cardOrdersSubmit(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "submit", null, CardOrder.class, () -> orders.submit(id));
    }

    @Override
    public ResponseEntity<CardOrder> cardOrdersCancel(UUID id, String idempotencyKey, CardOrdersCancelRequest body) {
        return actions.action(idempotencyKey, id, "cancel", body, CardOrder.class,
                () -> orders.cancel(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<CardOrder> cardOrdersReship(UUID id, String idempotencyKey, CardOrdersReshipRequest body) {
        return actions.action(idempotencyKey, id, "reship", body, CardOrder.class,
                () -> orders.reship(id, actions.tree(body)));
    }

    @Override
    public ResponseEntity<CardOrder> cardOrdersBureauCallbacks(UUID tenantId, CardOrdersBureauCallbacksRequest body) {
        Signatures.verify(props.callbackSecret(), CardConfig.currentBody(), request.getHeader("X-Signature"));
        JsonNode req = actions.tree(body);
        try {
            return ResponseEntity.ok(TenantContext.callAs(tenantId, "bureau-callback",
                    () -> actions.model(orders.bureauUpdate(req), CardOrder.class)));
        } catch (ApiException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
