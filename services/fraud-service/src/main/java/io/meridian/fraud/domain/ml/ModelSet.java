package io.meridian.fraud.domain.ml;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The active deployments of one ai-platform model in this service's environment, as roles:
 * <ul>
 *   <li>CHAMPION: decides (at most one).</li>
 *   <li>CANARY: decides for a deterministic slice of {@code traffic_percent} of the subjects (hash of subject id and
 *       deployment id); the champion is then logged as a challenger for that event.</li>
 *   <li>CHALLENGER and SHADOW: always scored and logged, never decide.</li>
 * </ul>
 * A deployment whose artefact could not be loaded keeps its role with {@code error} set; if it would decide, the
 * evaluation degrades to rules.
 */
public record ModelSet(UUID modelId, String modelCode, List<Deployment> deployments) {

    public enum Role { CHAMPION, CANARY, CHALLENGER, SHADOW }

    /**
     * @param error null when loaded; else MODEL_UNAVAILABLE, CHECKSUM_MISMATCH or INVALID_ARTIFACT
     */
    public record Deployment(UUID deploymentId, UUID versionId, String version, Role role, int trafficPercent,
                             String sha256, ModelArtifact artifact, String error, String errorDetail) {
        public boolean usable() {
            return artifact != null && error == null;
        }
    }

    public ModelSet {
        deployments = deployments.stream()
                .sorted(Comparator.comparing((Deployment d) -> d.role().ordinal()).thenComparing(Deployment::deploymentId))
                .toList();
    }

    public Deployment champion() {
        return deployments.stream().filter(d -> d.role() == Role.CHAMPION).findFirst().orElse(null);
    }

    /** The deployment that decides for this subject: a canary whose slice contains it, else the champion (or null). */
    public Deployment deciding(UUID subjectId) {
        int cumulative = 0;
        for (Deployment d : deployments) {
            if (d.role() != Role.CANARY) {
                continue;
            }
            int bucket = bucket(subjectId, d.deploymentId());
            if (bucket >= cumulative && bucket < cumulative + d.trafficPercent()) {
                return d;
            }
            cumulative += d.trafficPercent();
        }
        return champion();
    }

    /** Everything else, scored for the log only (challengers, shadows, canaries outside their slice, the champion when a canary decides). */
    public List<Deployment> observers(Deployment deciding) {
        List<Deployment> out = new ArrayList<>();
        for (Deployment d : deployments) {
            if (d != deciding) {
                out.add(d);
            }
        }
        return out;
    }

    /**
     * Stable 0-99 slice of a subject for a canary: the first 4 bytes of SHA-256(deployment id || subject id).
     * The same subject always lands in the same slice of a given deployment.
     */
    public static int bucket(UUID subjectId, UUID deploymentId) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(deploymentId.toString().getBytes(StandardCharsets.US_ASCII));
            md.update(subjectId == null ? new byte[0] : subjectId.toString().getBytes(StandardCharsets.US_ASCII));
            int v = ByteBuffer.wrap(md.digest(), 0, 4).getInt();
            return Math.floorMod(v, 100);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
