package io.meridian.customerchannel.config;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Customer channel settings ({@code meridian.channels.*}).
 *
 * @param stepUp                money movement and security changes: the default policy and per-operation overrides
 * @param acrLevels             acr claim value -> Level of Authentication (Keycloak {@code acr.loa.map}); a numeric
 *                              acr not listed here counts as its number, anything else as level 0
 * @param sessions              customer session registry, new-device alerts and the identity provider sessions client
 * @param debtorNameFromProfile put the customer's display name on outgoing payments as debtor name
 */
@ConfigurationProperties(prefix = "meridian.channels")
public record ChannelProperties(StepUpPolicies stepUp, Map<String, Integer> acrLevels, Sessions sessions,
                                Boolean debtorNameFromProfile) {
    public ChannelProperties {
        stepUp = stepUp == null ? new StepUpPolicies(null, null, null, null) : stepUp;
        acrLevels = acrLevels == null || acrLevels.isEmpty() ? Map.of("0", 0, "1", 1, "2", 2) : Map.copyOf(acrLevels);
        sessions = sessions == null ? new Sessions(null, null, null, null) : sessions;
        debtorNameFromProfile = debtorNameFromProfile == null || debtorNameFromProfile;
    }

    /** Level of Authentication of an acr value (0 when absent or unknown). */
    public int level(String acr) {
        if (acr == null || acr.isBlank()) {
            return 0;
        }
        Integer mapped = acrLevels.get(acr.trim());
        if (mapped != null) {
            return mapped;
        }
        try {
            return Math.max(0, Integer.parseInt(acr.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Step-up policies.
     *
     * @param acr        minimum acr (default "2": password plus a second factor)
     * @param maxAge     the authentication (token {@code auth_time}) must be at most this old (default 5 minutes)
     * @param amr        when not empty, the token's amr must contain at least one of these methods
     * @param operations per-operation overrides keyed by operation id (e.g. {@code myCardsLimitsSet}); unset fields
     *                   take the default
     */
    public record StepUpPolicies(String acr, Duration maxAge, List<String> amr, Map<String, Policy> operations) {
        public StepUpPolicies {
            acr = acr == null || acr.isBlank() ? "2" : acr.trim();
            maxAge = maxAge == null ? Duration.ofMinutes(5) : maxAge;
            amr = amr == null ? List.of() : List.copyOf(amr);
            operations = operations == null ? Map.of() : new LinkedHashMap<>(operations);
        }

        /** The effective policy of one operation. */
        public Policy forOperation(String operation) {
            Policy o = operations.get(operation);
            return new Policy(o == null || o.acr() == null ? acr : o.acr(),
                    o == null || o.maxAge() == null ? maxAge : o.maxAge(),
                    o == null || o.amr() == null ? amr : o.amr());
        }
    }

    /** One step-up policy (fields may be null in overrides). */
    public record Policy(String acr, Duration maxAge, List<String> amr) {}

    /**
     * Customer sessions.
     *
     * @param enabled           register sessions (sid claim) and refuse revoked ones (default true)
     * @param newDeviceAlerts   publish {@code channels.customer_session.new_device} for a device the customer never
     *                          used before (default true)
     * @param touchInterval     how often last_seen_at is updated (default 1 minute)
     * @param identityProvider  the Keycloak admin client used to list and end sessions and list sign-in factors
     */
    public record Sessions(Boolean enabled, Boolean newDeviceAlerts, Duration touchInterval,
                           IdentityProvider identityProvider) {
        public Sessions {
            enabled = enabled == null || enabled;
            newDeviceAlerts = newDeviceAlerts == null || newDeviceAlerts;
            touchInterval = touchInterval == null ? Duration.ofMinutes(1) : touchInterval;
        }

        public boolean idpConfigured() {
            return identityProvider != null && identityProvider.configured();
        }
    }

    /**
     * Keycloak admin REST access for customer sessions (client {@code meridian-customer-sessions}: client credentials,
     * realm-management view-users and manage-users). Used only for the {@code sub} of the signed-in customer's token.
     *
     * @param baseUrl      Keycloak base URL (e.g. http://localhost:15180)
     * @param realm        realm (default meridian)
     * @param clientId     confidential client id
     * @param clientSecret its secret
     * @param timeout      per-call timeout (default 5 s)
     */
    public record IdentityProvider(URI baseUrl, String realm, String clientId, String clientSecret, Duration timeout) {
        public IdentityProvider {
            realm = realm == null || realm.isBlank() ? "meridian" : realm;
            timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
        }

        public boolean configured() {
            return baseUrl != null && clientId != null && !clientId.isBlank() && clientSecret != null
                    && !clientSecret.isBlank();
        }
    }
}
