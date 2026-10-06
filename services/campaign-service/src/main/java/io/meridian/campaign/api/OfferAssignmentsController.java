package io.meridian.campaign.api;

import io.meridian.campaign.api.generated.OfferAssignmentsApi;
import io.meridian.campaign.api.generated.model.OfferAssignment;
import io.meridian.campaign.api.generated.model.OfferAssignmentPage;
import io.meridian.campaign.api.generated.model.OfferAssignmentsAcceptRequest;
import io.meridian.campaign.api.generated.model.OfferAssignmentsDeclineRequest;
import io.meridian.campaign.application.Json;
import io.meridian.campaign.application.OfferService;
import io.meridian.common.tenancy.TenantContext;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Customer responses to offers. */
@RestController
public class OfferAssignmentsController implements OfferAssignmentsApi {
    private final OfferService offers;
    private final Http http;

    public OfferAssignmentsController(OfferService offers, Http http) {
        this.offers = offers;
        this.http = http;
    }

    @Override
    public ResponseEntity<OfferAssignment> offerAssignmentsShown(UUID id, String idempotencyKey) {
        return http.ok(idempotencyKey, "assignment.shown", id, null, OfferAssignment.class, () -> offers.shown(id));
    }

    @Override
    public ResponseEntity<OfferAssignment> offerAssignmentsClicked(UUID id, String idempotencyKey) {
        return http.ok(idempotencyKey, "assignment.clicked", id, null, OfferAssignment.class, () -> offers.clicked(id));
    }

    @Override
    public ResponseEntity<OfferAssignment> offerAssignmentsAccept(UUID id, String idempotencyKey,
                                                                  OfferAssignmentsAcceptRequest body) {
        JsonNode req = http.tree(body);
        return http.ok(idempotencyKey, "assignment.accept", id, body, OfferAssignment.class,
                () -> offers.accept(id, Json.text(req, "resulting_object_type"), Json.uuid(req, "resulting_object_id")));
    }

    @Override
    public ResponseEntity<OfferAssignment> offerAssignmentsDecline(UUID id, String idempotencyKey,
                                                                   OfferAssignmentsDeclineRequest body) {
        String reason = Json.text(http.tree(body), "reason");
        return http.ok(idempotencyKey, "assignment.decline", id, body, OfferAssignment.class, () -> offers.decline(id, reason));
    }

    @Override
    public ResponseEntity<OfferAssignmentPage> offerAssignmentsForCustomer(UUID customerId, Integer limit, String cursor) {
        TenantContext.require();
        return ResponseEntity.ok(http.read(offers.forCustomer(customerId, limit, cursor), OfferAssignmentPage.class));
    }
}
