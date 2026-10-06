package io.meridian.openbanking.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Consent rules per type: which TPP role may request it, which permissions are valid, how the permission set
 * must be combined (UK OBIE / Berlin Group semantics), and state transitions of consents and SCA sessions.
 */
public final class ConsentRules {
    public static final Set<String> AIS_PERMISSIONS = Set.of(
            "ReadAccountsBasic", "ReadAccountsDetail", "ReadBalances", "ReadBeneficiariesBasic",
            "ReadBeneficiariesDetail", "ReadDirectDebits", "ReadOffers", "ReadPAN", "ReadParty", "ReadPartyPSU",
            "ReadProducts", "ReadScheduledPaymentsBasic", "ReadScheduledPaymentsDetail", "ReadStandingOrdersBasic",
            "ReadStandingOrdersDetail", "ReadStatementsBasic", "ReadStatementsDetail", "ReadTransactionsBasic",
            "ReadTransactionsCredits", "ReadTransactionsDebits", "ReadTransactionsDetail");
    public static final Set<String> PIS_PERMISSIONS = Set.of(
            "CreatePayment", "CreateDomesticPayment", "CreateDomesticScheduledPayment", "CreateInternationalPayment");
    public static final Set<String> VRP_PERMISSIONS = Set.of("CreateVRP", "CreatePayment");
    public static final Set<String> CBPII_PERMISSIONS = Set.of("ReadFundsConfirmation", "FundsConfirmation");
    public static final Set<String> SCA_APPROACHES = Set.of("REDIRECT", "DECOUPLED", "EMBEDDED");
    public static final Set<String> REVOKERS = Set.of("CUSTOMER", "TPP", "ASPSP");
    public static final Set<String> TPP_ROLES = Set.of("AISP", "PISP", "CBPII");

    /** Consent type → TPP role required to request it. */
    public static final Map<String, String> REQUIRED_ROLE = Map.of(
            "AIS", "AISP", "FDX_DATA", "AISP", "PIS", "PISP", "VRP", "PISP", "CBPII", "CBPII");

    private ConsentRules() {}

    /** Violations of the permission rules for a consent type (empty when valid). */
    public static List<String> permissionErrors(String consentType, List<String> permissions) {
        List<String> errors = new ArrayList<>();
        Set<String> allowed = switch (consentType) {
            case "AIS" -> AIS_PERMISSIONS;
            case "PIS" -> PIS_PERMISSIONS;
            case "VRP" -> VRP_PERMISSIONS;
            case "CBPII" -> CBPII_PERMISSIONS;
            default -> null;
        };
        if (allowed == null) {
            if (permissions.isEmpty()) {
                errors.add("at least one permission is required");
            }
            return errors;
        }
        for (String p : permissions) {
            if (!allowed.contains(p)) {
                errors.add("permission " + p + " is not valid for " + consentType + " consents");
            }
        }
        if ("AIS".equals(consentType)) {
            if (permissions.isEmpty()) {
                errors.add("AIS consents need at least one permission");
            }
            boolean txBase = permissions.contains("ReadTransactionsBasic") || permissions.contains("ReadTransactionsDetail");
            boolean txDir = permissions.contains("ReadTransactionsCredits") || permissions.contains("ReadTransactionsDebits");
            if (txBase && !txDir) {
                errors.add("ReadTransactionsBasic/Detail must be combined with ReadTransactionsCredits and/or ReadTransactionsDebits");
            }
            if (txDir && !txBase) {
                errors.add("ReadTransactionsCredits/Debits require ReadTransactionsBasic or ReadTransactionsDetail");
            }
        }
        return errors;
    }

    /** Required fields of a PIS consent's initiation block (empty when complete). */
    public static List<String> initiationErrors(Map<String, Object> initiation) {
        List<String> errors = new ArrayList<>();
        if (initiation == null) {
            errors.add("initiation is required for PIS consents");
            return errors;
        }
        for (String f : List.of("instructed_amount", "currency", "creditor_name", "creditor_account")) {
            Object v = initiation.get(f);
            if (v == null || v.toString().isBlank()) {
                errors.add("initiation." + f + " is required");
            }
        }
        Object amount = initiation.get("instructed_amount");
        if (amount != null) {
            try {
                if (new BigDecimal(amount.toString()).signum() <= 0) {
                    errors.add("initiation.instructed_amount must be positive");
                }
            } catch (NumberFormatException e) {
                errors.add("initiation.instructed_amount must be a decimal");
            }
        }
        Object ccy = initiation.get("currency");
        if (ccy != null && !ccy.toString().matches("[A-Z]{3}")) {
            errors.add("initiation.currency must be an ISO 4217 code");
        }
        return errors;
    }

    /** Normalised account identifier for comparison (spaces removed, upper case). */
    public static String normaliseAccount(String account) {
        return account == null ? null : account.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    /** Whether a payment matches the payment details the customer authorised. */
    public static List<String> initiationMismatches(Map<String, Object> initiation, BigDecimal amount, String currency,
                                                    String creditorName, String creditorAccount) {
        List<String> m = new ArrayList<>();
        if (initiation == null) {
            return m;
        }
        Object a = initiation.get("instructed_amount");
        if (a != null && new BigDecimal(a.toString()).compareTo(amount) != 0) {
            m.add("amount");
        }
        Object c = initiation.get("currency");
        if (c != null && !c.toString().equals(currency)) {
            m.add("currency");
        }
        Object n = initiation.get("creditor_name");
        if (n != null && !n.toString().trim().equalsIgnoreCase(creditorName == null ? "" : creditorName.trim())) {
            m.add("creditor_name");
        }
        Object acc = initiation.get("creditor_account");
        if (acc != null && !normaliseAccount(acc.toString()).equals(normaliseAccount(creditorAccount))) {
            m.add("creditor_account");
        }
        return m;
    }

    /** Core payment status → PISP-facing initiation status. */
    public static String initiationStatus(String paymentStatus) {
        return switch (paymentStatus == null ? "" : paymentStatus) {
            case "SUBMITTED", "ACCEPTED" -> "ACCEPTED";
            case "SETTLED" -> "EXECUTED";
            case "REJECTED", "FAILED", "RETURNED" -> "REJECTED";
            case "CANCELLED" -> "CANCELLED";
            default -> "PENDING";
        };
    }

    /** True when the account identifier is a structurally valid IBAN (ISO 13616 mod-97). */
    public static boolean isIban(String raw) {
        String iban = normaliseAccount(raw);
        if (iban == null || !iban.matches("[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}")) {
            return false;
        }
        String r = iban.substring(4) + iban.substring(0, 4);
        int mod = 0;
        for (char ch : r.toCharArray()) {
            int v = Character.isDigit(ch) ? ch - '0' : ch - 'A' + 10;
            mod = v > 9 ? (mod * 100 + v) % 97 : (mod * 10 + v) % 97;
        }
        return mod == 1;
    }
}
