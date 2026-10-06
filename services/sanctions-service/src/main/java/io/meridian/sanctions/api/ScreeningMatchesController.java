package io.meridian.sanctions.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.sanctions.api.generated.ScreeningMatchesApi;
import io.meridian.sanctions.api.generated.model.ScreeningMatch;
import io.meridian.sanctions.api.generated.model.ScreeningMatchUpdate;
import io.meridian.sanctions.application.ScreeningService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Match dispositions (TRUE_MATCH / FALSE_POSITIVE) with four-eyes on clearing a true match. */
@RestController
public class ScreeningMatchesController implements ScreeningMatchesApi {
    private final ScreeningService screening;
    private final Http http;

    public ScreeningMatchesController(ScreeningService screening, Http http) {
        this.screening = screening;
        this.http = http;
    }

    @Override
    public ResponseEntity<ScreeningMatch> screeningMatchesUpdate(UUID id, String ifMatch, ScreeningMatchUpdate body) {
        TenantContext.require();
        return http.ok(screening.disposition(id, http.tree(body), Http.ifMatch(ifMatch)).toString(), ScreeningMatch.class);
    }
}
