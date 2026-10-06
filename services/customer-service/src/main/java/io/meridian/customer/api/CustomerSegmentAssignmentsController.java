package io.meridian.customer.api;

import io.meridian.customer.api.generated.CustomerSegmentAssignmentsApi;
import io.meridian.customer.api.generated.model.CustomerSegmentAssignment;
import io.meridian.customer.api.generated.model.CustomerSegmentAssignmentCreate;
import io.meridian.customer.application.SegmentService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Manual segment assignment (active segment, no duplicate live assignment). */
@RestController
public class CustomerSegmentAssignmentsController implements CustomerSegmentAssignmentsApi {
    private final SegmentService segments;
    private final Http http;

    public CustomerSegmentAssignmentsController(SegmentService segments, Http http) {
        this.segments = segments;
        this.http = http;
    }

    @Override
    public ResponseEntity<CustomerSegmentAssignment> customerSegmentAssignmentsCreate(UUID customerId, String idempotencyKey,
                                                                                      CustomerSegmentAssignmentCreate body) {
        return http.created(idempotencyKey, "segment-assignments.create", customerId, body, CustomerSegmentAssignment.class,
                () -> segments.assign(customerId, body));
    }
}
