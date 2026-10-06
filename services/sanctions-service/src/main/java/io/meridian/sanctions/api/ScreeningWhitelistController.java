package io.meridian.sanctions.api;

import io.meridian.sanctions.api.generated.ScreeningWhitelistApi;
import io.meridian.sanctions.api.generated.model.ScreeningWhitelist;
import io.meridian.sanctions.api.generated.model.ScreeningWhitelistCreate;
import io.meridian.sanctions.application.ScreeningService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Good-guy whitelist: suppresses a known false positive for one subject and list entry. */
@RestController
public class ScreeningWhitelistController implements ScreeningWhitelistApi {
    private final ScreeningService screening;
    private final Http http;

    public ScreeningWhitelistController(ScreeningService screening, Http http) {
        this.screening = screening;
        this.http = http;
    }

    @Override
    public ResponseEntity<ScreeningWhitelist> screeningWhitelistCreate(String idempotencyKey, ScreeningWhitelistCreate body) {
        return http.once(idempotencyKey, body, ScreeningWhitelist.class, () -> http.respond(HttpStatus.CREATED,
                screening.whitelist(http.tree(body)).toString(), ScreeningWhitelist.class));
    }
}
