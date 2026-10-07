package io.meridian.customerchannel.api;

import io.meridian.customerchannel.api.generated.MyThreadsApi;
import io.meridian.customerchannel.api.generated.model.MyThreadsCreateRequest;
import io.meridian.customerchannel.api.generated.model.MyThreadsAddMessagesRequest;
import io.meridian.customerchannel.api.generated.model.SecureMessage;
import io.meridian.customerchannel.api.generated.model.SecureMessagePage;
import io.meridian.customerchannel.api.generated.model.SecureMessageThread;
import io.meridian.customerchannel.api.generated.model.SecureMessageThreadPage;
import io.meridian.customerchannel.application.InboxService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Secure messaging threads of the customer. */
@RestController
public class MyThreadsController implements MyThreadsApi {
    private final InboxService inbox;
    private final Http http;

    public MyThreadsController(InboxService inbox, Http http) {
        this.inbox = inbox;
        this.http = http;
    }

    @Override
    public ResponseEntity<SecureMessageThreadPage> myThreadsList(Integer limit, String cursor) {
        return http.ok(inbox.threads(limit, cursor), SecureMessageThreadPage.class);
    }

    @Override
    public ResponseEntity<SecureMessageThread> myThreadsCreate(String idempotencyKey, MyThreadsCreateRequest body) {
        return http.ok(inbox.open(http.body(body), idempotencyKey), SecureMessageThread.class);
    }

    @Override
    public ResponseEntity<SecureMessageThread> myThreadsGet(UUID threadId) {
        return http.ok(inbox.thread(threadId), SecureMessageThread.class);
    }

    @Override
    public ResponseEntity<SecureMessagePage> myThreadsMessages(UUID threadId, Integer limit, String cursor) {
        return http.ok(inbox.messages(threadId, limit, cursor), SecureMessagePage.class);
    }

    @Override
    public ResponseEntity<SecureMessage> myThreadsAddMessages(UUID threadId, String idempotencyKey,
                                                              MyThreadsAddMessagesRequest body) {
        return http.ok(inbox.send(threadId, http.body(body), idempotencyKey), SecureMessage.class);
    }

    @Override
    public ResponseEntity<SecureMessageThread> myThreadsRead(UUID threadId, String idempotencyKey) {
        return http.ok(inbox.read(threadId, idempotencyKey), SecureMessageThread.class);
    }
}
