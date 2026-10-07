package io.meridian.customerchannel.api;

import io.meridian.customerchannel.api.generated.MySessionsApi;
import io.meridian.customerchannel.api.generated.model.CustomerSession;
import io.meridian.customerchannel.api.generated.model.CustomerSessionPage;
import io.meridian.customerchannel.api.generated.model.MySessionsRevokeAllRequest;
import io.meridian.customerchannel.api.generated.model.MySessionsRevokeRequest;
import io.meridian.customerchannel.application.CustomerSessions;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** The customer's sign-in sessions: list, sign out one, sign out everywhere. */
@RestController
public class MySessionsController implements MySessionsApi {
    private final CustomerSessions sessions;
    private final Http http;

    public MySessionsController(CustomerSessions sessions, Http http) {
        this.sessions = sessions;
        this.http = http;
    }

    @Override
    public ResponseEntity<CustomerSessionPage> mySessionsList(Integer limit, String cursor) {
        return http.ok(sessions.list(limit), CustomerSessionPage.class);
    }

    @Override
    public ResponseEntity<CustomerSession> mySessionsRevoke(UUID sessionId, String idempotencyKey,
                                                            MySessionsRevokeRequest body) {
        return http.ok(sessions.revoke(sessionId, http.body(body)), CustomerSession.class);
    }

    @Override
    public ResponseEntity<CustomerSessionPage> mySessionsRevokeAll(String idempotencyKey,
                                                                   MySessionsRevokeAllRequest body, Integer limit,
                                                                   String cursor) {
        return http.ok(sessions.revokeAll(http.body(body)), CustomerSessionPage.class);
    }
}
