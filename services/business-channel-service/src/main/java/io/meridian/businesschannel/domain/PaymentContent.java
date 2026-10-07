package io.meridian.businesschannel.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The content of a business payment that approvers approve: what is paid, from where, to whom, when and (for batches)
 * every line. Its SHA-256 is stored at drafting and on every approval; a payment whose content no longer matches is
 * never approved or released.
 */
public final class PaymentContent {
    public static final List<String> FIELDS = List.of("kind", "debtor_account_id", "payee_id", "creditor_account_id",
            "amount", "currency", "instructed_amount", "instructed_currency", "fx_quote_id", "reference", "remittance",
            "purpose_code", "requested_execution_date", "name", "items");

    private PaymentContent() {}

    /**
     * Checksum over the content fields. {@code value} renders a field (null when absent); amounts are normalised so
     * 100, 100.0 and 100.00 hash alike.
     */
    public static String checksum(Function<String, String> value) {
        StringBuilder sb = new StringBuilder();
        for (String f : FIELDS) {
            String v = value.apply(f);
            if (v != null && (f.equals("amount") || f.equals("instructed_amount"))) {
                v = new BigDecimal(v).stripTrailingZeros().toPlainString();
            }
            sb.append(f).append('=').append(v == null ? "" : v.length()).append(':').append(v == null ? "" : v).append('\n');
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Canonical text of batch lines (stable key order). */
    public static String lines(List<Map<String, String>> lines) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, String> l : lines) {
            for (String k : BatchCsv.COLUMNS) {
                String v = l.get(k);
                sb.append(v == null ? "" : v.replace("\\", "\\\\").replace("|", "\\|")).append('|');
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
