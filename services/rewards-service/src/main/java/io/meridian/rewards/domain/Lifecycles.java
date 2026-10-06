package io.meridian.rewards.domain;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Set;

/** State machines of the rewards entities and small pure helpers. */
public final class Lifecycles {
    private Lifecycles() {}

    public static final StateMachine PROGRAM = new StateMachine("rewards program")
            .on("activate", Set.of("DRAFT", "PAUSED"), "ACTIVE")
            .on("pause", "ACTIVE", "PAUSED")
            .on("end", Set.of("DRAFT", "ACTIVE", "PAUSED"), "ENDED");

    public static final StateMachine RULE = new StateMachine("reward rule")
            .on("activate", Set.of("DRAFT", "INACTIVE"), "ACTIVE")
            .on("deactivate", Set.of("DRAFT", "ACTIVE"), "INACTIVE");

    public static final StateMachine ACCOUNT = new StateMachine("points account")
            .on("suspend", "ACTIVE", "SUSPENDED")
            .on("reactivate", "SUSPENDED", "ACTIVE")
            .on("close", Set.of("ACTIVE", "SUSPENDED"), "CLOSED");

    public static final StateMachine ADJUSTMENT = new StateMachine("points adjustment")
            .on("approve", "PENDING_APPROVAL", "APPLIED")
            .on("reject", "PENDING_APPROVAL", "REJECTED");

    public static final StateMachine CASHBACK = new StateMachine("cashback")
            .on("confirm", "PENDING", "CONFIRMED")
            .on("pay", "CONFIRMED", "PAID")
            .on("reverse", Set.of("PENDING", "CONFIRMED", "PAID"), "REVERSED");

    public static final StateMachine REDEMPTION = new StateMachine("redemption")
            .on("cancel", Set.of("REQUESTED", "PROCESSING", "FAILED"), "CANCELLED")
            .on("retry", "FAILED", "PROCESSING")
            .on("fulfil", Set.of("REQUESTED", "PROCESSING"), "FULFILLED")
            .on("fail", Set.of("REQUESTED", "PROCESSING"), "FAILED");

    public static final StateMachine REFERRAL_PROGRAM = new StateMachine("referral program")
            .on("activate", Set.of("DRAFT", "PAUSED"), "ACTIVE")
            .on("pause", "ACTIVE", "PAUSED")
            .on("end", Set.of("DRAFT", "ACTIVE", "PAUSED"), "ENDED");

    public static final StateMachine REFERRAL = new StateMachine("referral")
            .on("sign-up", "INVITED", "SIGNED_UP")
            .on("progress", Set.of("SIGNED_UP", "QUALIFYING"), "QUALIFYING")
            .on("qualify", Set.of("SIGNED_UP", "QUALIFYING"), "QUALIFIED")
            .on("pay", "QUALIFIED", "REWARDED")
            .on("reject", Set.of("INVITED", "SIGNED_UP", "QUALIFYING", "QUALIFIED"), "REJECTED")
            .on("expire", Set.of("INVITED", "SIGNED_UP", "QUALIFYING"), "EXPIRED");

    private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Readable 8-character referral / voucher code (no 0/O/1/I). */
    public static String code(int length) {
        StringBuilder b = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            b.append(CODE_ALPHABET[RANDOM.nextInt(CODE_ALPHABET.length)]);
        }
        return b.toString();
    }

    public static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
