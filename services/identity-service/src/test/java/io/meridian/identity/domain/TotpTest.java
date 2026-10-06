package io.meridian.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** RFC 6238 appendix B test vectors (SHA-1 seed "12345678901234567890"), plus RFC 4226 HOTP vectors. */
class TotpTest {
    static final byte[] SEED = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    void rfc6238VectorsEightDigits() {
        assertThat(Totp.at(SEED, Instant.ofEpochSecond(59), 8)).isEqualTo("94287082");
        assertThat(Totp.at(SEED, Instant.ofEpochSecond(1111111109), 8)).isEqualTo("07081804");
        assertThat(Totp.at(SEED, Instant.ofEpochSecond(1111111111), 8)).isEqualTo("14050471");
        assertThat(Totp.at(SEED, Instant.ofEpochSecond(1234567890), 8)).isEqualTo("89005924");
        assertThat(Totp.at(SEED, Instant.ofEpochSecond(2000000000), 8)).isEqualTo("69279037");
        assertThat(Totp.at(SEED, Instant.ofEpochSecond(20000000000L), 8)).isEqualTo("65353130");
    }

    @Test
    void rfc4226HotpVectors() {
        String[] expected = {"755224", "287082", "359152", "969429", "338314", "254676", "287922", "162583", "399871", "520489"};
        for (int i = 0; i < expected.length; i++) {
            assertThat(Totp.hotp(SEED, i, 6)).isEqualTo(expected[i]);
        }
    }

    @Test
    void verifyAcceptsDriftWithinWindowOnly() {
        Instant now = Instant.ofEpochSecond(1111111111);
        String current = Totp.at(SEED, now, 6);
        String previous = Totp.at(SEED, now.minusSeconds(30), 6);
        String old = Totp.at(SEED, now.minusSeconds(90), 6);
        assertThat(Totp.verify(SEED, current, now, 1)).hasValue(Totp.counterAt(now));
        assertThat(Totp.verify(SEED, previous, now, 1)).isPresent();
        assertThat(Totp.verify(SEED, old, now, 1)).isEmpty();
        assertThat(Totp.verify(SEED, "12ab56", now, 1)).isEmpty();
        assertThat(Totp.verify(SEED, null, now, 1)).isEmpty();
    }

    @Test
    void base32RoundTripAndKnownValue() {
        assertThat(Base32.encode("foobar".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YTBOI");
        assertThat(new String(Base32.decode("MZXW6YTBOI======"), StandardCharsets.US_ASCII)).isEqualTo("foobar");
        byte[] random = Secrets.randomBytes(20);
        assertThat(Base32.decode(Base32.encode(random))).isEqualTo(random);
        assertThat(Totp.otpauthUri("Meridian", "alice", "ABC")).startsWith("otpauth://totp/Meridian:alice?secret=ABC");
    }
}
