package io.meridian.payment.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * EMVCo Merchant-Presented Mode QR (EMV QRCPS-MPM 1.1): TLV payload with two-digit ids and lengths, terminated by the
 * CRC (id 63, CRC-16/CCITT-FALSE over the whole payload including "6304").
 */
public final class EmvQr {
    public static final String PAYLOAD_FORMAT = "00";
    public static final String POINT_OF_INITIATION = "01";
    public static final String MERCHANT_ACCOUNT = "26";
    public static final String MCC = "52";
    public static final String CURRENCY = "53";
    public static final String AMOUNT = "54";
    public static final String COUNTRY = "58";
    public static final String NAME = "59";
    public static final String CITY = "60";
    public static final String ADDITIONAL = "62";
    public static final String CRC = "63";

    /** Data needed to render a QR. */
    public record Spec(boolean dynamic, String guid, String merchantAccountRef, String mcc, String currency,
                       BigDecimal amount, String country, String merchantName, String merchantCity, String reference) {}

    /** A decoded payload; {@code fields} keeps the top-level TLVs in order, templates decoded one level deep. */
    public record Decoded(Map<String, String> fields, Map<String, Map<String, String>> templates, boolean crcValid,
                          String expectedCrc) {
        public Optional<String> field(String id) {
            return Optional.ofNullable(fields.get(id));
        }
    }

    private EmvQr() {}

    public static String encode(Spec s) {
        StringBuilder p = new StringBuilder();
        tlv(p, PAYLOAD_FORMAT, "01");
        tlv(p, POINT_OF_INITIATION, s.dynamic() ? "12" : "11");
        StringBuilder mai = new StringBuilder();
        tlv(mai, "00", s.guid());
        tlv(mai, "01", s.merchantAccountRef());
        tlv(p, MERCHANT_ACCOUNT, mai.toString());
        tlv(p, MCC, s.mcc() == null ? "0000" : s.mcc());
        tlv(p, CURRENCY, numericCurrency(s.currency()));
        if (s.amount() != null) {
            tlv(p, AMOUNT, s.amount().stripTrailingZeros().toPlainString());
        }
        tlv(p, COUNTRY, s.country());
        tlv(p, NAME, ascii(s.merchantName(), 25));
        tlv(p, CITY, ascii(s.merchantCity(), 15));
        if (s.reference() != null && !s.reference().isBlank()) {
            StringBuilder add = new StringBuilder();
            tlv(add, "05", ascii(s.reference(), 25));
            tlv(p, ADDITIONAL, add.toString());
        }
        p.append(CRC).append("04");
        p.append(crc16Hex(p.toString()));
        return p.toString();
    }

    /** Parses and verifies a payload; throws {@link IllegalArgumentException} with a stable code on malformed TLV. */
    public static Decoded decode(String payload) {
        if (payload == null || payload.length() < 12) {
            throw new IllegalArgumentException("invalid_qr_payload");
        }
        Map<String, String> fields = parse(payload);
        if (!"01".equals(fields.get(PAYLOAD_FORMAT))) {
            throw new IllegalArgumentException("invalid_qr_payload_format");
        }
        String crc = fields.get(CRC);
        if (crc == null || !payload.endsWith(CRC + "04" + crc)) {
            throw new IllegalArgumentException("invalid_qr_crc_position");
        }
        String expected = crc16Hex(payload.substring(0, payload.length() - 4));
        Map<String, Map<String, String>> templates = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            int id = Integer.parseInt(e.getKey());
            if ((id >= 26 && id <= 51) || id == 62 || id == 64 || (id >= 80 && id <= 99)) {
                try {
                    templates.put(e.getKey(), parse(e.getValue()));
                } catch (IllegalArgumentException ignored) {
                    // primitive value in a template slot: leave it as is
                }
            }
        }
        return new Decoded(fields, templates, expected.equalsIgnoreCase(crc), expected);
    }

    static Map<String, String> parse(String s) {
        Map<String, String> out = new LinkedHashMap<>();
        int i = 0;
        while (i < s.length()) {
            if (i + 4 > s.length()) {
                throw new IllegalArgumentException("invalid_qr_tlv");
            }
            String id = s.substring(i, i + 2);
            String lenText = s.substring(i + 2, i + 4);
            if (!id.chars().allMatch(Character::isDigit) || !lenText.chars().allMatch(Character::isDigit)) {
                throw new IllegalArgumentException("invalid_qr_tlv");
            }
            int len = Integer.parseInt(lenText);
            if (i + 4 + len > s.length()) {
                throw new IllegalArgumentException("invalid_qr_tlv");
            }
            out.put(id, s.substring(i + 4, i + 4 + len));
            i += 4 + len;
        }
        return out;
    }

    private static void tlv(StringBuilder sb, String id, String value) {
        if (value == null) {
            return;
        }
        if (value.length() > 99) {
            throw new IllegalArgumentException("qr_field_too_long:" + id);
        }
        sb.append(id).append(String.format("%02d", value.length())).append(value);
    }

    /** CRC-16/CCITT-FALSE: polynomial 0x1021, initial 0xFFFF, no reflection, no final XOR. */
    public static int crc16(byte[] data) {
        int crc = 0xFFFF;
        for (byte b : data) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
                crc &= 0xFFFF;
            }
        }
        return crc;
    }

    public static String crc16Hex(String s) {
        return String.format("%04X", crc16(s.getBytes(StandardCharsets.US_ASCII)));
    }

    /** ISO 4217 numeric code, e.g. EUR -> 978. */
    public static String numericCurrency(String alpha) {
        return String.format("%03d", Currency.getInstance(alpha.toUpperCase(Locale.ROOT)).getNumericCode());
    }

    public static Optional<String> alphaCurrency(String numeric) {
        int n = Integer.parseInt(numeric);
        return Currency.getAvailableCurrencies().stream().filter(c -> c.getNumericCode() == n)
                .map(Currency::getCurrencyCode).sorted().findFirst();
    }

    private static String ascii(String s, int max) {
        String a = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replaceAll("[^\\x20-\\x7E]", "");
        return a.length() > max ? a.substring(0, max) : a;
    }
}
