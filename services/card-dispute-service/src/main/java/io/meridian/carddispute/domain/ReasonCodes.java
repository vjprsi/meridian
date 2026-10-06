package io.meridian.carddispute.domain;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Scheme reason codes. Mastercard uses its four-digit 48xx codes; Visa and the other schemes (which this issuer
 * submits through Visa-style dispute categories) use Visa Claims Resolution codes (10.x fraud, 11.x authorisation,
 * 12.x processing errors, 13.x consumer disputes).
 */
public final class ReasonCodes {
    private ReasonCodes() {}

    private static final Map<String, String> VISA = Map.of(
            "FRAUD", "10.4", "AUTHORIZATION", "11.3", "PROCESSING_ERROR", "12.5", "DUPLICATE", "12.6.1",
            "NOT_RECEIVED", "13.1", "NOT_AS_DESCRIBED", "13.3", "CANCELLED_RECURRING", "13.2",
            "CREDIT_NOT_PROCESSED", "13.6", "CONSUMER_DISPUTE", "13.7", "ATM_DISPUTE", "13.9");

    private static final Map<String, String> MASTERCARD = Map.of(
            "FRAUD", "4837", "AUTHORIZATION", "4808", "PROCESSING_ERROR", "4834", "DUPLICATE", "4834",
            "NOT_RECEIVED", "4855", "NOT_AS_DESCRIBED", "4853", "CANCELLED_RECURRING", "4841",
            "CREDIT_NOT_PROCESSED", "4860", "CONSUMER_DISPUTE", "4853", "ATM_DISPUTE", "4834");

    private static final Map<String, String> DESCRIPTIONS = Map.ofEntries(
            Map.entry("10.4", "Other fraud - card-absent environment"), Map.entry("11.3", "No authorization"),
            Map.entry("12.5", "Incorrect amount"), Map.entry("12.6.1", "Duplicate processing"),
            Map.entry("13.1", "Merchandise / services not received"), Map.entry("13.2", "Cancelled recurring transaction"),
            Map.entry("13.3", "Not as described or defective merchandise / services"),
            Map.entry("13.6", "Credit not processed"), Map.entry("13.7", "Cancelled merchandise / services"),
            Map.entry("13.9", "Non-receipt of cash"), Map.entry("4837", "No cardholder authorization"),
            Map.entry("4808", "Authorization-related chargeback"), Map.entry("4834", "Point-of-interaction error"),
            Map.entry("4855", "Goods or services not provided"), Map.entry("4853", "Cardholder dispute"),
            Map.entry("4841", "Cancelled recurring or digital goods"), Map.entry("4860", "Credit not processed"));

    private static final Pattern VISA_FORMAT = Pattern.compile("1[0-3]\\.\\d{1,2}(\\.\\d)?");
    private static final Pattern MASTERCARD_FORMAT = Pattern.compile("48\\d{2}");

    public static String suggest(String category, String scheme) {
        return ("MASTERCARD".equals(scheme) ? MASTERCARD : VISA).get(category);
    }

    public static boolean fits(String scheme, String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        return ("MASTERCARD".equals(scheme) ? MASTERCARD_FORMAT : VISA_FORMAT).matcher(code).matches();
    }

    public static String describe(String code) {
        return code == null ? null : DESCRIPTIONS.getOrDefault(code, "Scheme reason code " + code);
    }
}
