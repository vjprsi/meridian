package io.meridian.deposit.domain;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.random.RandomGenerator;

/**
 * How one legal entity numbers its deposit accounts: the country's scheme (IBAN where the country uses IBAN, else
 * the national account number format), the national bank and branch codes and the entity's BIC. Pure: resolved from
 * the legal entity, its numbering settings and reference data by the application layer, randomness is injected.
 *
 * <p>IBAN scheme: account number = {@code accountLength} digits ending in a Luhn check digit; BBAN = bank code +
 * branch code + account number in the country's layout ({@link AccountNumbers#IBAN_COUNTRIES}, or bank + branch +
 * account filling the registered IBAN length for other IBAN countries); IBAN = country + ISO 7064 mod 97-10 check
 * digits + BBAN.
 *
 * <p>National scheme: the account number is rendered from a pattern of the tokens {@code {bank}}, {@code {branch}},
 * {@code {serial}} (random digits, never starting with 0) and {@code {check}}; the check digit(s) are computed over
 * bank code + branch code + serial with {@link CheckDigit}. Known countries ({@link #NATIONAL}) fix code lengths, the
 * pattern and the algorithm; elsewhere the generic default is {@code {serial}{check}} (Luhn), overridable per entity.
 */
public record NumberingRule(String country, Scheme scheme, String bankCode, String branchCode, String bic,
                            String pattern, int serialLength, CheckDigit checkDigit, int accountLength, Integer ibanLength) {

    public enum Scheme { IBAN, NATIONAL }

    /** National check digit algorithms over bank code + branch code + serial. */
    public enum CheckDigit {
        /** One Luhn (mod 10) digit. */
        LUHN,
        /** Two ISO 7064 MOD 97-10 digits (letters count A=10 .. Z=35). */
        MOD97,
        /** One NUBAN digit (weights 3, 7, 3 repeating; CBN Nigeria). */
        NUBAN,
        /** No check digit. */
        NONE;

        int length() {
            return switch (this) {
                case LUHN, NUBAN -> 1;
                case MOD97 -> 2;
                case NONE -> 0;
            };
        }
    }

    /** National format of a country: bank / branch code lengths (digits), pattern, serial length and check digit. */
    public record NationalFormat(int bankLength, int branchLength, String pattern, int serialLength, CheckDigit check) {}

    /**
     * Built-in national formats. KE: 2-digit CBK bank code, 3-digit branch code; account number = branch (3) +
     * serial (9) + Luhn digit over bank + branch + serial (13 digits); routing code = bank + branch (the 5-digit
     * bank-branch code of KEPSS / PesaLink). NG: NUBAN, 3-digit bank code, serial (9) + NUBAN digit (10 digits).
     */
    public static final Map<String, NationalFormat> NATIONAL = Map.of(
            "KE", new NationalFormat(2, 3, "{branch}{serial}{check}", 9, CheckDigit.LUHN),
            "NG", new NationalFormat(3, 0, "{serial}{check}", 9, CheckDigit.NUBAN),
            // CO: no IBAN; 3-digit entity code (ACH Colombia / Superfinanciera participant code) and 3-digit office
            // code; account number = office (3) + serial (7) + Luhn over entity + office + serial = 11 digits.
            "CO", new NationalFormat(3, 3, "{branch}{serial}{check}", 7, CheckDigit.LUHN));

    public static final String GENERIC_PATTERN = "{serial}{check}";
    private static final Pattern TOKEN = Pattern.compile("\\{(bank|branch|serial|check)}|[A-Z0-9]");
    private static final Pattern BIC = Pattern.compile("[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?");

    /** Generated identifiers of one account. */
    public record Numbers(String accountNumber, String iban, String bban, String routingCode, String bic) {}

    /** A rule that cannot be built: {@code code} is account_numbering_not_configured or account_numbering_invalid. */
    public static final class Invalid extends RuntimeException {
        private final String code;

        public Invalid(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /**
     * Resolved settings (any member may be null).
     *
     * @param scheme              IBAN, NATIONAL or AUTO/null (IBAN where the country has an IBAN length)
     * @param accountNumberLength national: total length of a generic number (default 10) when serial_length is not set
     * @param ibanLength          the country's IBAN length (reference-data or settings), else the built-in table
     */
    public record Input(String country, String scheme, String bankCode, String branchCode, String bic, String pattern,
                        Integer serialLength, String checkDigit, Integer accountNumberLength, Integer ibanLength) {}

    public static NumberingRule resolve(Input in) {
        String country = upper(in.country());
        if (country == null || !country.matches("[A-Z]{2}")) {
            throw invalid("country must be an ISO 3166 alpha-2 code, got " + in.country());
        }
        Integer ibanLength = in.ibanLength() != null && in.ibanLength() > 0 ? in.ibanLength()
                : AccountNumbers.IBAN_LENGTHS.get(country);
        String s = upper(in.scheme());
        Scheme scheme;
        if (s == null || "AUTO".equals(s)) {
            scheme = ibanLength != null ? Scheme.IBAN : Scheme.NATIONAL;
        } else {
            try {
                scheme = Scheme.valueOf(s);
            } catch (IllegalArgumentException e) {
                throw invalid("scheme must be IBAN, NATIONAL or AUTO, got " + in.scheme());
            }
        }
        String bic = upper(in.bic());
        if (bic != null) {
            if (!BIC.matcher(bic).matches()) {
                throw invalid("bic " + bic + " is not a valid BIC (ISO 9362)");
            }
            if (!bic.substring(4, 6).equals(country)) {
                throw invalid("bic " + bic + " belongs to country " + bic.substring(4, 6) + ", the legal entity numbers in " + country);
            }
            bic = bic.length() == 8 ? bic + "XXX" : bic;
        }
        return scheme == Scheme.IBAN ? iban(in, country, ibanLength, bic) : national(in, country, bic);
    }

    private static NumberingRule iban(Input in, String country, Integer ibanLength, String bic) {
        if (ibanLength == null) {
            throw invalid("country " + country + " has no known IBAN length; set iban_length or use scheme NATIONAL");
        }
        if (ibanLength < 15 || ibanLength > 34) {
            throw invalid("iban_length must be 15..34");
        }
        AccountNumbers.BbanFormat layout = AccountNumbers.IBAN_COUNTRIES.get(country);
        if (layout != null && layout.ibanLength() != ibanLength) {
            layout = null; // the registry length changed: fall back to the generic layout
        }
        String bank = upper(in.bankCode());
        if (bank == null && bic != null && (layout == null || layout.bankAlpha())) {
            bank = bic.substring(0, 4); // e.g. GB, IE, NL: the bank code of the BBAN is the BIC's institution code
        }
        if (bank == null) {
            throw notConfigured("bank_code is required for " + country + " IBANs (or a BIC to derive it from)");
        }
        String branch = upper(in.branchCode());
        int accountLength;
        if (layout != null) {
            if (layout.bankAlpha()) {
                if (!bank.matches("[A-Z]{" + layout.bankLength() + "}")) {
                    throw invalid("bank_code for " + country + " must be " + layout.bankLength() + " letters, got " + bank);
                }
            } else {
                bank = padDigits("bank_code", bank, layout.bankLength(), country);
            }
            if (layout.branchLength() > 0) {
                if (branch == null) {
                    throw notConfigured("branch_code is required for " + country + " IBANs");
                }
                branch = padDigits("branch_code", branch, layout.branchLength(), country);
            } else {
                branch = "";
            }
            accountLength = layout.accountLength();
        } else {
            branch = branch == null ? "" : branch;
            if (!bank.matches("[A-Z0-9]+") || !branch.matches("[A-Z0-9]*")) {
                throw invalid("bank_code and branch_code must be letters and digits");
            }
            accountLength = ibanLength - 4 - bank.length() - branch.length();
            if (accountLength < 4) {
                throw invalid("bank_code + branch_code leave " + accountLength + " characters for the account in a "
                        + ibanLength + "-character " + country + " IBAN");
            }
        }
        return new NumberingRule(country, Scheme.IBAN, bank, branch, bic, null, accountLength - 1, CheckDigit.LUHN,
                accountLength, ibanLength);
    }

    private static NumberingRule national(Input in, String country, String bic) {
        NationalFormat nf = NATIONAL.get(country);
        String bank = upper(in.bankCode());
        String branch = upper(in.branchCode());
        if (nf != null) {
            if (nf.bankLength() > 0) {
                if (bank == null) {
                    throw notConfigured("bank_code (" + nf.bankLength() + " digits) is required for " + country + " account numbers");
                }
                bank = padDigits("bank_code", bank, nf.bankLength(), country);
            }
            if (nf.branchLength() > 0) {
                if (branch == null) {
                    throw notConfigured("branch_code (" + nf.branchLength() + " digits) is required for " + country + " account numbers");
                }
                branch = padDigits("branch_code", branch, nf.branchLength(), country);
            }
        }
        bank = bank == null ? "" : bank;
        branch = branch == null ? "" : branch;
        if (!bank.matches("[A-Z0-9]*") || !branch.matches("[A-Z0-9]*")) {
            throw invalid("bank_code and branch_code must be letters and digits");
        }
        String pattern = upper(in.pattern()) != null ? in.pattern().trim() : nf != null ? nf.pattern() : GENERIC_PATTERN;
        CheckDigit check;
        try {
            check = in.checkDigit() != null && !in.checkDigit().isBlank()
                    ? CheckDigit.valueOf(in.checkDigit().trim().toUpperCase(Locale.ROOT))
                    : nf != null ? nf.check() : CheckDigit.LUHN;
        } catch (IllegalArgumentException e) {
            throw invalid("check_digit must be one of LUHN, MOD97, NUBAN, NONE, got " + in.checkDigit());
        }
        if (check == CheckDigit.NUBAN && !(bank + branch).chars().allMatch(Character::isDigit)) {
            throw invalid("NUBAN check digits need numeric bank and branch codes");
        }
        int serials = 0;
        int checks = 0;
        int fixed = 0;
        int at = 0;
        Matcher m = TOKEN.matcher(pattern);
        while (at < pattern.length()) {
            if (!m.find(at) || m.start() != at) {
                throw invalid("pattern may contain only {bank}, {branch}, {serial}, {check} and letters/digits: " + pattern);
            }
            String token = m.group(1);
            if (token == null) {
                fixed++;
            } else {
                switch (token) {
                    case "bank" -> fixed += bank.length();
                    case "branch" -> fixed += branch.length();
                    case "serial" -> serials++;
                    default -> checks++;
                }
            }
            at = m.end();
        }
        if (serials != 1) {
            throw invalid("pattern must contain {serial} exactly once: " + pattern);
        }
        if (checks > 1 || (checks == 0) != (check == CheckDigit.NONE)) {
            throw invalid("pattern must contain {check} once unless check_digit is NONE: " + pattern);
        }
        int serialLength;
        if (in.serialLength() != null) {
            serialLength = in.serialLength();
        } else if (nf != null && in.accountNumberLength() == null) {
            serialLength = nf.serialLength();
        } else {
            int total = in.accountNumberLength() == null ? 10 : in.accountNumberLength();
            serialLength = total - fixed - check.length();
        }
        if (serialLength < 1 || serialLength > 20) {
            throw invalid("serial length must be 1..20, got " + serialLength);
        }
        int length = fixed + serialLength + check.length();
        if (length > 34) {
            throw invalid("account numbers longer than 34 characters are not supported (" + length + ")");
        }
        return new NumberingRule(country, Scheme.NATIONAL, bank, branch, bic, pattern, serialLength, check, length, null);
    }

    /** A fresh set of identifiers (uniqueness is the caller's job). */
    public Numbers generate(RandomGenerator random) {
        if (scheme == Scheme.IBAN) {
            String account = AccountNumbers.generate(accountLength, random);
            String bban = bankCode + branchCode + account;
            String iban = country + AccountNumbers.checkDigits(country, bban) + bban;
            return new Numbers(account, iban, bban, branchCode.isEmpty() ? bankCode : branchCode, bic);
        }
        StringBuilder serial = new StringBuilder(serialLength);
        serial.append(1 + random.nextInt(9));
        for (int i = 1; i < serialLength; i++) {
            serial.append(random.nextInt(10));
        }
        String number = render(serial.toString(), check(serial.toString()));
        String bban = pattern.contains("{bank}") ? number : bankCode + number;
        String routing = (bankCode + branchCode).isEmpty() ? null : bankCode + branchCode;
        return new Numbers(number, null, bban, routing, bic);
    }

    /** Whether {@code accountNumber} is a well-formed number of this rule with valid check digit(s). */
    public boolean isValid(String accountNumber) {
        if (accountNumber == null) {
            return false;
        }
        if (scheme == Scheme.IBAN) {
            return accountNumber.length() == accountLength && AccountNumbers.isValidLuhn(accountNumber);
        }
        StringBuilder regex = new StringBuilder();
        Matcher m = TOKEN.matcher(pattern);
        while (m.find()) {
            String token = m.group(1);
            if (token == null) {
                regex.append(Pattern.quote(m.group()));
            } else {
                regex.append(switch (token) {
                    case "bank" -> Pattern.quote(bankCode);
                    case "branch" -> Pattern.quote(branchCode);
                    case "serial" -> "(?<serial>[0-9]{" + serialLength + "})";
                    default -> "(?<check>[0-9]{" + checkDigit.length() + "})";
                });
            }
        }
        Matcher n = Pattern.compile(regex.toString()).matcher(accountNumber);
        if (!n.matches()) {
            return false;
        }
        return checkDigit == CheckDigit.NONE || check(n.group("serial")).equals(n.group("check"));
    }

    private String check(String serial) {
        String base = bankCode + branchCode + serial;
        return switch (checkDigit) {
            case LUHN -> Integer.toString(AccountNumbers.luhnCheckDigit(AccountNumbers.numeric(base)));
            case MOD97 -> AccountNumbers.checkMod97(base);
            case NUBAN -> Integer.toString(AccountNumbers.nubanCheckDigit(base));
            case NONE -> "";
        };
    }

    private String render(String serial, String check) {
        StringBuilder out = new StringBuilder();
        Matcher m = TOKEN.matcher(pattern);
        while (m.find()) {
            String token = m.group(1);
            out.append(token == null ? m.group() : switch (token) {
                case "bank" -> bankCode;
                case "branch" -> branchCode;
                case "serial" -> serial;
                default -> check;
            });
        }
        return out.toString();
    }

    private static String padDigits(String field, String value, int length, String country) {
        if (!value.matches("[0-9]{1," + length + "}")) {
            throw invalid(field + " for " + country + " must be up to " + length + " digits, got " + value);
        }
        return "0".repeat(length - value.length()) + value;
    }

    private static String upper(String s) {
        return s == null || s.isBlank() ? null : s.trim().toUpperCase(Locale.ROOT);
    }

    private static Invalid invalid(String message) {
        return new Invalid("account_numbering_invalid", message);
    }

    private static Invalid notConfigured(String message) {
        return new Invalid("account_numbering_not_configured", message);
    }
}
