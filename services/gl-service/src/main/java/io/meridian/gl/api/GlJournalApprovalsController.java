package io.meridian.gl.api;

import io.meridian.gl.api.generated.GlJournalApprovalsApi;
import org.springframework.web.bind.annotation.RestController;

/**
 * GL journal approvals (append-only checker sign-offs). List and get are generic CRUD; approvals are created by
 * POST /v1/gl/journals/{id}/approve.
 */
@RestController
public class GlJournalApprovalsController implements GlJournalApprovalsApi {
}
