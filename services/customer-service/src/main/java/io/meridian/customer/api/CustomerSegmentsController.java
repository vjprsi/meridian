package io.meridian.customer.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.customer.api.generated.CustomerSegmentsApi;
import io.meridian.customer.api.generated.model.CustomerSegment;
import io.meridian.customer.api.generated.model.CustomerSegmentCreate;
import io.meridian.customer.api.generated.model.CustomerSegmentUpdate;
import io.meridian.customer.application.SegmentService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

/** Segments with validated criteria; evaluate returns the assignment summary. */
@RestController
public class CustomerSegmentsController implements CustomerSegmentsApi {
    private final SegmentService segments;
    private final Http http;

    public CustomerSegmentsController(SegmentService segments, Http http) {
        this.segments = segments;
        this.http = http;
    }

    @Override
    public ResponseEntity<CustomerSegment> customerSegmentsCreate(String idempotencyKey, CustomerSegmentCreate body) {
        return http.created(idempotencyKey, "segments.create", null, body, CustomerSegment.class, () -> segments.create(body));
    }

    @Override
    public ResponseEntity<CustomerSegment> customerSegmentsUpdate(UUID id, String ifMatch, CustomerSegmentUpdate body) {
        TenantContext.require();
        ObjectNode row = segments.update(id, ifMatch, body);
        return ResponseEntity.ok().eTag("\"" + row.get("row_version").asString() + "\"").body(http.read(row, CustomerSegment.class));
    }

    @Override
    public ResponseEntity<Map<String, Object>> customerSegmentsEvaluate(UUID id, String idempotencyKey) {
        return http.document(idempotencyKey, "segments.evaluate", id, null, HttpStatus.OK, () -> segments.evaluate(id));
    }
}
