package io.meridian.customer.domain;

import java.util.Set;

/** Status machines of the customer-service aggregates. Status columns change only through these actions. */
public final class Lifecycles {
    private Lifecycles() {}

    /** Statuses in which the bank still has a relationship with the customer. */
    public static final Set<String> CUSTOMER_OPEN = Set.of("PROSPECT", "PENDING_KYC", "ACTIVE", "DORMANT", "SUSPENDED", "BLOCKED");

    /** Statuses a customer may be created in; everything else is reached through actions. */
    public static final Set<String> CUSTOMER_INITIAL = Set.of("PROSPECT", "PENDING_KYC");

    public static final StateMachine CUSTOMER = new StateMachine("customer")
            .on("activate", "ACTIVE", "PROSPECT", "PENDING_KYC")
            .on("suspend", "SUSPENDED", "ACTIVE", "DORMANT", "PENDING_KYC")
            .on("reactivate", "ACTIVE", "SUSPENDED", "DORMANT")
            .on("close", "CLOSED", "PROSPECT", "PENDING_KYC", "ACTIVE", "DORMANT", "SUSPENDED")
            .on("mark-deceased", "DECEASED", "PROSPECT", "PENDING_KYC", "ACTIVE", "DORMANT", "SUSPENDED", "BLOCKED")
            .on("merge", "CLOSED", "PROSPECT", "PENDING_KYC", "ACTIVE", "DORMANT", "SUSPENDED");

    public static final StateMachine DEVICE = new StateMachine("device")
            .on("trust", "TRUSTED", "PENDING")
            .on("revoke", "REVOKED", "PENDING", "TRUSTED", "LOST");

    public static final StateMachine CREDENTIAL = new StateMachine("credential")
            .on("unlock", "ACTIVE", "LOCKED")
            .on("revoke", "REVOKED", "ACTIVE", "LOCKED", "EXPIRED");

    public static final StateMachine CONSENT = new StateMachine("consent")
            .on("withdraw", "WITHDRAWN", "GIVEN")
            .on("renew", "GIVEN", "GIVEN", "EXPIRED");

    public static final StateMachine PRIVACY_REQUEST = new StateMachine("privacy request")
            .on("verify-identity", "IN_PROGRESS", "RECEIVED", "VERIFYING_IDENTITY")
            .on("execute", "COMPLETED", "IN_PROGRESS")
            .on("reject", "REJECTED", "RECEIVED", "VERIFYING_IDENTITY", "IN_PROGRESS");
}
