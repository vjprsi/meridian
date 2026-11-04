package io.meridian.payment.api;

import io.meridian.payment.application.OrderService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * WE-ETM-1 (G-08): Order &amp; Pay (ETM) orders - creation, the segregated payment / fulfilment legs, the
 * payment-gateway heartbeat, and ambiguous-case settlement (resolve one or the whole due backlog).
 *
 * <p>Like the WE-TXN-2 wallet controller this is written against Spring MVC directly rather than implementing a
 * generated {@code *Api} interface, so payment-service compiles in this worktree before the host regenerates the
 * contract from the updated {@code spec/domains/payments.yaml}. The request / response shapes match the spec actions
 * exactly (request bodies are JSON objects; every action returns the order row). After the host runs the generator
 * this can be switched to implement {@code PaymentOrdersApi} with no behaviour change; the CRUD {@code list} / plain
 * {@code get} / {@code create} shapes are then served by the generated controller (here create and a convenience get
 * are provided directly).
 */
@RestController
@RequestMapping("/v1/payment-orders")
public class PaymentOrdersController {
    private final OrderService orders;
    private final Actions actions;

    public PaymentOrdersController(OrderService orders, Actions actions) {
        this.orders = orders;
        this.actions = actions;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                                      @RequestBody Map<String, Object> body) {
        JsonNode req = actions.tree(body);
        return actions.object(idempotencyKey, null, "payment-order:create", body,
                () -> orders.create(req, idempotencyKey));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable UUID id) {
        return actions.read(orders.get(id));
    }

    @GetMapping("/{id}/events")
    public ResponseEntity<List<ObjectNode>> events(@PathVariable UUID id) {
        return ResponseEntity.ok(orders.listEvents(id));
    }

    @PostMapping("/{id}/pay")
    public ResponseEntity<Map<String, Object>> pay(@PathVariable UUID id,
                                                   @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                   @RequestBody Map<String, Object> body) {
        JsonNode req = actions.tree(body);
        return actions.object(idempotencyKey, id, "payment-order:pay", body, () -> orders.pay(id, req));
    }

    @PostMapping("/{id}/payment-result")
    public ResponseEntity<Map<String, Object>> paymentResult(@PathVariable UUID id,
                                                             @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                             @RequestBody Map<String, Object> body) {
        JsonNode req = actions.tree(body);
        return actions.object(idempotencyKey, id, "payment-order:payment-result", body, () -> orders.paymentResult(id,
                Actions.text(req, "outcome"), Actions.text(req, "pg_heartbeat_status"), Actions.text(req, "reason")));
    }

    @PostMapping("/{id}/fulfil")
    public ResponseEntity<Map<String, Object>> fulfil(@PathVariable UUID id,
                                                      @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                      @RequestBody Map<String, Object> body) {
        JsonNode req = actions.tree(body);
        return actions.object(idempotencyKey, id, "payment-order:fulfil", body,
                () -> orders.fulfil(id, Actions.text(req, "status"), Actions.text(req, "reason")));
    }

    @PostMapping("/{id}/heartbeat")
    public ResponseEntity<Map<String, Object>> heartbeat(@PathVariable UUID id,
                                                         @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                         @RequestBody Map<String, Object> body) {
        JsonNode req = actions.tree(body);
        return actions.object(idempotencyKey, id, "payment-order:heartbeat", body,
                () -> orders.heartbeat(id, Actions.text(req, "status")));
    }

    @PostMapping("/{id}/resolve")
    public ResponseEntity<Map<String, Object>> resolve(@PathVariable UUID id,
                                                       @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return actions.object(idempotencyKey, id, "payment-order:resolve", null, () -> orders.resolve(id));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable UUID id,
                                                      @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                      @RequestBody(required = false) Map<String, Object> body) {
        JsonNode req = actions.tree(body);
        return actions.object(idempotencyKey, id, "payment-order:cancel", body,
                () -> orders.cancel(id, Actions.text(req, "reason")));
    }

    @PostMapping("/resolve-ambiguous")
    public ResponseEntity<Map<String, Object>> resolveAmbiguous(@RequestHeader("Idempotency-Key") String idempotencyKey) {
        return actions.object(idempotencyKey, null, "payment-order:resolve-ambiguous", null,
                orders::resolveDueAmbiguous);
    }
}
