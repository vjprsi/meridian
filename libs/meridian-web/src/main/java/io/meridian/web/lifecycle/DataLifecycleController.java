package io.meridian.web.lifecycle;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.security.RequiredScope;
import java.util.Set;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Internal data-lifecycle API of every data-owning service (GAP-08), see {@link DataLifecycleService}:
 * {@code POST /v1/internal/data-lifecycle/{count,extract,confirm,purge}} for archive-service and
 * {@code POST /v1/internal/data-lifecycle/{export,erase}} for data subject requests.
 *
 * <p>Only Meridian services may call it: an internal service token (operator-grade scope, never a bank user's token)
 * naming the calling service ({@code X-Meridian-Service}), which must be one of
 * {@code meridian.data-lifecycle.callers} (default archive-service, customer-service). The tenant is the one the
 * service token carries in {@code X-Tenant-Id}; row-level security applies to every statement.
 */
@RestController
@RequestMapping("/v1/internal/data-lifecycle")
public class DataLifecycleController {
    private final DataLifecycleService lifecycle;
    private final Set<String> callers;

    public DataLifecycleController(DataLifecycleService lifecycle, Set<String> callers) {
        this.lifecycle = lifecycle;
        this.callers = Set.copyOf(callers);
    }

    @PostMapping("/count")
    @RequiredScope("platform:operator")
    public ObjectNode count(@RequestBody JsonNode body) {
        guard();
        return lifecycle.count(body);
    }

    @PostMapping("/extract")
    @RequiredScope("platform:operator")
    public ObjectNode extract(@RequestBody JsonNode body) {
        guard();
        return lifecycle.extract(body);
    }

    @PostMapping("/confirm")
    @RequiredScope("platform:operator")
    public ObjectNode confirm(@RequestBody JsonNode body) {
        guard();
        return lifecycle.confirm(body);
    }

    @PostMapping("/purge")
    @RequiredScope("platform:operator")
    public ObjectNode purge(@RequestBody JsonNode body) {
        guard();
        return lifecycle.purge(body);
    }

    @PostMapping("/export")
    @RequiredScope("platform:operator")
    public ObjectNode export(@RequestBody JsonNode body) {
        guard();
        return lifecycle.export(body);
    }

    @PostMapping("/erase")
    @RequiredScope("platform:operator")
    public ObjectNode erase(@RequestBody JsonNode body) {
        guard();
        return lifecycle.erase(body);
    }

    private void guard() {
        TenantContext.require();
        String caller = TenantContext.callingService().orElse(null);
        if (caller == null || !callers.contains(caller)) {
            throw new ApiException(403, "caller_not_allowed",
                    "Data-lifecycle operations are reserved to " + String.join(", ", callers.stream().sorted().toList()));
        }
    }
}
