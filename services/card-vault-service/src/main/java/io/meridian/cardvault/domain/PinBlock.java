package io.meridian.cardvault.domain;

import java.util.Arrays;

/**
 * ISO 9564-1 format 0 PIN block: PIN field {@code 0 | len | PIN | F...} (16 nibbles) XOR PAN field
 * {@code 0000 | rightmost 12 PAN digits excluding the check digit}. Clear blocks are 8 bytes; callers wipe them.
 */
public final class PinBlock {
    private PinBlock() {}

    public static byte[] encode(String pin, String pan) {
        if (!PinRules.wellFormed(pin)) {
            throw new IllegalArgumentException("PIN must be 4-12 digits");
        }
        StringBuilder f = new StringBuilder(16).append('0').append(Integer.toHexString(pin.length()).toUpperCase())
                .append(pin);
        while (f.length() < 16) {
            f.append('F');
        }
        return xor(hex(f.toString()), panField(pan));
    }

    /** Recovers the PIN; throws {@link IllegalArgumentException} when the block is not a valid format 0 block. */
    public static String decode(byte[] block, String pan) {
        if (block == null || block.length != 8) {
            throw new IllegalArgumentException("PIN block must be 8 bytes");
        }
        byte[] clear = xor(block, panField(pan));
        try {
            String nib = java.util.HexFormat.of().withUpperCase().formatHex(clear);
            if (nib.charAt(0) != '0') {
                throw new IllegalArgumentException("not a format 0 PIN block");
            }
            int len = Character.digit(nib.charAt(1), 16);
            if (len < 4 || len > 12) {
                throw new IllegalArgumentException("bad PIN length");
            }
            String pin = nib.substring(2, 2 + len);
            if (!pin.chars().allMatch(c -> c >= '0' && c <= '9')) {
                throw new IllegalArgumentException("bad PIN digits");
            }
            for (int i = 2 + len; i < 16; i++) {
                if (nib.charAt(i) != 'F') {
                    throw new IllegalArgumentException("bad PIN padding");
                }
            }
            return pin;
        } finally {
            Arrays.fill(clear, (byte) 0);
        }
    }

    static byte[] panField(String pan) {
        if (pan == null || pan.length() < 13) {
            throw new IllegalArgumentException("PAN too short");
        }
        String twelve = pan.substring(pan.length() - 13, pan.length() - 1);
        return hex("0000" + twelve);
    }

    private static byte[] xor(byte[] a, byte[] b) {
        byte[] r = new byte[8];
        for (int i = 0; i < 8; i++) {
            r[i] = (byte) (a[i] ^ b[i]);
        }
        return r;
    }

    private static byte[] hex(String s) {
        return java.util.HexFormat.of().parseHex(s);
    }
}
