package io.meridian.wallet.domain;

/**
 * WE-PRICE-1 (PE-16): classifies a cash-in / cash-out as ONSITE, OFFSITE or NOT_CLASSIFIED by comparing the serving
 * cell-id / location-area-code reported with the transaction against the outlet the agent operates from. ONSITE means
 * the transaction happened at the outlet's own cell (or LAC when only a LAC is configured); OFFSITE means it was
 * somewhere else; NOT_CLASSIFIED means either side supplied no location, so no judgement can be made.
 */
public final class RemoteClassifier {
    public static final String ONSITE = "ONSITE";
    public static final String OFFSITE = "OFFSITE";
    public static final String NOT_CLASSIFIED = "NOT_CLASSIFIED";

    private RemoteClassifier() {}

    public static String classify(String txnCell, String txnLac, String outletCell, String outletLac) {
        boolean txnHas = notBlank(txnCell) || notBlank(txnLac);
        boolean outletHas = notBlank(outletCell) || notBlank(outletLac);
        if (!txnHas || !outletHas) {
            return NOT_CLASSIFIED;
        }
        if (notBlank(outletCell)) {
            return outletCell.equals(txnCell) ? ONSITE : OFFSITE;
        }
        // only a LAC is configured on the outlet: a LAC match is onsite
        return outletLac.equals(txnLac) ? ONSITE : OFFSITE;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
