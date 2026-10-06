package io.meridian.openbanking.domain;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal ASN.1 DER reader: enough to walk X.509 names and the eIDAS / PSD2 QCStatements extension
 * (ETSI TS 119 495). Definite lengths only (DER); indefinite or truncated encodings are rejected.
 */
public final class Der {
    public static final int SEQUENCE = 0x30;
    public static final int SET = 0x31;
    public static final int OID = 0x06;
    public static final int OCTET_STRING = 0x04;
    public static final int UTF8_STRING = 0x0C;
    public static final int PRINTABLE_STRING = 0x13;
    public static final int IA5_STRING = 0x16;
    public static final int TELETEX_STRING = 0x14;
    public static final int BMP_STRING = 0x1E;

    /** One TLV element. */
    public record Element(int tag, byte[] value) {
        public boolean isConstructed() {
            return (tag & 0x20) != 0;
        }

        /** Child elements of a constructed element (SEQUENCE, SET, explicit tags). */
        public List<Element> children() {
            if (!isConstructed()) {
                throw new MalformedException("element with tag 0x" + Integer.toHexString(tag) + " is not constructed");
            }
            return parseAll(value);
        }

        public String oid() {
            if (tag != OID) {
                throw new MalformedException("expected OBJECT IDENTIFIER, found tag 0x" + Integer.toHexString(tag));
            }
            return decodeOid(value);
        }

        /** Character string value (UTF8, Printable, IA5, Teletex as Latin-1, BMP as UTF-16BE). */
        public String string() {
            return switch (tag) {
                case UTF8_STRING, PRINTABLE_STRING, IA5_STRING -> new String(value, StandardCharsets.UTF_8);
                case TELETEX_STRING -> new String(value, StandardCharsets.ISO_8859_1);
                case BMP_STRING -> new String(value, StandardCharsets.UTF_16BE);
                default -> throw new MalformedException("expected a string, found tag 0x" + Integer.toHexString(tag));
            };
        }
    }

    public static final class MalformedException extends RuntimeException {
        public MalformedException(String message) {
            super(message);
        }
    }

    private Der() {}

    /** Parses exactly one element spanning the whole input. */
    public static Element parse(byte[] der) {
        List<Element> all = parseAll(der);
        if (all.size() != 1) {
            throw new MalformedException("expected one DER element, found " + all.size());
        }
        return all.getFirst();
    }

    /** Parses a concatenation of elements. */
    public static List<Element> parseAll(byte[] der) {
        List<Element> out = new ArrayList<>();
        int pos = 0;
        while (pos < der.length) {
            if (der.length - pos < 2) {
                throw new MalformedException("truncated DER element");
            }
            int tag = der[pos++] & 0xFF;
            if ((tag & 0x1F) == 0x1F) {
                throw new MalformedException("multi-byte tags are not supported");
            }
            int len = der[pos++] & 0xFF;
            if (len == 0x80) {
                throw new MalformedException("indefinite length is not DER");
            }
            if (len > 0x80) {
                int n = len & 0x7F;
                if (n > 4 || der.length - pos < n) {
                    throw new MalformedException("invalid DER length");
                }
                len = 0;
                for (int i = 0; i < n; i++) {
                    len = (len << 8) | (der[pos++] & 0xFF);
                }
                if (len < 0) {
                    throw new MalformedException("invalid DER length");
                }
            }
            if (der.length - pos < len) {
                throw new MalformedException("DER element longer than its container");
            }
            byte[] value = new byte[len];
            System.arraycopy(der, pos, value, 0, len);
            pos += len;
            out.add(new Element(tag, value));
        }
        return out;
    }

    static String decodeOid(byte[] v) {
        if (v.length == 0) {
            throw new MalformedException("empty OBJECT IDENTIFIER");
        }
        StringBuilder sb = new StringBuilder();
        long acc = 0;
        boolean first = true;
        for (int i = 0; i < v.length; i++) {
            int b = v[i] & 0xFF;
            acc = (acc << 7) | (b & 0x7F);
            if (acc > Long.MAX_VALUE >> 8) {
                throw new MalformedException("OBJECT IDENTIFIER arc too large");
            }
            if ((b & 0x80) == 0) {
                if (first) {
                    long a = acc < 80 ? acc / 40 : 2;
                    sb.append(a).append('.').append(acc - a * 40);
                    first = false;
                } else {
                    sb.append('.').append(acc);
                }
                acc = 0;
            } else if (i == v.length - 1) {
                throw new MalformedException("truncated OBJECT IDENTIFIER");
            }
        }
        return sb.toString();
    }
}
