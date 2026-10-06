package io.meridian.identity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** identity-service: Staff and operator identity: users, roles, permissions, ABAC policies, SSO (OIDC/SAML), MFA, sessions; and machine identity: OAuth 2.1 API clients, API keys, scopes, rate-limit pol */
@SpringBootApplication
public class IdentityApplication {
    public static void main(String[] args) {
        SpringApplication.run(IdentityApplication.class, args);
    }
}
