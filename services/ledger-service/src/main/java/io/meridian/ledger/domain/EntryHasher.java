package io.meridian.ledger.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Canonical SHA-256 content hash of a journal entry. Inputs are normalised exactly as PostgreSQL stores them
 * (UTC, microsecond timestamps; amounts without trailing zeros), so verification can recompute the hash from
 * the stored rows and detect any later modification.
 */
public final class EntryHasher {
    private EntryHasher() {}

    public record Line(UUID accountId, String direction, BigDecimal amount, String currency, long accountVersion) {}

    /** Normalises a timestamp to what timestamptz stores: UTC, microsecond precision. */
    public static OffsetDateTime normalize(OffsetDateTime t) {
        return t.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    public static String hash(UUID entryId, UUID ledgerId, Instant effectiveAt, List<Line> lines) {
        StringBuilder sb = new StringBuilder()
                .append(entryId).append('|').append(ledgerId).append('|')
                .append(ChronoUnit.MICROS.between(Instant.EPOCH, effectiveAt));
        for (Line l : lines) {
            sb.append('|').append(l.accountId()).append(':').append(l.direction()).append(':')
                    .append(l.amount().stripTrailingZeros().toPlainString()).append(':').append(l.currency())
                    .append(':').append(l.accountVersion());
        }
        return sha256(sb.toString());
    }

    /** Next link of the verification chain: sha256(previous chain value + entry hash). */
    public static String chain(String previous, String entryHash) {
        return sha256((previous == null ? "" : previous) + entryHash);
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
