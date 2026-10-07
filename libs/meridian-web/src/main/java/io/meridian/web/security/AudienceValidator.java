package io.meridian.web.security;

import java.util.ArrayList;
import java.util.List;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * SEC-14: a token is accepted only when it was issued for the Meridian API (its {@code aud} contains a configured
 * audience or this service's name) and, when the service restricts clients, by an allowed OAuth client ({@code azp}).
 * Contributed to Spring Boot's auto-configured {@code JwtDecoder} as an additional validator, next to the issuer and
 * expiry checks.
 */
public final class AudienceValidator implements OAuth2TokenValidator<Jwt> {
    private final List<String> audiences;
    private final List<String> allowedClients;

    public AudienceValidator(SecurityProperties props, String serviceName) {
        List<String> a = new ArrayList<>(props.audiences());
        if (!a.isEmpty() && serviceName != null && !serviceName.isBlank()) {
            a.add(serviceName);
        }
        this.audiences = List.copyOf(a);
        this.allowedClients = props.allowedClients();
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        if (!audiences.isEmpty()) {
            List<String> aud = jwt.getAudience();
            if (aud == null || aud.stream().noneMatch(audiences::contains)) {
                return failure("The token was not issued for this API (aud must contain one of " + audiences + ")");
            }
        }
        if (!allowedClients.isEmpty()) {
            String azp = jwt.getClaimAsString("azp");
            if (azp == null || !allowedClients.contains(azp)) {
                return failure("Tokens of client " + azp + " are not accepted by this service");
            }
        }
        return OAuth2TokenValidatorResult.success();
    }

    private static OAuth2TokenValidatorResult failure(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, description, null));
    }
}
