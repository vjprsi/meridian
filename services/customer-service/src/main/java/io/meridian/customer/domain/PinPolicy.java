package io.meridian.customer.domain;

import io.meridian.common.error.ApiException;
import java.time.LocalDate;

/** PIN strength rules: 4-12 digits, no repeated or sequential digits, not derived from the date of birth. */
public final class PinPolicy {
    private PinPolicy() {}

    public static final int MAX_FAILED_ATTEMPTS = 3;

    public static void requireStrong(String pin, LocalDate dateOfBirth) {
        if (pin == null || !pin.matches("\\d{4,12}")) {
            throw ApiException.unprocessable("weak_pin", "PIN must be 4 to 12 digits");
        }
        if (pin.chars().distinct().count() == 1) {
            throw ApiException.unprocessable("weak_pin", "PIN must not repeat a single digit");
        }
        if (sequential(pin, 1) || sequential(pin, -1)) {
            throw ApiException.unprocessable("weak_pin", "PIN must not be an ascending or descending sequence");
        }
        if (dateOfBirth != null) {
            String dd = "%02d".formatted(dateOfBirth.getDayOfMonth());
            String mm = "%02d".formatted(dateOfBirth.getMonthValue());
            String yyyy = String.valueOf(dateOfBirth.getYear());
            for (String derived : new String[] {dd + mm, mm + dd, yyyy, dd + mm + yyyy.substring(2), yyyy.substring(2) + mm + dd}) {
                if (pin.equals(derived)) {
                    throw ApiException.unprocessable("weak_pin", "PIN must not be derived from the date of birth");
                }
            }
        }
    }

    private static boolean sequential(String pin, int step) {
        for (int i = 1; i < pin.length(); i++) {
            if (pin.charAt(i) - pin.charAt(i - 1) != step) {
                return false;
            }
        }
        return true;
    }
}
