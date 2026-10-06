package io.meridian.customer.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.customer.api.generated.CustomerGroupsApi;
import io.meridian.customer.api.generated.model.CustomerGroupMember;
import io.meridian.customer.api.generated.model.CustomerGroupMemberPage;
import io.meridian.customer.api.generated.model.CustomerGroupsMembersRequest;
import io.meridian.customer.application.GroupService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

/**
 * Groups and their members. Contract gap: {@code /exposure} is declared 204 without a body; it returns 200 with
 * the exposure document.
 */
@RestController
public class CustomerGroupsController implements CustomerGroupsApi {
    private final GroupService groups;
    private final Http http;

    public CustomerGroupsController(GroupService groups, Http http) {
        this.groups = groups;
        this.http = http;
    }

    @Override
    public ResponseEntity<Void> customerGroupsDelete(UUID id) {
        TenantContext.require();
        groups.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<CustomerGroupMemberPage> customerGroupsMembers(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        return ResponseEntity.ok(http.read((ObjectNode) groups.members(id, limit, cursor), CustomerGroupMemberPage.class));
    }

    @Override
    public ResponseEntity<CustomerGroupMember> customerGroupsAddMembers(UUID id, String idempotencyKey,
                                                                       CustomerGroupsMembersRequest body) {
        return http.created(idempotencyKey, "groups.add-member", id, body, CustomerGroupMember.class,
                () -> groups.addMember(id, http.tree(body)));
    }

    @Override
    public ResponseEntity<Void> customerGroupsRemoveMembers(UUID id, UUID memberId, String idempotencyKey) {
        return http.noContent(idempotencyKey, "groups.remove-member", id + "/" + memberId, null,
                () -> groups.removeMember(id, memberId));
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public ResponseEntity customerGroupsExposure(UUID id) {
        TenantContext.require();
        return ResponseEntity.ok(groups.exposure(id));
    }
}
