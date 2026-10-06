package io.meridian.cardtokenization.domain;

/**
 * Issuer provisioning decision for a token request (Visa VTS / Mastercard MDES "green / yellow / red path").
 * Wallet and device scores are on the wallet providers' 1-5 scale (5 = most trusted); a missing score counts as 3.
 */
public final class ProvisioningDecision {
    public enum Path { GREEN, YELLOW, RED }

    /** The decision and the reason that drove it (stored as the token's status reason). */
    public record Assessment(Path path, String reason) {}

    static final int NEUTRAL_SCORE = 3;

    private ProvisioningDecision() {}

    /**
     * @param cardStatus     status of the card in card-service
     * @param expiryMatches  the expiry sent by the token service matches the card (always true for push provisioning)
     * @param walletScore    wallet account score 1-5 or null
     * @param deviceScore    device score 1-5 or null
     * @param liveTokens     tokens of the card that are active, suspended or awaiting verification
     * @param maxTokens      configured limit of live tokens per card
     */
    public static Assessment assess(String cardStatus, boolean expiryMatches, Integer walletScore, Integer deviceScore,
                                    int liveTokens, int maxTokens) {
        if (!"ACTIVE".equals(cardStatus)) {
            return new Assessment(Path.RED, "CARD_NOT_ACTIVE");
        }
        if (!expiryMatches) {
            return new Assessment(Path.RED, "EXPIRY_MISMATCH");
        }
        if (liveTokens >= maxTokens) {
            return new Assessment(Path.RED, "TOKEN_LIMIT_REACHED");
        }
        int wallet = walletScore == null ? NEUTRAL_SCORE : walletScore;
        int device = deviceScore == null ? NEUTRAL_SCORE : deviceScore;
        if (wallet <= 1 || device <= 1) {
            return new Assessment(Path.RED, "HIGH_RISK_SCORE");
        }
        if (wallet >= 4 && device >= 4) {
            return new Assessment(Path.GREEN, "LOW_RISK");
        }
        return new Assessment(Path.YELLOW, "STEP_UP_REQUIRED");
    }
}
