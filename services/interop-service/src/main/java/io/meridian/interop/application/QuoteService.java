package io.meridian.interop.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.interop.domain.InteropCrypto;
import io.meridian.interop.infrastructure.Downstream;
import io.meridian.interop.infrastructure.Rows;
import io.meridian.interop.infrastructure.SwitchGateway;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Party lookup and quotes. Outbound quotes are requested from the switch for one of our payer wallets; inbound quote
 * requests (we are the payee FSP) resolve the payee wallet and answer with a condition whose fulfilment we keep
 * secret until the transfer is prepared.
 */
@Service
public class QuoteService {
    static final String TABLE = "interop_quote";
    static final Set<String> PARTY_TYPES = Set.of("MSISDN", "ACCOUNT", "ALIAS");

    private final Rows rows;
    private final SwitchService switches;
    private final SwitchGateway gateway;
    private final Downstream downstream;
    private final Events events;
    private final TransactionTemplate tx;

    public QuoteService(Rows rows, SwitchService switches, SwitchGateway gateway, Downstream downstream, Events events,
                        TransactionTemplate tx) {
        this.rows = rows;
        this.switches = switches;
        this.gateway = gateway;
        this.downstream = downstream;
        this.events = events;
        this.tx = tx;
    }

    public ObjectNode lookupParty(UUID switchId, String partyType, String partyId) {
        ObjectNode sw = switches.requireActive(switchId);
        requirePartyType(partyType);
        SwitchGateway.Party p = gateway.lookup(sw, partyType, partyId, switches.lookupCandidates(switchId));
        ObjectNode out = rows.object();
        out.put("found", p.found());
        out.put("party_type", partyType);
        out.put("party_id", partyId);
        out.put("fsp_id", p.fspId());
        out.put("fsp_name", p.found() ? participant(switchId, p.fspId()).map(x -> Rows.str(x, "name")).orElse(null) : null);
        out.put("name", p.name());
        out.put("currency", p.currency());
        return out;
    }

    private static void requirePartyType(String t) {
        if (!PARTY_TYPES.contains(t)) {
            throw ApiException.unprocessable("invalid_party_type", "party_type must be one of " + PARTY_TYPES);
        }
    }

    Optional<ObjectNode> participant(UUID switchId, String fspId) {
        return rows.queryOne("select row_to_json(p)::text from interop_participant p where interop_switch_id = ? and fsp_id = ?",
                switchId, fspId);
    }

    /** Outbound quote for one of our wallets; ACCEPTED with the switch's condition and fee, valid per the switch. */
    public ObjectNode createOutbound(JsonNode req) {
        UUID switchId = Rows.uuid(req, "interop_switch_id");
        ObjectNode sw = switches.requireActive(switchId);
        String currency = Rows.str(req, "currency");
        BigDecimal amount = Rows.dec(req, "amount");
        String partyType = Rows.str(req, "payee_party_type");
        String partyId = Rows.str(req, "payee_party_id");
        requirePartyType(partyType);
        if (amount == null || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2) {
            throw ApiException.unprocessable("invalid_amount", "amount must be positive with at most 2 decimals");
        }
        if (Rows.str(sw, "currency") != null && !Rows.str(sw, "currency").equals(currency)) {
            throw ApiException.unprocessable("currency_not_supported", "The switch settles in " + Rows.str(sw, "currency"));
        }
        UUID payer = Rows.uuid(req, "payer_wallet_id");
        if (payer == null) {
            throw ApiException.unprocessable("payer_required", "An outbound quote needs payer_wallet_id");
        }
        JsonNode wallet = downstream.wallet(payer);
        if (!"ACTIVE".equals(wallet.path("status").asString())) {
            throw ApiException.unprocessable("wallet_not_active", "The payer wallet is " + wallet.path("status").asString());
        }
        if (!currency.equals(wallet.path("currency").asString())) {
            throw ApiException.unprocessable("currency_mismatch", "The payer wallet is in " + wallet.path("currency").asString());
        }
        String fsp = Rows.str(req, "payee_fsp_id");
        if (fsp == null) {
            SwitchGateway.Party party = gateway.lookup(sw, partyType, partyId, switches.lookupCandidates(switchId));
            if (!party.found()) {
                throw ApiException.unprocessable("party_not_found", "No participant knows payee " + partyId);
            }
            fsp = party.fspId();
        }
        ObjectNode participant = participant(switchId, fsp).orElseThrow(() ->
                ApiException.unprocessable("participant_not_found", "Unknown participant " + Rows.str(req, "payee_fsp_id")));
        if (!Rows.bool(participant, "is_active")) {
            throw ApiException.unprocessable("participant_inactive", "Participant " + Rows.str(participant, "fsp_id") + " is not active");
        }
        String quoteId = Ids.newId().toString();
        SwitchGateway.Quote q = gateway.quote(sw, quoteId, fsp, partyType, partyId, amount, currency);
        String payeeFsp = fsp;
        return tx.execute(s -> {
            ObjectNode v = rows.object();
            v.put("interop_switch_id", switchId.toString());
            v.put("quote_id", quoteId);
            v.put("direction", "OUTBOUND");
            v.put("payer_wallet_id", payer.toString());
            v.put("payee_party_type", partyType);
            v.put("payee_party_id", partyId);
            v.put("payee_fsp_id", payeeFsp);
            if (q.payeeName() != null) {
                v.put("payee_name", q.payeeName());
            }
            v.put("amount", amount.toPlainString());
            v.put("currency", currency);
            v.put("payee_receive_amount", q.payeeReceiveAmount().toPlainString());
            v.put("fee_amount", q.fee().toPlainString());
            v.put("ilp_condition", q.ilpCondition());
            v.put("status", "ACCEPTED");
            copyCustom(req, v);
            ObjectNode row = rows.insert(TABLE, v);
            rows.execute("update interop_quote set expires_at = now() + make_interval(secs => ?) where id = ?",
                    Rows.integer(sw, "quote_validity_seconds"), Rows.uuid(row, "id"));
            return rows.get(TABLE, Rows.uuid(row, "id"));
        });
    }

    static void copyCustom(JsonNode req, ObjectNode v) {
        for (int i = 1; i <= 5; i++) {
            if (req.hasNonNull("custom_field_" + i)) {
                v.set("custom_field_" + i, req.get("custom_field_" + i));
            }
        }
    }

    // ------------------------------------------------------------------ switch callbacks

    /** QUOTE_REQUEST: we are the payee FSP. Idempotent on quote_id. */
    public ObjectNode onQuoteRequest(ObjectNode sw, JsonNode p) {
        String quoteId = required(p, "quote_id");
        Optional<ObjectNode> existing = byQuoteId(quoteId);
        if (existing.isPresent()) {
            return response(existing.get());
        }
        String partyType = Optional.ofNullable(Rows.str(p, "payee_party_type")).orElse("MSISDN");
        String partyId = required(p, "payee_party_id");
        BigDecimal amount = new BigDecimal(required(p, "amount"));
        String currency = required(p, "currency");
        String reason = null;
        JsonNode wallet = null;
        if (!PARTY_TYPES.contains(partyType)) {
            reason = "Unsupported party type " + partyType;
        } else if (amount.signum() <= 0) {
            reason = "Amount must be positive";
        } else {
            Optional<JsonNode> w = switch (partyType) {
                case "MSISDN" -> downstream.walletByMsisdn(partyId, currency);
                default -> Optional.empty();
            };
            if (w.isEmpty()) {
                reason = "Payee " + partyId + " has no active " + currency + " wallet";
            } else if (!currency.equals(w.get().path("currency").asString())) {
                reason = "Payee wallet does not accept " + currency;
            } else {
                wallet = w.get();
            }
        }
        String fulfilment = InteropCrypto.newFulfilment();
        String why = reason;
        JsonNode payee = wallet;
        return tx.execute(s -> {
            ObjectNode v = rows.object();
            v.put("interop_switch_id", Rows.str(sw, "id"));
            v.put("quote_id", quoteId);
            v.put("direction", "INBOUND");
            v.put("payer_fsp_id", Rows.str(p, "payer_fsp_id"));
            v.put("payee_party_type", PARTY_TYPES.contains(partyType) ? partyType : "MSISDN");
            v.put("payee_party_id", partyId);
            v.put("payee_fsp_id", Rows.str(sw, "participant_id"));
            v.put("amount", amount.toPlainString());
            v.put("currency", currency);
            if (payee != null) {
                v.put("payee_wallet_id", payee.path("id").asString());
                v.put("payee_name", payee.path("display_name").asString(null));
                v.put("payee_receive_amount", amount.toPlainString());
                v.put("fee_amount", "0");
                v.put("fulfilment", fulfilment);
                v.put("ilp_condition", InteropCrypto.condition(fulfilment));
                v.put("status", "ACCEPTED");
            } else {
                v.put("status", "REJECTED");
                v.put("rejection_reason", why.length() > 255 ? why.substring(0, 255) : why);
            }
            ObjectNode row = rows.insert(TABLE, v);
            rows.execute("update interop_quote set expires_at = now() + make_interval(secs => ?) where id = ?",
                    Rows.integer(sw, "quote_validity_seconds"), Rows.uuid(row, "id"));
            ObjectNode after = rows.get(TABLE, Rows.uuid(row, "id"));
            events.publish("wallets.interop_quote.received", TABLE, after);
            return response(after);
        });
    }

    /** QUOTE_RESPONSE / QUOTE_ERROR for one of our outbound quotes still PENDING; final quotes are left as they are. */
    public ObjectNode onQuoteResponse(ObjectNode sw, JsonNode p, boolean error) {
        String quoteId = required(p, "quote_id");
        ObjectNode q = byQuoteId(quoteId).filter(x -> "OUTBOUND".equals(Rows.str(x, "direction"))
                        && Rows.str(sw, "id").equals(Rows.str(x, "interop_switch_id")))
                .orElseThrow(() -> ApiException.notFound("interop quote", quoteId));
        if (!"PENDING".equals(Rows.str(q, "status"))) {
            return response(q);
        }
        return tx.execute(s -> {
            rows.lock(TABLE, Rows.uuid(q, "id"));
            ObjectNode v = rows.object();
            if (error) {
                v.put("status", "REJECTED");
                String why = Optional.ofNullable(Rows.str(p, "error_description")).orElse("Rejected by the payee FSP");
                v.put("rejection_reason", why.length() > 255 ? why.substring(0, 255) : why);
            } else {
                v.put("status", "ACCEPTED");
                v.put("ilp_condition", required(p, "ilp_condition"));
                if (p.hasNonNull("fee_amount")) {
                    v.put("fee_amount", Rows.str(p, "fee_amount"));
                }
                v.put("payee_receive_amount", Optional.ofNullable(Rows.str(p, "payee_receive_amount")).orElse(Rows.str(q, "amount")));
                if (p.hasNonNull("payee_name")) {
                    v.put("payee_name", Rows.str(p, "payee_name"));
                }
            }
            return response(rows.update(TABLE, Rows.uuid(q, "id"), v));
        });
    }

    /** Expires accepted quotes past their validity (database clock) that no transfer used. */
    public int expireQuotes() {
        return tx.execute(s -> rows.execute("""
                update interop_quote q set status = 'EXPIRED'
                where q.status in ('ACCEPTED', 'PENDING') and q.expires_at < now()
                  and not exists (select 1 from interop_transfer t where t.interop_quote_id = q.id)"""));
    }

    Optional<ObjectNode> byQuoteId(String quoteId) {
        return rows.queryOne("select row_to_json(q)::text from interop_quote q where quote_id = ?", quoteId);
    }

    ObjectNode response(JsonNode q) {
        ObjectNode out = rows.object();
        out.put("quote_id", Rows.str(q, "quote_id"));
        out.put("status", Rows.str(q, "status"));
        out.put("ilp_condition", Rows.str(q, "ilp_condition"));
        out.put("payee_receive_amount", Rows.str(q, "payee_receive_amount"));
        out.put("fee_amount", Rows.str(q, "fee_amount"));
        out.put("payee_name", Rows.str(q, "payee_name"));
        out.put("expires_at", Rows.str(q, "expires_at"));
        if (q.hasNonNull("rejection_reason")) {
            out.put("rejection_reason", Rows.str(q, "rejection_reason"));
        }
        return out;
    }

    static String required(JsonNode p, String f) {
        String v = Rows.str(p, f);
        if (v == null || v.isBlank()) {
            throw ApiException.unprocessable("invalid_callback", "payload." + f + " is required");
        }
        return v;
    }

    List<String> partyTypes() {
        return List.copyOf(PARTY_TYPES);
    }
}
