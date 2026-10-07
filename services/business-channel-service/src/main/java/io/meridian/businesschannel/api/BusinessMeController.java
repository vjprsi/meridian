package io.meridian.businesschannel.api;

import io.meridian.businesschannel.api.generated.BusinessMeApi;
import io.meridian.businesschannel.application.UserAdminService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in business user, the business and the user's entitlements. */
@RestController
public class BusinessMeController implements BusinessMeApi {
    private final UserAdminService users;
    private final Http http;

    public BusinessMeController(UserAdminService users, Http http) {
        this.users = users;
        this.http = http;
    }

    @Override
    public ResponseEntity<Map<String, Object>> businessMeGet() {
        return http.object(users.me());
    }
}
