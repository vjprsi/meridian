package io.meridian.workflow.domain;

import java.util.Set;

/** The authenticated user acting on approvals and tasks: id, roles and whether step-up MFA was performed. */
public record Caller(String principal, Set<String> roles, boolean mfaVerified) {
    public Caller {
        roles = Set.copyOf(roles);
    }
}
