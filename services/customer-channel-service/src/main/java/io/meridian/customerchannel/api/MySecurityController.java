package io.meridian.customerchannel.api;

import io.meridian.customerchannel.api.generated.MySecurityApi;
import io.meridian.customerchannel.application.SecurityOverview;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** The customer's sign-in security: authentication strength, second factors, step-up policy. */
@RestController
public class MySecurityController implements MySecurityApi {
    private final SecurityOverview security;
    private final Http http;

    public MySecurityController(SecurityOverview security, Http http) {
        this.security = security;
        this.http = http;
    }

    @Override
    public ResponseEntity<Map<String, Object>> mySecurityGet() {
        return http.object(security.get());
    }
}
