package io.meridian.merchant.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * EMVCo merchant-presented QR (MPM) codec. A payload is a sequence of TLV data objects: 2-digit id, 2-digit length,
 * value; templates (26-51, 62) nest TLVs. The payload ends with tag 63 (length 04) holding the CRC-16/CCITT-FALSE
 * (polynomial 0x1021, initial value 0xFFFF) computed over everything before it including "6304", in upper-case hex.
 */
public final class EmvQr {
    public static final String GUID = "io.meridian";
    private static final Map<String, String> NUMERIC = Map.ofEntries(
            Map.entry("KES", "404"), Map.entry("UGX", "800"), Map.entry("TZS", "834"), Map.entry("RWF", "646"),
            Map.entry("NGN", "566"), Map.entry("GHS", "936"), Map.entry("ZAR", "710"), Map.entry("ZMW", "967"),
            Map.entry("XOF", "952"), Map.entry("XAF", "950"), Map.entry("ETB", "230"), Map.entry("EGP", "818"),
            Map.entry("MAD", "504"), Map.entry("INR", "356"), Map.entry("PKR", "586"), Map.entry("BDT", "050"),
            Map.entry("PHP", "608"), Map.entry("IDR", "360"), Map.entry("USD", "840"), Map.entry("EUR", "978"),
            Map.entry("GBP", "826"), Map.entry("MWK", "454"), Map.entry("MZN", "943"), Map.entry("BWP", "072"));

    private EmvQr() {}

    /** The fields of a merchant QR. */
    public record Fields(boolean dynamic, String merchantCode, String qrId, String mcc, String currency,
                         BigDecimal amount, String country, String name, String city, String reference,
                         String terminalId) {}

    /** Thrown for structurally invalid payloads. */
    public static final class MalformedQr extends RuntimeException {
        public MalformedQr(String message) {
            super(message);
        }
    }

    public static String numericCurrency(String alpha) {
        String n = NUMERIC.get(alpha);
        if (n == null) {
            throw new IllegalArgumentException("Currency " + alpha + " has no ISO 4217 numeric code mapping");
        }
        return n;
    }

    public static String alphaCurrency(String numeric) {
        return NUMERIC.entrySet().stream().filter(e -> e.getValue().equals(numeric)).map(Map.Entry::getKey)
                .findFirst().orElse(null);
    }

    public static String encode(Fields f) {
        StringBuilder sb = new StringBuilder();
        tlv(sb, "00", "01");
        tlv(sb, "01", f.dynamic() ? "12" : "11");
        StringBuilder acct = new StringBuilder();
        tlv(acct, "00", GUID);
        tlv(acct, "01", f.merchantCode());
        if (f.qrId() != null) {
            tlv(acct, "02", f.qrId());
        }
        tlv(sb, "26", acct.toString());
        tlv(sb, "52", f.mcc());
        tlv(sb, "53", numericCurrency(f.currency()));
        if (f.amount() != null) {
            tlv(sb, "54", f.amount().stripTrailingZeros().scale() <= 0
                    ? f.amount().setScale(0).toPlainString() : f.amount().toPlainString());
        }
        tlv(sb, "58", f.country());
        tlv(sb, "59", clip(ascii(f.name()), 25));
        tlv(sb, "60", clip(ascii(f.city() == null ? "NA" : f.city()), 15));
        StringBuilder add = new StringBuilder();
        if (f.reference() != null) {
            tlv(add, "01", clip(f.reference(), 25));
        }
        if (f.terminalId() != null) {
            tlv(add, "07", clip(f.terminalId(), 25));
        }
        if (!add.isEmpty()) {
            tlv(sb, "62", add.toString());
        }
        sb.append("6304");
        return sb.append(crcHex(sb.toString())).toString();
    }

    /** Parses top-level TLVs (template values kept as raw strings); throws {@link MalformedQr}. */
    public static Map<String, String> parse(String data) {
        Map<String, String> out = new LinkedHashMap<>();
        int i = 0;
        while (i < data.length()) {
            if (i + 4 > data.length()) {
                throw new MalformedQr("Truncated data object at position " + i);
            }
            String id = data.substring(i, i + 2);
            String len = data.substring(i + 2, i + 4);
            if (!id.matches("\\d{2}") || !len.matches("\\d{2}")) {
                throw new MalformedQr("Invalid id/length at position " + i);
            }
            int n = Integer.parseInt(len);
            if (i + 4 + n > data.length()) {
                throw new MalformedQr("Data object " + id + " overruns the payload");
            }
            out.put(id, data.substring(i + 4, i + 4 + n));
            i += 4 + n;
        }
        return out;
    }

    /** True when the payload ends with a well-formed tag 63 whose CRC matches the preceding data. */
    public static boolean crcValid(String payload) {
        if (payload == null || payload.length() < 8 || !payload.startsWith("6304", payload.length() - 8)) {
            return false;
        }
        String body = payload.substring(0, payload.length() - 4);
        return crcHex(body).equalsIgnoreCase(payload.substring(payload.length() - 4));
    }

    /** Decodes a payload into fields (CRC not checked here; see {@link #crcValid}). */
    public static Fields decode(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new MalformedQr("Empty payload");
        }
        Map<String, String> top = parse(payload.trim());
        if (!"01".equals(top.get("00"))) {
            throw new MalformedQr("Payload format indicator (00) must be 01");
        }
        Map<String, String> acct = top.containsKey("26") ? parse(top.get("26")) : Map.of();
        Map<String, String> add = top.containsKey("62") ? parse(top.get("62")) : Map.of();
        String amount = top.get("54");
        BigDecimal amt;
        try {
            amt = amount == null ? null : new BigDecimal(amount);
        } catch (NumberFormatException e) {
            throw new MalformedQr("Transaction amount (54) is not numeric");
        }
        String num = top.get("53");
        return new Fields("12".equals(top.get("01")), acct.get("01"), acct.get("02"), top.get("52"),
                num == null ? null : alphaCurrency(num), amt, top.get("58"), top.get("59"), top.get("60"),
                add.get("01"), add.get("07"));
    }

    /** CRC-16/CCITT-FALSE as 4 upper-case hex digits. */
    public static String crcHex(String data) {
        return String.format("%04X", crc16(data.getBytes(StandardCharsets.UTF_8)));
    }

    public static int crc16(byte[] bytes) {
        int crc = 0xFFFF;
        for (byte b : bytes) {
            crc ^= (b & 0xFF) << 8;
            for (int k = 0; k < 8; k++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
                crc &= 0xFFFF;
            }
        }
        return crc;
    }

    private static void tlv(StringBuilder sb, String id, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (value.length() > 99) {
            throw new IllegalArgumentException("Data object " + id + " exceeds 99 characters");
        }
        sb.append(id).append(String.format("%02d", value.length())).append(value);
    }

    private static String clip(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String ascii(String s) {
        String n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.replaceAll("[^\\x20-\\x7E]", "").toUpperCase(Locale.ROOT).trim();
    }
}
