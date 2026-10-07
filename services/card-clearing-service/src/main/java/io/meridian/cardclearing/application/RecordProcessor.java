package io.meridian.cardclearing.application;

import io.meridian.cardclearing.config.ClearingProperties;
import io.meridian.cardclearing.domain.ClearingMatcher;
import io.meridian.cardclearing.domain.ClearingMatcher.AuthView;
import io.meridian.cardclearing.domain.ClearingMatcher.Decision;
import io.meridian.cardclearing.domain.ParsedRecord;
import io.meridian.cardclearing.domain.Settlement;
import io.meridian.cardclearing.infrastructure.Clients;
import io.meridian.cardclearing.infrastructure.Clients.Leg;
import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Matches one clearing record and moves the money (no database writes: clearing_record is append-only, so the
 * outcome is computed first and inserted once). Ledger keys derive from the record id, so a retried call replays
 * the same postings.
 *
 * <ul>
 *   <li>PRESENTMENT matched: capture the authorisation hold (amount = min(cleared, remaining authorised)) against the
 *       scheme settlement account with release_remainder (single-presentment model: a cleared amount below the
 *       authorisation releases the rest); an excess within tolerance is posted as an ADJUSTMENT journal; then the
 *       authorisation is told it was cleared.</li>
 *   <li>PRESENTMENT without authorisation but with a known card, or whose authorisation already expired / was
 *       reversed: FORCE_POST journal debit cardholder, credit scheme settlement (the issuer must pay the scheme).</li>
 *   <li>REFUND: debit settlement, credit cardholder.</li>
 *   <li>CHARGEBACK, REPRESENTMENT, REVERSAL, FEE_COLLECTION, FUNDS_DISBURSEMENT: settlement-only (counted in the
 *       settlement position; cardholder effects belong to card-dispute-service).</li>
 * </ul>
 */
@Component
public class RecordProcessor {
    private final Clients clients;
    private final ClearingProperties props;

    public RecordProcessor(Clients clients, ClearingProperties props) {
        this.clients = clients;
        this.props = props;
    }

    /** A fee row to insert with the record. {@code charged} = cardholder fee (publishes cards.card_fee.charged). */
    public record Fee(UUID id, String feeType, BigDecimal amount, String currency, UUID ledgerAccountId,
                      UUID journalEntryId, String description, boolean charged) {}

    /** Result of processing one record. */
    public static final class Outcome {
        public final UUID recordId;
        public String matchStatus;
        public String matchRule;
        public UUID authId;
        public UUID holdId;
        public UUID cardId;
        public UUID programId;
        public UUID journalId;
        public BigDecimal authorized;
        public BigDecimal adjustment;
        public BigDecimal interchange;
        public String exceptionReason;
        public final List<Fee> fees = new ArrayList<>();
        /** Payment references of the record's postings (scheme reference, clearing file, merchant). */
        JsonNode refs;

        Outcome(UUID recordId) {
            this.recordId = recordId;
        }

        public boolean posted() {
            return "MATCHED".equals(matchStatus) || "FORCE_POST".equals(matchStatus);
        }

        void note(String n) {
            exceptionReason = exceptionReason == null ? n : exceptionReason + "; " + n;
        }
    }

    /**
     * @param forced  authorisation chosen by an operator (force match): the tolerance is waived, currency and status
     *                rules still apply
     */
    public Outcome process(UUID recordId, String scheme, UUID fileProgramId, ParsedRecord r, AuthView forced) {
        return process(recordId, scheme, fileProgramId, null, r, forced);
    }

    /** @param fileId clearing file of the record: the batch id of its postings */
    public Outcome process(UUID recordId, String scheme, UUID fileProgramId, UUID fileId, ParsedRecord r,
                           AuthView forced) {
        Outcome o = new Outcome(recordId);
        o.refs = clients.refs(ref(r), fileId == null ? null : fileId.toString(), r.merchantName());
        o.cardId = r.cardId();
        o.programId = fileProgramId;
        o.interchange = r.interchangeAmount();
        if (o.interchange == null && "PRESENTMENT".equals(r.recordType())) {
            o.interchange = Settlement.interchange(r.settlementAmount(), props.clearing().interchangeRate(scheme));
        }
        AuthView auth = null;
        try {
            switch (r.recordType()) {
                case "PRESENTMENT" -> auth = presentment(o, scheme, r, forced);
                case "REFUND" -> refund(o, scheme, r, forced);
                default -> settlementOnly(o, r, forced);
            }
        } catch (ApiException e) {
            o.matchStatus = "EXCEPTION";
            o.journalId = null;
            o.note("posting failed: " + e.code() + " " + e.getMessage());
            return o;
        }
        if ("PRESENTMENT".equals(r.recordType()) && o.posted() && o.cardId != null) {
            fees(o, scheme, r, auth);
        }
        return o;
    }

    // ------------------------------------------------------------------ record types

    private AuthView presentment(Outcome o, String scheme, ParsedRecord r, AuthView forced) {
        AuthView a = forced != null ? forced : find(o, r);
        if (forced != null) {
            o.matchRule = "FORCE";
        }
        if (a == null) {
            if (o.cardId == null) {
                o.matchStatus = "UNMATCHED";
                o.note("no authorisation found and no card on the record");
                return null;
            }
            Optional<JsonNode> card = clients.card(o.cardId);
            if (card.isEmpty()) {
                o.matchStatus = "UNMATCHED";
                o.note("no authorisation found and card " + o.cardId + " is unknown");
                return null;
            }
            o.programId = o.programId != null ? o.programId : uuid(card.get(), "card_program_id");
            o.matchRule = "NO_AUTHORIZATION";
            forcePost(o, scheme, r, uuid(card.get(), "ledger_account_id"));
            return null;
        }
        o.authId = a.id();
        o.cardId = o.cardId != null ? o.cardId : a.cardId();
        o.programId = o.programId != null ? o.programId : a.programId();
        o.authorized = a.approved();
        BigDecimal tolerance = forced != null ? new BigDecimal("1000000") : props.clearing().matchTolerancePct();
        Decision d = ClearingMatcher.evaluate(a, r.billingAmount(), r.billingCurrency(), tolerance);
        switch (d.kind()) {
            case EXCEPTION -> {
                o.matchStatus = "EXCEPTION";
                o.note(d.reason());
                return a;
            }
            case HOLD_GONE -> {
                UUID account = a.holdAccountId() != null ? a.holdAccountId() : cardAccount(o.cardId);
                o.note(d.reason() + ": posted without a hold");
                forcePost(o, scheme, r, account);
                return a;
            }
            case MATCHED -> {
                UUID settle = settlementAccount(scheme);
                BigDecimal capture = r.billingAmount().min(d.remaining());
                String desc = "Card presentment " + nz(r.merchantName(), "") + " " + r.transactionDate();
                if (a.holdId() != null && capture.signum() > 0) {
                    o.journalId = clients.capture(a.holdId(), capture, settle, desc.trim(), true,
                            "clearing-capture:" + o.recordId, ref(r), o.refs);
                    o.holdId = a.holdId();
                } else {
                    capture = BigDecimal.ZERO;
                }
                BigDecimal excess = r.billingAmount().subtract(capture);
                if (excess.signum() > 0) {
                    UUID account = a.holdAccountId() != null ? a.holdAccountId() : cardAccount(o.cardId);
                    UUID adj = clients.post(props.ledgerId(), capture.signum() == 0 ? "CARD_SETTLEMENT" : "ADJUSTMENT",
                            "Clearing adjustment over authorised amount", r.billingCurrency(), "CLEARING_RECORD",
                            o.recordId, ref(r), o.refs, List.of(new Leg(account, "DEBIT", excess, desc.trim()),
                                    new Leg(settle, "CREDIT", excess, "Scheme settlement")),
                            "clearing-adjust:" + o.recordId);
                    if (o.journalId == null) {
                        o.journalId = adj;
                    }
                }
                BigDecimal diff = r.billingAmount().subtract(d.remaining());
                o.adjustment = diff.signum() == 0 ? null : diff;
                o.matchStatus = "MATCHED";
                try {
                    clients.clearAuthorization(a.id(), r.billingAmount(), o.recordId, true);
                } catch (ApiException e) {
                    o.note("authorisation not updated: " + e.code());
                }
                return a;
            }
            default -> throw new IllegalStateException();
        }
    }

    private void refund(Outcome o, String scheme, ParsedRecord r, AuthView forced) {
        AuthView a = forced;
        if (a == null && r.networkTransactionId() != null) {
            a = first(clients.findAuthorizations("network_transaction_id", r.networkTransactionId()));
            if (a != null) {
                o.matchRule = "NETWORK_TRANSACTION_ID";
            }
        }
        if (a != null) {
            o.authId = a.id();
            o.cardId = o.cardId != null ? o.cardId : a.cardId();
            o.programId = o.programId != null ? o.programId : a.programId();
        }
        if (o.cardId == null) {
            o.matchStatus = "UNMATCHED";
            o.note("refund without a card or a matching authorisation");
            return;
        }
        Optional<JsonNode> card = clients.card(o.cardId);
        if (card.isEmpty()) {
            o.matchStatus = "UNMATCHED";
            o.note("card " + o.cardId + " is unknown");
            return;
        }
        o.programId = o.programId != null ? o.programId : uuid(card.get(), "card_program_id");
        UUID settle = settlementAccount(scheme);
        o.journalId = clients.post(props.ledgerId(), "CARD_SETTLEMENT", "Card refund " + nz(r.merchantName(), ""),
                r.billingCurrency(), "CLEARING_RECORD", o.recordId, ref(r), o.refs,
                List.of(new Leg(settle, "DEBIT", r.billingAmount(), "Scheme settlement"),
                        new Leg(uuid(card.get(), "ledger_account_id"), "CREDIT", r.billingAmount(), "Refund")),
                "clearing-refund:" + o.recordId);
        o.matchStatus = a != null ? "MATCHED" : "FORCE_POST";
        if (o.matchRule == null) {
            o.matchRule = a != null ? "FORCE" : "NO_AUTHORIZATION";
        }
    }

    private void settlementOnly(Outcome o, ParsedRecord r, AuthView forced) {
        AuthView a = forced;
        if (a == null && r.networkTransactionId() != null) {
            a = first(clients.findAuthorizations("network_transaction_id", r.networkTransactionId()));
        }
        if (a != null) {
            o.authId = a.id();
            o.cardId = o.cardId != null ? o.cardId : a.cardId();
            o.programId = o.programId != null ? o.programId : a.programId();
        }
        o.matchStatus = "MATCHED";
        o.matchRule = "SETTLEMENT_ONLY";
    }

    private void forcePost(Outcome o, String scheme, ParsedRecord r, UUID cardholderAccount) {
        UUID settle = settlementAccount(scheme);
        o.journalId = clients.post(props.ledgerId(), "CARD_SETTLEMENT",
                "Card presentment (force post) " + nz(r.merchantName(), ""), r.billingCurrency(), "CLEARING_RECORD",
                o.recordId, ref(r), o.refs, List.of(new Leg(cardholderAccount, "DEBIT", r.billingAmount(), "Card purchase"),
                        new Leg(settle, "CREDIT", r.billingAmount(), "Scheme settlement")),
                "clearing-post:" + o.recordId);
        o.matchStatus = "FORCE_POST";
    }

    // ------------------------------------------------------------------ fees

    private void fees(Outcome o, String scheme, ParsedRecord r, AuthView a) {
        if (r.isCrossCurrency() && a != null && a.fxMarkup() != null && a.fxMarkup().signum() > 0) {
            BigDecimal share = BigDecimal.ONE;
            if (a.billingAmount() != null && a.billingAmount().signum() > 0) {
                share = r.billingAmount().divide(a.billingAmount(), 10, RoundingMode.HALF_EVEN).min(BigDecimal.ONE);
            }
            BigDecimal markup = a.fxMarkup().multiply(share).setScale(2, RoundingMode.HALF_EVEN);
            if (markup.signum() > 0) {
                o.fees.add(new Fee(Ids.newId(), "FX_MARKUP", markup, r.billingCurrency(), a.holdAccountId(), null,
                        "FX markup included in the billing amount (no separate posting)", false));
            }
        }
        BigDecimal atmFee = props.clearing().atmFee();
        if ("6011".equals(r.mcc()) && atmFee.signum() > 0) {
            try {
                UUID account = cardAccount(o.cardId);
                UUID feeId = Ids.newId();
                UUID j = clients.post(props.ledgerId(), "FEE", "ATM withdrawal fee", r.billingCurrency(), "CARD_FEE",
                        feeId, ref(r), o.refs, List.of(new Leg(account, "DEBIT", atmFee, "ATM withdrawal fee"),
                                new Leg(props.clearing().feeIncomeAccount(), "CREDIT", atmFee, "ATM fee income")),
                        "clearing-fee-atm:" + o.recordId);
                o.fees.add(new Fee(feeId, "ATM_WITHDRAWAL", atmFee, r.billingCurrency(), account, j,
                        "ATM withdrawal fee", true));
            } catch (ApiException e) {
                o.note("ATM fee not posted: " + e.code());
            }
        }
        if (o.interchange != null && o.interchange.signum() > 0) {
            try {
                UUID feeId = Ids.newId();
                UUID j = clients.post(props.ledgerId(), "CARD_SETTLEMENT", "Interchange income", r.settlementCurrency(),
                        "CARD_FEE", feeId, ref(r), o.refs,
                        List.of(new Leg(settlementAccount(scheme), "DEBIT", o.interchange, "Interchange receivable"),
                                new Leg(props.clearing().interchangeIncomeAccount(), "CREDIT", o.interchange,
                                        "Interchange income")),
                        "clearing-interchange:" + o.recordId);
                o.fees.add(new Fee(feeId, "INTERCHANGE_INCOME", o.interchange, r.settlementCurrency(), null, j,
                        "Interchange " + nz(r.interchangeRateCode(), scheme), false));
            } catch (ApiException e) {
                o.note("interchange not posted: " + e.code());
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Candidate authorisation by network transaction id, else card + auth code, else card + RRN. */
    private AuthView find(Outcome o, ParsedRecord r) {
        if (r.networkTransactionId() != null) {
            AuthView a = ClearingMatcher.select(clients.findAuthorizations("network_transaction_id",
                    r.networkTransactionId()));
            if (a != null) {
                o.matchRule = "NETWORK_TRANSACTION_ID";
                return a;
            }
        }
        if (r.cardId() != null && r.authCode() != null) {
            AuthView a = ClearingMatcher.select(clients.findAuthorizations("card_id", r.cardId().toString(),
                    "auth_code", r.authCode()));
            if (a != null) {
                o.matchRule = "AUTH_CODE";
                return a;
            }
        }
        if (r.cardId() != null && r.rrn() != null) {
            AuthView a = ClearingMatcher.select(clients.findAuthorizations("card_id", r.cardId().toString(),
                    "rrn", r.rrn()));
            if (a != null) {
                o.matchRule = "RRN";
                return a;
            }
        }
        return null;
    }

    private static AuthView first(List<AuthView> list) {
        return ClearingMatcher.select(list);
    }

    UUID settlementAccount(String scheme) {
        UUID a = props.clearing().settlementAccount(scheme);
        if (a == null) {
            throw new ApiException(500, "settlement_account_not_configured",
                    "No settlement account is configured for scheme " + scheme);
        }
        return a;
    }

    private UUID cardAccount(UUID cardId) {
        if (cardId == null) {
            throw ApiException.unprocessable("card_unknown", "the record has no card");
        }
        return clients.card(cardId).map(c -> uuid(c, "ledger_account_id"))
                .orElseThrow(() -> ApiException.unprocessable("card_unknown", "card " + cardId + " is unknown"));
    }

    private static String ref(ParsedRecord r) {
        return r.arn() != null ? r.arn() : r.networkTransactionId() != null ? r.networkTransactionId() : r.rrn();
    }

    private static String nz(String s, String d) {
        return s == null ? d : s;
    }

    static UUID uuid(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() || v.asString().isBlank() ? null : UUID.fromString(v.asString());
    }
}
