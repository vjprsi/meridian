package io.meridian.workflow.application;

import io.meridian.common.error.ApiException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * SEC-03: who may open an approval request for which resource type.
 *
 * <ul>
 *   <li>{@code meridian.workflow.approval-owners}: resource type -> owning service. Only that service, calling with its
 *       own service identity ({@code X-Meridian-Service} with an internal token, see
 *       {@code io.meridian.common.tenancy.TenantContext#callingService()}), may open requests for the type. The decision
 *       is dispatched only to the owner ({@code owner_service} on the request), so no other service's handler runs.</li>
 *   <li>{@code meridian.workflow.user-request-types}: generic types a user may open directly (no service executes them;
 *       {@code owner_service = workflow-service}). Everything else is refused for user tokens.</li>
 * </ul>
 * A service opening a type that is not registered is refused ({@code unregistered_resource_type}); register new types
 * here together with their {@code ApprovalOutcomeHandler} ({@code ApprovalOwnersRegistryTest} checks the sources).
 */
@Component
public class ApprovalOwners {
    public static final String WORKFLOW = "workflow-service";

    private final Map<String, String> owners;
    private final Set<String> userTypes;

    @org.springframework.beans.factory.annotation.Autowired
    public ApprovalOwners(Environment env) {
        Binder binder = Binder.get(env);
        this.owners = Map.copyOf(binder.bind("meridian.workflow.approval-owners",
                Bindable.mapOf(String.class, String.class)).orElse(Map.of()));
        this.userTypes = Set.copyOf(binder.bind("meridian.workflow.user-request-types",
                Bindable.listOf(String.class)).orElse(List.of()));
    }

    public ApprovalOwners(Map<String, String> owners, Set<String> userTypes) {
        this.owners = Map.copyOf(owners);
        this.userTypes = Set.copyOf(userTypes);
    }

    public Optional<String> ownerOf(String resourceType) {
        return Optional.ofNullable(owners.get(resourceType));
    }

    public Map<String, String> owners() {
        return owners;
    }

    /**
     * The owner_service to record for a request opened by {@code callingService} (empty: a user token). Throws 403 when
     * the caller may not open requests of this type.
     */
    public String authorize(String resourceType, Optional<String> callingService) {
        String owner = owners.get(resourceType);
        if (callingService.isEmpty()) {
            if (userTypes.contains(resourceType) && owner == null) {
                return WORKFLOW;
            }
            throw new ApiException(403, "service_owned_resource_type", "Approval requests for " + resourceType
                    + " are opened only by " + (owner == null ? "the owning service" : owner)
                    + " with its service identity, never directly by a user");
        }
        String caller = callingService.get();
        if (owner == null) {
            throw new ApiException(403, "unregistered_resource_type", "Resource type " + resourceType
                    + " has no registered owning service (meridian.workflow.approval-owners)");
        }
        if (!owner.equals(caller)) {
            throw new ApiException(403, "not_resource_owner", "Resource type " + resourceType + " belongs to " + owner
                    + ", not " + caller);
        }
        return owner;
    }
}
