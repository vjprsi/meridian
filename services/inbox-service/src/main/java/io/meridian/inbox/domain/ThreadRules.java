package io.meridian.inbox.domain;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Set;

/**
 * Secure thread rules: status after a message, who may send what, and which messages a reader marks as read.
 * Pure (no Spring, no SQL).
 */
public final class ThreadRules {
    public static final Set<String> SENDERS = Set.of("CUSTOMER", "STAFF", "SYSTEM", "BOT");

    private ThreadRules() {}

    /** Result of checking a message against the thread. {@code error} is a stable problem code, null when allowed. */
    public record Check(String error, String detail) {
        static Check ok() {
            return new Check(null, null);
        }

        public boolean allowed() {
            return error == null;
        }
    }

    public static Check canSend(String threadStatus, String senderType, boolean internalNote) {
        if (!SENDERS.contains(senderType)) {
            return new Check("invalid_sender", "sender_type must be one of " + SENDERS);
        }
        if ("CLOSED".equals(threadStatus)) {
            return new Check("thread_closed", "The thread is closed; reopen it before sending");
        }
        if (internalNote && !"STAFF".equals(senderType)) {
            return new Check("internal_note_not_allowed", "Only staff can write internal notes");
        }
        return Check.ok();
    }

    /**
     * Thread status after a message. Internal notes do not change it; a customer message waits on the bank, any
     * bank-side visible message (staff, system, bot) waits on the customer.
     */
    public static String statusAfter(String current, String senderType, boolean internalNote) {
        if (internalNote) {
            return current;
        }
        return "CUSTOMER".equals(senderType) ? "AWAITING_BANK" : "AWAITING_CUSTOMER";
    }

    /** Whether a message counts as unread for the customer (true) or for staff (false); null = for nobody. */
    public static Boolean unreadForCustomer(String senderType, boolean internalNote) {
        if (internalNote) {
            return null;
        }
        return !"CUSTOMER".equals(senderType);
    }

    /** A reader marks the messages of the other party: the customer reads bank-side visible messages, staff reads customer ones. */
    public static boolean marksAsRead(String readerType, String senderType, boolean internalNote) {
        if (internalNote) {
            return false;
        }
        if ("CUSTOMER".equals(readerType)) {
            return !"CUSTOMER".equals(senderType);
        }
        return "CUSTOMER".equals(senderType);
    }

    public static Check canClose(String status) {
        return "CLOSED".equals(status) ? new Check("invalid_transition", "The thread is already closed") : Check.ok();
    }

    public static Check canReopen(String status, OffsetDateTime closedAt, OffsetDateTime now, int windowDays) {
        if (!"CLOSED".equals(status)) {
            return new Check("invalid_transition", "Only a closed thread can be reopened (status " + status + ")");
        }
        if (closedAt != null && windowDays > 0 && Duration.between(closedAt, now).toDays() >= windowDays) {
            return new Check("reopen_window_passed", "The thread was closed more than " + windowDays
                    + " days ago; open a new thread");
        }
        return Check.ok();
    }

    public static Check canAssign(String status) {
        return "CLOSED".equals(status) ? new Check("thread_closed", "A closed thread cannot be assigned") : Check.ok();
    }
}
