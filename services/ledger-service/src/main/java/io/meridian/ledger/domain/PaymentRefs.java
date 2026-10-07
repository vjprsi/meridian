package io.meridian.ledger.domain;

import io.meridian.common.error.ApiException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Structured payment references of a journal entry (request field {@code external_refs}), stored in typed columns of
 * the journal entry and copied to each of its ledger entries, so reconciliation can match statement lines on UETR,
 * end-to-end id, instruction id, bank / scheme reference, batch id and counterparty. Immutable once posted (both tables
 * are append-only).
 *
 * <p>The counterparty account is accepted in clear on the request and stored only as a tenant-keyed pseudonym
 * ({@code counterparty_account_hash}, see {@link io.meridian.common.refs.CounterpartyAccounts}) plus its last four
 * characters; {@link #parse} keeps the clear value only until the posting computes the pseudonym.
 */
public record PaymentRefs(String uetr, String endToEndId, String instructionId, String bankReference,
                          String schemeReference, String batchId, String counterpartyName, String counterpartyAccount,
                          String counterpartyAccountHash, String counterpartyAccountLast4, String purposeCode) {

    public static final PaymentRefs NONE = new PaymentRefs(null, null, null, null, null, null, null, null, null, null, null);

    private static final Set<String> KEYS = Set.of("uetr", "end_to_end_id", "instruction_id", "bank_reference",
            "scheme_reference", "batch_id", "counterparty_name", "counterparty_account", "purpose_code");
    private static final Pattern UUID_RE = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Pattern PURPOSE_RE = Pattern.compile("^[A-Za-z0-9]{1,35}$");

    /** Column values by column name, without nulls (what is written to journal_entry and ledger_entry). */
    public Map<String, String> columns() {
        Map<String, String> m = new LinkedHashMap<>();
        put(m, "uetr", uetr);
        put(m, "end_to_end_id", endToEndId);
        put(m, "instruction_id", instructionId);
        put(m, "bank_reference", bankReference);
        put(m, "scheme_reference", schemeReference);
        put(m, "batch_id", batchId);
        put(m, "counterparty_name", counterpartyName);
        put(m, "counterparty_account_hash", counterpartyAccountHash);
        put(m, "counterparty_account_last4", counterpartyAccountLast4);
        put(m, "purpose_code", purposeCode);
        return m;
    }

    public boolean isEmpty() {
        return columns().isEmpty() && counterpartyAccount == null;
    }

    /** The same references with the clear account replaced by its pseudonym and last four characters. */
    public PaymentRefs pseudonymised(String hash, String last4) {
        return new PaymentRefs(uetr, endToEndId, instructionId, bankReference, schemeReference, batchId,
                counterpartyName, null, hash, last4, purposeCode);
    }

    /** References of a stored journal entry (reversals copy the references of the entry they reverse). */
    public static PaymentRefs ofStored(JsonNode entry) {
        return new PaymentRefs(text(entry, "uetr"), text(entry, "end_to_end_id"), text(entry, "instruction_id"),
                text(entry, "bank_reference"), text(entry, "scheme_reference"), text(entry, "batch_id"),
                text(entry, "counterparty_name"), null, text(entry, "counterparty_account_hash"),
                text(entry, "counterparty_account_last4"), text(entry, "purpose_code"));
    }

    /**
     * Validates the request object {@code external_refs}: only known keys, string values within the column lengths,
     * UETR a UUID (stored lower-case), purpose code alphanumeric. Null / absent means no references.
     */
    public static PaymentRefs parse(JsonNode refs) {
        if (refs == null || refs.isNull() || refs.isMissingNode()) {
            return NONE;
        }
        if (!refs.isObject()) {
            throw invalid("external_refs must be an object");
        }
        for (String name : refs.propertyNames()) {
            if (!KEYS.contains(name)) {
                throw invalid("unknown key external_refs." + name + " (allowed: " + String.join(", ",
                        KEYS.stream().sorted().toList()) + ")");
            }
            JsonNode v = refs.get(name);
            if (!v.isNull() && !v.isString()) {
                throw invalid("external_refs." + name + " must be a string");
            }
        }
        String uetr = field(refs, "uetr", 36);
        if (uetr != null) {
            uetr = uetr.toLowerCase(Locale.ROOT);
            if (!UUID_RE.matcher(uetr).matches()) {
                throw invalid("external_refs.uetr must be a UUID (ISO 20022 UETR)");
            }
        }
        String purpose = field(refs, "purpose_code", 35);
        if (purpose != null && !PURPOSE_RE.matcher(purpose).matches()) {
            throw invalid("external_refs.purpose_code must be alphanumeric");
        }
        String account = field(refs, "counterparty_account", 64);
        return new PaymentRefs(uetr, field(refs, "end_to_end_id", 35), field(refs, "instruction_id", 35),
                field(refs, "bank_reference", 64), field(refs, "scheme_reference", 64), field(refs, "batch_id", 64),
                field(refs, "counterparty_name", 140), account, null, null, purpose);
    }

    private static String field(JsonNode refs, String name, int max) {
        JsonNode v = refs.get(name);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asString().strip();
        if (s.isEmpty()) {
            return null;
        }
        if (s.length() > max) {
            throw invalid("external_refs." + name + " is longer than " + max + " characters");
        }
        return s;
    }

    private static String text(JsonNode n, String field) {
        return n != null && n.hasNonNull(field) ? n.get(field).asString() : null;
    }

    private static void put(Map<String, String> m, String k, String v) {
        if (v != null) {
            m.put(k, v);
        }
    }

    private static ApiException invalid(String detail) {
        return ApiException.unprocessable("invalid_external_refs", detail);
    }
}
