package io.meridian.card.domain;

import java.math.BigInteger;
import java.util.random.RandomGenerator;

/**
 * Generates PANs inside a program's account range: a prefix chosen uniformly in [rangeStart, rangeEnd] (equal-length
 * digit strings starting with the BIN), random individual account digits up to {@code panLength - 1}, and the Luhn
 * check digit. The PAN only ever travels to the vault; callers must not log or persist it.
 */
public final class PanGenerator {
    private final RandomGenerator random;

    public PanGenerator(RandomGenerator random) {
        this.random = random;
    }

    public String generate(String rangeStart, String rangeEnd, int panLength) {
        ProgramRules.validateRangeShape(rangeStart, rangeEnd, panLength);
        BigInteger lo = new BigInteger(rangeStart);
        BigInteger hi = new BigInteger(rangeEnd);
        BigInteger span = hi.subtract(lo).add(BigInteger.ONE);
        BigInteger offset = randomBelow(span);
        String prefix = pad(lo.add(offset).toString(), rangeStart.length());
        StringBuilder sb = new StringBuilder(panLength).append(prefix);
        while (sb.length() < panLength - 1) {
            sb.append((char) ('0' + random.nextInt(10)));
        }
        sb.append((char) ('0' + Luhn.checkDigit(sb)));
        return sb.toString();
    }

    private BigInteger randomBelow(BigInteger bound) {
        if (bound.bitLength() < 62) {
            return BigInteger.valueOf(random.nextLong(bound.longValue()));
        }
        int bits = bound.bitLength();
        BigInteger r;
        do { // rejection sampling over exactly `bits` random bits keeps the choice uniform
            byte[] bytes = new byte[(bits + 7) / 8];
            random.nextBytes(bytes);
            r = new BigInteger(1, bytes).shiftRight(bytes.length * 8 - bits);
        } while (r.compareTo(bound) >= 0);
        return r;
    }

    private static String pad(String s, int len) {
        return "0".repeat(Math.max(0, len - s.length())) + s;
    }
}
