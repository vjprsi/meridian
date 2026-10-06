package io.meridian.card.domain;

import io.meridian.common.error.ApiException;
import java.util.Set;

/** Card status state machine. Every status change goes through here; anything else is 409 invalid_transition. */
public final class CardLifecycle {
    private CardLifecycle() {}

    /** Statuses after which the card can never authorise again. */
    public static final Set<String> TERMINAL = Set.of("BLOCKED", "LOST", "STOLEN", "EXPIRED", "CLOSED", "REPLACED");
    /** Statuses that do not count towards a product's max_cards_per_customer. */
    public static final Set<String> NOT_COUNTED = TERMINAL;
    public static final Set<String> BLOCK_TARGETS = Set.of("BLOCKED", "LOST", "STOLEN");

    public static String activate(String from) {
        return require("activate", from, Set.of("CREATED"), "ACTIVE");
    }

    public static String freeze(String from) {
        return require("freeze", from, Set.of("ACTIVE"), "FROZEN");
    }

    public static String unfreeze(String from) {
        return require("unfreeze", from, Set.of("FROZEN"), "ACTIVE");
    }

    public static String block(String from, String target) {
        if (!BLOCK_TARGETS.contains(target)) {
            throw ApiException.unprocessable("invalid_block_status", "status must be BLOCKED, LOST or STOLEN");
        }
        return require("block", from, Set.of("CREATED", "ACTIVE", "FROZEN"), target);
    }

    public static String close(String from) {
        if ("CLOSED".equals(from) || "REPLACED".equals(from)) {
            throw invalid("close", from);
        }
        return "CLOSED";
    }

    public static String expire(String from) {
        return require("expire", from, Set.of("CREATED", "ACTIVE", "FROZEN"), "EXPIRED");
    }

    /** Checks a card may be replaced; returns the old card's new status for the replacement reason. */
    public static String replace(String from, String reason) {
        if ("CLOSED".equals(from) || "REPLACED".equals(from)) {
            throw invalid("replace", from);
        }
        if (Set.of("LOST", "STOLEN", "BLOCKED").contains(from)) {
            return from; // already blocked for security reasons: keep it
        }
        return switch (reason) {
            case "LOST" -> "LOST";
            case "STOLEN" -> "STOLEN";
            case "FRAUD" -> "BLOCKED";
            default -> "REPLACED";
        };
    }

    public static void renew(String from) {
        require("renew", from, Set.of("ACTIVE", "FROZEN"), from);
    }

    /** PIN set / unblock is allowed on cards that can still be used. */
    public static void pinAllowed(String from) {
        require("set the PIN of", from, Set.of("CREATED", "ACTIVE", "FROZEN"), from);
    }

    public static void orderPhysicalAllowed(String from) {
        require("order a physical card for", from, Set.of("ACTIVE", "FROZEN"), from);
    }

    /** Initial status of a newly issued card. */
    public static String initialStatus(String formFactor) {
        return "VIRTUAL".equals(formFactor) ? "ACTIVE" : "CREATED";
    }

    private static String require(String action, String from, Set<String> allowed, String to) {
        if (!allowed.contains(from)) {
            throw invalid(action, from);
        }
        return to;
    }

    private static ApiException invalid(String action, String from) {
        return ApiException.conflict("invalid_transition", "Cannot " + action + " a card in status " + from);
    }
}
