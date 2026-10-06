package io.meridian.inbox.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.inbox.api.generated.SecureMessagesApi;
import io.meridian.inbox.api.generated.model.SecureMessageReceiptPage;
import io.meridian.inbox.application.ThreadService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Secure messages (append-only) and their read receipts; list/get use the generic CRUD engine. */
@RestController
public class SecureMessagesController implements SecureMessagesApi {
    private final ThreadService threads;
    private final Http http;

    public SecureMessagesController(ThreadService threads, Http http) {
        this.threads = threads;
        this.http = http;
    }

    @Override
    public ResponseEntity<SecureMessageReceiptPage> secureMessagesReceipts(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        return ResponseEntity.ok(http.read(threads.receipts(id, limit, cursor), SecureMessageReceiptPage.class));
    }
}
