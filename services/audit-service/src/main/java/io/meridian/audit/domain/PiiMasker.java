package io.meridian.audit.domain;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Masks personal data in the before / after states stored on audit events (GAP-07; spec: "PII masked").
 *
 * <p>What is masked:
 * <ul>
 *   <li>the columns the spec marks {@code pii} on the event's resource table (from the generated personal-data map,
 *       {@code PersonalDataCatalog.piiColumnsByTable}), at the top level of the payload, whatever their JSON type;</li>
 *   <li>at any depth, fields whose name says they hold personal data ({@link #isPersonalField}), e.g. nested
 *       {@code collected_data.email} or {@code risk_context.ip_address}, and fields of tables the map does not know.</li>
 * </ul>
 * Fields that are already safe ({@code *_masked}, {@code *_hash}, {@code *_digest}, {@code *_last4}) and references (ids) are kept.
 *
 * <p>A masked value is a tenant-keyed pseudonym, consistent with fraud-service's pseudonymisation:
 * {@code psn:} + 128 bits of HMAC-SHA-256 under {@code HMAC(secret, "meridian-audit-pseudonym/v1|" + tenant)} over the
 * normalised value (trimmed, lower-case). Investigators can still see that two events carry the same e-mail address
 * or account number, but not which; two tenants never share a pseudonym; without the secret
 * ({@code meridian.audit.pseudonym-key}) a pseudonym cannot be reversed by enumerating phone or account numbers.
 */
public final class PiiMasker {
    public static final String PREFIX = "psn:";

    /** Field names that always hold personal data. */
    static final Set<String> PERSONAL_FIELDS = Set.of(
            "email", "phone", "msisdn", "mobile", "first_name", "middle_name", "last_name", "full_name", "legal_name",
            "name_local_script", "date_of_birth", "dob", "place_of_birth", "national_id", "tax_id", "tin",
            "passport_number", "document_number", "identifier_value", "account_number", "iban", "pan", "card_number",
            "address", "address_line1", "address_line2", "address_line3", "line1", "line2", "line3", "postal_code",
            "ip_address", "device_fingerprint", "destination", "payee_name", "creditor_name", "debtor_name",
            "cardholder_name", "holder_name", "holder_address", "recipient_name", "contact_name", "contact_email",
            "contact_msisdn", "employer_name", "screened_name", "beneficiary_name", "counterparty_name", "account_name",
            "payee_msisdn", "target_msisdn", "creditor_account_number", "debtor_account_number", "creditor_address");
    /** Suffixes of field names that hold personal data. */
    static final String[] PERSONAL_SUFFIXES = {"_email", "_phone", "_msisdn", "_account_number", "_iban",
            "_date_of_birth", "_national_id", "_tax_id", "_address", "_full_name", "_first_name", "_last_name"};
    /** Suffixes of field names that are already safe (masked, hashed, references). */
    static final String[] SAFE_SUFFIXES = {"_masked", "_hash", "_digest", "_last4"};

    private final Map<String, Set<String>> piiByTable;
    private final byte[] secret;
    private final Map<UUID, SecretKeySpec> tenantKeys = new ConcurrentHashMap<>();

    public PiiMasker(Map<String, Set<String>> piiByTable, String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("meridian.audit.pseudonym-key is not set");
        }
        this.piiByTable = Map.copyOf(piiByTable);
        this.secret = secret.trim().getBytes(StandardCharsets.UTF_8);
    }

    /** Spec pii columns of a table (empty when unknown). */
    public Set<String> piiColumns(String table) {
        return table == null ? Set.of() : piiByTable.getOrDefault(table, Set.of());
    }

    /** A masked copy of {@code state} (null stays null). */
    public JsonNode mask(UUID tenant, String resourceTable, JsonNode state) {
        if (state == null || state.isNull() || state.isMissingNode()) {
            return state;
        }
        return walk(tenant, state, piiColumns(resourceTable), true);
    }

    private JsonNode walk(UUID tenant, JsonNode node, Set<String> topLevelPii, boolean top) {
        if (node.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            node.properties().forEach(e -> {
                String k = e.getKey();
                JsonNode v = e.getValue();
                if (v == null || v.isNull()) {
                    out.set(k, v);
                } else if ((top && topLevelPii.contains(k)) || isPersonalField(k)) {
                    out.put(k, pseudonym(tenant, v));
                } else {
                    out.set(k, walk(tenant, v, topLevelPii, false));
                }
            });
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            node.forEach(x -> out.add(walk(tenant, x, topLevelPii, false)));
            return out;
        }
        return node;
    }

    /** True for a field name that holds personal data by its name. */
    public static boolean isPersonalField(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (PERSONAL_FIELDS.contains(n)) {
            return true;
        }
        for (String s : SAFE_SUFFIXES) {
            if (n.endsWith(s)) {
                return false;
            }
        }
        for (String s : PERSONAL_SUFFIXES) {
            if (n.endsWith(s)) {
                return true;
            }
        }
        return false;
    }

    /** The tenant-keyed pseudonym of a value ({@code psn:} + 32 hex characters); already masked values stay. */
    public String pseudonym(UUID tenant, JsonNode value) {
        String raw = value.isString() ? value.asString() : value.toString();
        if (raw.startsWith(PREFIX) && raw.length() == PREFIX.length() + 32) {
            return raw;
        }
        String normalised = raw.trim().toLowerCase(Locale.ROOT);
        byte[] mac = hmac(tenantKeys.computeIfAbsent(tenant, t -> new SecretKeySpec(
                hmac(new SecretKeySpec(secret, "HmacSHA256"), "meridian-audit-pseudonym/v1|" + t), "HmacSHA256")),
                "value|" + normalised);
        return PREFIX + HexFormat.of().formatHex(mac, 0, 16);
    }

    private static byte[] hmac(SecretKeySpec k, String material) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(k);
            return mac.doFinal(material.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
