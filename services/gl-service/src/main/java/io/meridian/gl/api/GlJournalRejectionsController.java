package io.meridian.gl.api;

import io.meridian.gl.api.generated.GlJournalRejectionsApi;
import org.springframework.web.bind.annotation.RestController;

/**
 * Checker rejections of manual GL journals (append-only, read-only API: list and get use the generated CRUD
 * defaults). Rejections are recorded by {@code POST /v1/gl/journals/{id}/reject}.
 */
@RestController
public class GlJournalRejectionsController implements GlJournalRejectionsApi {
}
