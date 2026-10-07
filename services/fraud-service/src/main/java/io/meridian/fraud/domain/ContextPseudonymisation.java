package io.meridian.fraud.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Replaces personal identifiers in an evaluation's {@code context} by tenant-keyed pseudonyms before rules, features
 * and storage see it. Identifiers are recognised by field name (account numbers, IBANs, card numbers / fragments,
 * phone, e-mail, device ids, national ids) and, for any other string, by shape (e-mail address, IBAN, international
 * phone number, 8 or more digits); ids (UUIDs), codes, dates and numbers stay as they are. {@code <name>_digest} fields
 * carry a canonical digest computed by the caller ({@link ListValues#hash}); they are stored as {@code <name>} with the
 * pseudonym of the digest, which equals the pseudonym of the clear value.
 */
public final class ContextPseudonymisation {
    private ContextPseudonymisation() {}

    /** Field name -> list entity type whose canonical form the pseudonym uses. */
    static final Map<String, String> IDENTIFIER_FIELDS = fields();
    /** Codes that look like digits but are not personal (BIN, MCC, purpose / clearing codes). */
    static final Set<String> SAFE_FIELDS = Set.of("card_bin", "bin", "merchant_category_code", "mcc", "purpose_code",
            "category_purpose", "beneficiary_bic", "bic", "creditor_agent_clearing_id", "bank_code", "sort_code",
            "currency", "instructed_currency", "country", "merchant_country", "payment_type", "direction",
            "payment_channel", "channel", "txn_type", "initiator_type", "requested_execution_date", "user_agent");

    private static final Pattern UUID_RE = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern EMAIL_RE = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern IBAN_RE = Pattern.compile("^[A-Z]{2}[0-9]{2}[A-Z0-9]{10,30}$");
    private static final Pattern PHONE_RE = Pattern.compile("^\\+[0-9][0-9 ().-]{6,20}$");
    private static final Pattern DIGITS_RE = Pattern.compile("^[0-9][0-9 -]*[0-9]$");
    private static final Pattern DATE_RE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}([T ].*)?$");

    private static Map<String, String> fields() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String f : List.of("beneficiary_account", "account_number", "iban", "creditor_account_number",
                "debtor_account_number", "beneficiary_iban", "payee_account", "payee_account_number",
                "destination_account", "bank_account", "counterparty_account")) {
            m.put(f, "BENEFICIARY_ACCOUNT");
        }
        for (String f : List.of("card_number", "pan", "card_pan", "pan_last4", "card_last4", "last4", "masked_pan",
                "pan_fragment", "pan_suffix")) {
            m.put(f, "CARD");
        }
        for (String f : List.of("email", "payee_email", "customer_email", "email_address")) {
            m.put(f, "EMAIL");
        }
        for (String f : List.of("phone", "msisdn", "payee_msisdn", "payer_msisdn", "mobile", "phone_number",
                "mobile_number")) {
            m.put(f, "PHONE");
        }
        for (String f : List.of("device_id", "device_fingerprint", "device_identifier", "imei", "android_id", "idfa",
                "idfv")) {
            m.put(f, "DEVICE");
        }
        for (String f : List.of("national_id", "ssn", "tax_id", "passport_number", "id_number")) {
            m.put(f, "NATIONAL_ID");
        }
        return Map.copyOf(m);
    }

    /** A pseudonymised copy of the context (null or non-object = empty object). */
    public static ObjectNode sanitise(JsonNode context, Pseudonyms p) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        if (context == null || !context.isObject()) {
            return out;
        }
        for (Map.Entry<String, JsonNode> e : context.properties()) {
            String name = e.getKey();
            String lower = name.toLowerCase(Locale.ROOT);
            JsonNode v = e.getValue();
            if (lower.endsWith("_digest") && v != null && v.isString() && !Pseudonyms.isValuePseudonym(v.asString())) {
                String base = name.substring(0, name.length() - "_digest".length());
                out.put(context.has(base) ? name : base,Pseudonyms.isDigest(v.asString()) ? p.valueOfDigest(v.asString())
                        : p.value("BENEFICIARY_ACCOUNT", v.asString()));
                continue;
            }
            out.set(name, sanitiseValue(lower, v, p));
        }
        return out;
    }

    private static JsonNode sanitiseValue(String field, JsonNode v, Pseudonyms p) {
        if (v == null || v.isNull() || v.isNumber() || v.isBoolean()) {
            return v;
        }
        if (v.isObject()) {
            return sanitise(v, p);
        }
        if (v.isArray()) {
            ArrayNode a = JsonNodeFactory.instance.arrayNode();
            v.forEach(x -> a.add(sanitiseValue(field, x, p)));
            return a;
        }
        String s = v.asString();
        String type = classify(field, s);
        if (type != null) {
            return JsonNodeFactory.instance.stringNode(p.value(type, s));
        }
        String t = s.trim();
        if (SAFE_FIELDS.contains(field) || Pseudonyms.isValuePseudonym(t) || UUID_RE.matcher(t).matches()
                || DATE_RE.matcher(t).matches()) {
            return v;
        }
        String inline = inline(s, p);
        return inline.equals(s) ? v : JsonNodeFactory.instance.stringNode(inline);
    }

    /** Free text: e-mail addresses and runs of 12+ digits (card / account numbers) inside it become pseudonyms. */
    static String inline(String s, Pseudonyms p) {
        if (s.length() > 2000) {
            s = s.substring(0, 2000);
        }
        String out = EMBEDDED_EMAIL.matcher(s).replaceAll(m -> java.util.regex.Matcher.quoteReplacement(
                p.value("EMAIL", m.group())));
        return EMBEDDED_DIGITS.matcher(out).replaceAll(m -> m.group().replaceAll("\\D", "").length() >= 12
                ? java.util.regex.Matcher.quoteReplacement(p.value("BENEFICIARY_ACCOUNT", m.group()))
                : java.util.regex.Matcher.quoteReplacement(m.group()));
    }

    private static final Pattern EMBEDDED_EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern EMBEDDED_DIGITS = Pattern.compile("(?<![0-9A-Za-z-])[0-9][0-9 -]{10,}[0-9](?![0-9A-Za-z-])");

    /** The list entity type of an identifier (pseudonymised), or null for a value kept as it is. */
    static String classify(String field, String s) {
        if (s == null || s.isBlank() || Pseudonyms.isValuePseudonym(s)) {
            return null;
        }
        String type = IDENTIFIER_FIELDS.get(field);
        if (type != null) {
            return type;
        }
        String t = s.trim();
        if (SAFE_FIELDS.contains(field) || UUID_RE.matcher(t).matches() || DATE_RE.matcher(t).matches()) {
            return null;
        }
        if (EMAIL_RE.matcher(t).matches()) {
            return "EMAIL";
        }
        if (PHONE_RE.matcher(t).matches()) {
            return "PHONE";
        }
        String compact = t.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        if (IBAN_RE.matcher(compact).matches()) {
            return "BENEFICIARY_ACCOUNT";
        }
        if (DIGITS_RE.matcher(t).matches() && compact.length() >= 8) {
            return "BENEFICIARY_ACCOUNT"; // account numbers, card numbers
        }
        return null;
    }

    /** Clear values of the context, by list entity type, that this class would pseudonymise (for tests and audits). */
    public static List<String> identifiers(JsonNode context) {
        List<String> out = new ArrayList<>();
        if (context != null && context.isObject()) {
            for (Map.Entry<String, JsonNode> e : context.properties()) {
                JsonNode v = e.getValue();
                if (v != null && v.isString() && classify(e.getKey().toLowerCase(Locale.ROOT), v.asString()) != null) {
                    out.add(v.asString());
                } else if (v != null && v.isObject()) {
                    out.addAll(identifiers(v));
                }
            }
        }
        return out;
    }
}
