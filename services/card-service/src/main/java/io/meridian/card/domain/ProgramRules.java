package io.meridian.card.domain;

import io.meridian.common.error.ApiException;
import java.math.BigInteger;
import java.util.Map;
import java.util.Set;

/** Card program rules: BIN / account range shape, funding model vs linked account type, program state machine. */
public final class ProgramRules {
    private ProgramRules() {}

    /** Linked account types each funding model may use. */
    public static final Map<String, Set<String>> FUNDING_ACCOUNTS = Map.of(
            "DEBIT", Set.of("DEPOSIT_ACCOUNT", "WALLET"),
            "PREPAID", Set.of("PREPAID_LEDGER", "WALLET"),
            "CREDIT", Set.of("CREDIT_LINE"),
            "JIT", Set.of("JIT_PROGRAM"));

    public static void validateBin(String bin, String start, String end, Integer panLength) {
        if (bin == null || !bin.matches("\\d{6}|\\d{8}")) {
            throw ApiException.unprocessable("invalid_bin", "bin must be 6 or 8 digits");
        }
        validateRangeShape(start, end, panLength == null ? 16 : panLength);
        if (!start.startsWith(bin) || !end.startsWith(bin)) {
            throw ApiException.unprocessable("invalid_bin_range", "bin_range_start and bin_range_end must start with the BIN");
        }
    }

    public static void validateRangeShape(String start, String end, int panLength) {
        if (panLength < 13 || panLength > 19) {
            throw ApiException.unprocessable("invalid_pan_length", "pan_length must be between 13 and 19");
        }
        if (start == null || end == null || !start.matches("\\d+") || !end.matches("\\d+")) {
            throw ApiException.unprocessable("invalid_bin_range", "bin_range_start and bin_range_end must be digits");
        }
        if (start.length() != end.length()) {
            throw ApiException.unprocessable("invalid_bin_range", "bin_range_start and bin_range_end must have the same length");
        }
        if (start.length() >= panLength) {
            throw ApiException.unprocessable("invalid_bin_range", "the account range prefix must be shorter than pan_length");
        }
        if (new BigInteger(start).compareTo(new BigInteger(end)) > 0) {
            throw ApiException.unprocessable("invalid_bin_range", "bin_range_start must not exceed bin_range_end");
        }
    }

    public static void validateFunding(String fundingModel, String linkedAccountType) {
        Set<String> allowed = FUNDING_ACCOUNTS.getOrDefault(fundingModel, Set.of());
        if (!allowed.contains(linkedAccountType)) {
            throw ApiException.unprocessable("linked_account_type_mismatch",
                    "A " + fundingModel + " program requires linked_account_type in " + allowed);
        }
    }

    /** Next status of a program action, or 409 invalid_transition. */
    public static String transition(String action, String from) {
        String to = switch (action) {
            case "certify" -> "DRAFT".equals(from) ? "CERTIFICATION" : null;
            case "activate" -> "CERTIFICATION".equals(from) || "SUSPENDED".equals(from) ? "ACTIVE" : null;
            case "suspend" -> "ACTIVE".equals(from) ? "SUSPENDED" : null;
            default -> throw new IllegalArgumentException(action);
        };
        if (to == null) {
            throw ApiException.conflict("invalid_transition", "Cannot " + action + " a program in status " + from);
        }
        return to;
    }

    /** Next status of a product action, or 409 invalid_transition. */
    public static String productTransition(String action, String from) {
        String to = switch (action) {
            case "activate" -> "DRAFT".equals(from) ? "ACTIVE" : null;
            case "retire" -> "DRAFT".equals(from) || "ACTIVE".equals(from) ? "RETIRED" : null;
            default -> throw new IllegalArgumentException(action);
        };
        if (to == null) {
            throw ApiException.conflict("invalid_transition", "Cannot " + action + " a product in status " + from);
        }
        return to;
    }
}
