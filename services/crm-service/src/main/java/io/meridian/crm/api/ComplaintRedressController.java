package io.meridian.crm.api;

import io.meridian.crm.api.generated.ComplaintRedressApi;
import io.meridian.crm.api.generated.model.ComplaintRedress;
import io.meridian.crm.api.generated.model.ComplaintRedressApproveRequest;
import io.meridian.crm.api.generated.model.ComplaintRedressCreate;
import io.meridian.crm.api.generated.model.ComplaintRedressRejectRequest;
import io.meridian.crm.application.Json;
import io.meridian.crm.application.RedressService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Complaint redress: four-eyes approval and payment through the ledger. */
@RestController
public class ComplaintRedressController implements ComplaintRedressApi {
    private final RedressService redress;
    private final Http http;

    public ComplaintRedressController(RedressService redress, Http http) {
        this.redress = redress;
        this.http = http;
    }

    @Override
    public ResponseEntity<ComplaintRedress> complaintRedressCreate(String idempotencyKey, ComplaintRedressCreate body) {
        return http.created(idempotencyKey, "redress.create", body, ComplaintRedress.class, () -> redress.create(body));
    }

    @Override
    public ResponseEntity<ComplaintRedress> complaintRedressApprove(UUID id, String idempotencyKey,
                                                                   ComplaintRedressApproveRequest body) {
        String comment = Json.text(http.tree(body), "comment");
        return http.ok(idempotencyKey, "redress.approve", id, body, ComplaintRedress.class, () -> redress.approve(id, comment));
    }

    @Override
    public ResponseEntity<ComplaintRedress> complaintRedressReject(UUID id, String idempotencyKey,
                                                                  ComplaintRedressRejectRequest body) {
        String reason = Json.required(http.tree(body), "reason");
        return http.ok(idempotencyKey, "redress.reject", id, body, ComplaintRedress.class, () -> redress.reject(id, reason));
    }

    @Override
    public ResponseEntity<ComplaintRedress> complaintRedressPay(UUID id, String idempotencyKey) {
        return http.ok(idempotencyKey, "redress.pay", id, null, ComplaintRedress.class, () -> redress.pay(id));
    }
}
