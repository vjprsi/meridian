package io.meridian.interop.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.interop.domain.InteropCrypto;
import io.meridian.interop.domain.InteropRules;
import io.meridian.interop.infrastructure.Downstream;
import io.meridian.interop.infrastructure.Rows;
import io.meridian.interop.infrastructure.SwitchGateway;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Two-phase interoperable transfers. Outbound: the payer's funds (amount + our fee) are reserved in wallet-service
 * (hold towards the switch settlement account) under the net debit cap, the prepare goes to the switch, and the
 * payee FSP's fulfilment commits the hold (abort, error or expiry release it). Inbound: a prepare matching one of
 * our accepted quotes credits the payee wallet from the settlement account and is answered with our fulfilment.
 */
@Service
public class TransferService {
    private static final Logger log = LoggerFactory.getLogger(TransferService.class);
    static final String TABLE = "interop_transfer";

    private final Rows rows;
    private final SwitchService switches;
    private final QuoteService quotes;
    private final SwitchGateway gateway;
    private final Downstream downstream;
    private final Events events;
    private final TransactionTemplate tx;

    public TransferService(Rows rows, SwitchService switches, QuoteService quotes, SwitchGateway gateway,
                           Downstream downstream, Events events, TransactionTemplate tx) {
        this.rows = rows;
        this.switches = switches;
        this.quotes = quotes;
        this.gateway = gateway;
        this.downstream = downstream;
        this.events = events;
        this.tx = tx;
    }

    // ------------------------------------------------------------------ outbound

    public ObjectNode createOutbound(JsonNode req, String key) {
        Optional<ObjectNode> existing = rows.queryOne("select row_to_json(t)::text from interop_transfer t where idempotency_key = ?", key);
        if (existing.isPresent()) {
            if ("FAILED".equals(Rows.str(existing.get(), "status"))) {
                throw new ApiException(422, Rows.str(existing.get(), "error_code"), Rows.str(existing.get(), "error_description"));
            }
            return existing.get();
        }
        UUID switchId = Rows.uuid(req, "interop_switch_id");
        ObjectNode sw = switches.requireActive(switchId);
        UUID settlement = Rows.uuid(sw, "settlement_account_id");
        if (settlement == null) {
            throw ApiException.unprocessable("settlement_account_not_configured", "The switch has no settlement account");
        }
        UUID quoteRowId = Rows.uuid(req, "interop_quote_id");
        if (quoteRowId == null) {
            throw ApiException.unprocessable("quote_required", "An outbound transfer needs an accepted interop_quote_id");
        }
        ObjectNode quote = rows.find("interop_quote", quoteRowId).orElseThrow(() ->
                ApiException.unprocessable("quote_not_found", "Quote " + quoteRowId + " does not exist"));
        if (!"OUTBOUND".equals(Rows.str(quote, "direction")) || !switchId.equals(Rows.uuid(quote, "interop_switch_id"))) {
            throw ApiException.unprocessable("quote_mismatch", "The quote is not an outbound quote of this switch");
        }
        if (!"ACCEPTED".equals(Rows.str(quote, "status"))) {
            throw ApiException.unprocessable("quote_not_accepted", "The quote is " + Rows.str(quote, "status"));
        }
        if (rows.scalar("select expires_at < now() from interop_quote where id = ?", Boolean.class, quoteRowId).orElse(true)) {
            throw ApiException.unprocessable("quote_expired", "The quote expired; request a new one");
        }
        UUID payer = Rows.uuid(req, "payer_wallet_id") == null ? Rows.uuid(quote, "payer_wallet_id") : Rows.uuid(req, "payer_wallet_id");
        BigDecimal amount = Rows.dec(req, "amount");
        if (!payer.equals(Rows.uuid(quote, "payer_wallet_id"))
                || amount == null || amount.compareTo(Rows.dec(quote, "amount")) != 0
                || !Rows.str(quote, "currency").equals(Rows.str(req, "currency"))
                || !Rows.str(quote, "payee_fsp_id").equals(Rows.str(req, "counterparty_fsp_id"))) {
            throw ApiException.unprocessable("quote_mismatch",
                    "payer, amount, currency and counterparty_fsp_id must match the quote");
        }
        BigDecimal fee = Optional.ofNullable(Rows.dec(quote, "fee_amount")).orElse(BigDecimal.ZERO);
        BigDecimal total = amount.add(fee);
        UUID id = Ids.newId();
        String transferId = Ids.newId().toString();
        ObjectNode row = tx.execute(s -> {
            ObjectNode locked = rows.lock("interop_switch", switchId); // serialises reservations against the cap
            if (rows.scalar("select count(*) from interop_transfer where interop_quote_id = ?", Long.class, quoteRowId).orElse(0L) > 0) {
                throw ApiException.conflict("quote_already_used", "A transfer already uses this quote");
            }
            SwitchService.Position pos = switches.positionOf(switchId);
            if (!InteropRules.withinCap(Rows.dec(locked, "net_debit_cap"), pos.net(), pos.reservedOutbound(), total)) {
                throw ApiException.unprocessable("net_debit_cap_exceeded", "The transfer would exceed the net debit cap of "
                        + Rows.str(locked, "code") + " (headroom " + InteropRules.headroom(Rows.dec(locked, "net_debit_cap"),
                        pos.net(), pos.reservedOutbound()).toPlainString() + ")");
            }
            if (!InteropRules.withinCap(Rows.dec(locked, "liquidity_limit"), pos.net(), pos.reservedOutbound(), total)) {
                throw ApiException.unprocessable("liquidity_exceeded", "The transfer would exceed the prefunded liquidity of "
                        + Rows.str(locked, "code"));
            }
            ObjectNode v = rows.object();
            v.put("id", id.toString());
            v.put("interop_switch_id", switchId.toString());
            v.put("interop_quote_id", quoteRowId.toString());
            v.put("transfer_id", transferId);
            v.put("direction", "OUTBOUND");
            v.put("payer_wallet_id", payer.toString());
            v.put("counterparty_fsp_id", Rows.str(quote, "payee_fsp_id"));
            v.put("counterparty_party_id", Optional.ofNullable(Rows.str(req, "counterparty_party_id"))
                    .orElse(Rows.str(quote, "payee_party_id")));
            v.put("amount", amount.toPlainString());
            v.put("currency", Rows.str(quote, "currency"));
            v.put("fee_amount", fee.toPlainString());
            v.put("ilp_condition", Rows.str(quote, "ilp_condition"));
            v.put("status", "QUOTED");
            v.put("idempotency_key", key);
            QuoteService.copyCustom(req, v);
            return rows.insert(TABLE, v);
        });
        JsonNode wtx;
        try {
            wtx = downstream.reserve("itr-" + id + "-reserve", id, payer, settlement, total, Rows.str(row, "currency"),
                    "Interop transfer to " + Rows.str(row, "counterparty_fsp_id") + " " + transferId);
        } catch (ApiException e) {
            if (e.status() < 500) {
                finish(id, "FAILED", e.code(), e.getMessage(), null, null);
                throw new ApiException(422, e.code(), e.getMessage());
            }
            throw e;
        }
        ObjectNode reserved = tx.execute(s -> {
            rows.lock(TABLE, id);
            ObjectNode v = rows.object();
            v.put("status", "RESERVED");
            v.put("wallet_transaction_id", wtx.path("id").asString());
            if (wtx.hasNonNull("hold_id")) {
                v.put("hold_id", wtx.get("hold_id").asString());
            }
            rows.update(TABLE, id, v);
            rows.execute("update interop_transfer set expires_at = now() + make_interval(secs => ?) where id = ?",
                    Rows.integer(sw, "transfer_timeout_seconds"), id);
            ObjectNode after = rows.get(TABLE, id);
            events.publish("wallets.interop_transfer.reserved", TABLE, after);
            return after;
        });
        try {
            gateway.prepare(sw, transferId, Rows.str(quote, "quote_id"), Rows.str(quote, "payee_fsp_id"), amount,
                    Rows.str(quote, "currency"), Rows.str(quote, "ilp_condition"));
        } catch (ApiException e) {
            abort(reserved, "SWITCH_UNREACHABLE", e.getMessage());
            throw e;
        }
        return reserved;
    }

    public ObjectNode abortById(UUID id, String reason) {
        ObjectNode t = rows.get(TABLE, id);
        if ("ABORTED".equals(Rows.str(t, "status"))) {
            return t;
        }
        if (!"OUTBOUND".equals(Rows.str(t, "direction"))) {
            throw ApiException.unprocessable("inbound_transfer", "Inbound transfers are controlled by the switch");
        }
        InteropRules.requireTransition(Rows.str(t, "status"), "ABORTED");
        return abort(t, "ABORTED_BY_PAYER", reason);
    }

    /** Releases the reservation (if any) and marks the transfer ABORTED. */
    ObjectNode abort(ObjectNode t, String code, String description) {
        UUID id = Rows.uuid(t, "id");
        UUID wtx = Rows.uuid(t, "wallet_transaction_id");
        if (wtx != null) {
            downstream.cancel("itr-" + id + "-abort", wtx, description == null ? code : description);
        }
        return finish(id, "ABORTED", code, description, null, null);
    }

    /** Moves an open transfer to a final status in one transaction (no-op when it already is final). */
    private ObjectNode finish(UUID id, String status, String code, String description, String fulfilment, UUID walletTxn) {
        return tx.execute(s -> {
            ObjectNode cur = rows.lock(TABLE, id);
            if (InteropRules.isFinal(Rows.str(cur, "status"))) {
                return cur;
            }
            InteropRules.requireTransition(Rows.str(cur, "status"), status);
            ObjectNode v = rows.object();
            v.put("status", status);
            if (code != null) {
                v.put("error_code", code.length() > 32 ? code.substring(0, 32) : code);
            }
            if (description != null) {
                v.put("error_description", description.length() > 255 ? description.substring(0, 255) : description);
            }
            if (fulfilment != null) {
                v.put("fulfilment", fulfilment);
            }
            if (walletTxn != null) {
                v.put("wallet_transaction_id", walletTxn.toString());
            }
            rows.update(TABLE, id, v);
            if ("COMMITTED".equals(status)) {
                rows.execute("update interop_transfer set completed_at = now() where id = ?", id);
            }
            ObjectNode after = rows.get(TABLE, id);
            switch (status) {
                case "COMMITTED" -> events.publish("wallets.interop_transfer.committed", TABLE, after);
                case "ABORTED", "EXPIRED" -> events.publish("wallets.interop_transfer.aborted", TABLE, after);
                default -> { }
            }
            return after;
        });
    }

    /** Aborts reserved transfers past their expiry and expires stale quotes (database clock). */
    public ObjectNode expire() {
        List<ObjectNode> due = rows.query("""
                select row_to_json(t)::text from interop_transfer t
                where t.direction = 'OUTBOUND' and t.status in ('RESERVED', 'QUOTED') and t.expires_at < now()
                order by t.expires_at limit 500""");
        int aborted = 0;
        List<String> failed = new ArrayList<>();
        for (ObjectNode t : due) {
            try {
                abort(t, "EXPIRED", "No fulfilment before the transfer expired");
                aborted++;
            } catch (RuntimeException e) {
                failed.add(Rows.str(t, "transfer_id"));
                log.warn("could not expire transfer {}: {}", Rows.str(t, "transfer_id"), e.getMessage());
            }
        }
        int expiredQuotes = quotes.expireQuotes();
        ObjectNode out = rows.object();
        out.put("transfers_aborted", aborted);
        out.put("quotes_expired", expiredQuotes);
        out.set("failed_transfer_ids", rows.json().valueToTree(failed));
        return out;
    }

    // ------------------------------------------------------------------ switch callbacks

    Optional<ObjectNode> byTransferId(String transferId) {
        return rows.queryOne("select row_to_json(t)::text from interop_transfer t where transfer_id = ?", transferId);
    }

    /** TRANSFER_PREPARE: we are the payee FSP; credit the payee and reveal our fulfilment. Idempotent on transfer_id. */
    public ObjectNode onPrepare(ObjectNode sw, JsonNode p) {
        String transferId = QuoteService.required(p, "transfer_id");
        Optional<ObjectNode> existing = byTransferId(transferId);
        if (existing.isPresent()) {
            return response(existing.get(), true);
        }
        UUID settlement = Rows.uuid(sw, "settlement_account_id");
        if (settlement == null) {
            throw ApiException.unprocessable("settlement_account_not_configured", "The switch has no settlement account");
        }
        String quoteId = QuoteService.required(p, "quote_id");
        ObjectNode quote = quotes.byQuoteId(quoteId)
                .filter(q -> "INBOUND".equals(Rows.str(q, "direction")) && Rows.str(sw, "id").equals(Rows.str(q, "interop_switch_id")))
                .orElseThrow(() -> ApiException.unprocessable("quote_not_found", "No inbound quote " + quoteId));
        BigDecimal amount = new BigDecimal(QuoteService.required(p, "amount"));
        String error = null;
        if (!"ACCEPTED".equals(Rows.str(quote, "status"))) {
            error = "Quote is " + Rows.str(quote, "status");
        } else if (rows.scalar("select expires_at < now() from interop_quote where id = ?", Boolean.class,
                Rows.uuid(quote, "id")).orElse(true)) {
            error = "Quote expired";
        } else if (amount.compareTo(Rows.dec(quote, "amount")) != 0
                || !Rows.str(quote, "currency").equals(Rows.str(p, "currency"))) {
            error = "Amount or currency differ from the quote";
        } else if (p.hasNonNull("ilp_condition") && !Rows.str(quote, "ilp_condition").equals(Rows.str(p, "ilp_condition"))) {
            error = "Condition differs from the quote";
        } else if (rows.scalar("select count(*) from interop_transfer where interop_quote_id = ?", Long.class,
                Rows.uuid(quote, "id")).orElse(0L) > 0) {
            error = "Quote already used by another transfer";
        }
        UUID id = Ids.newId();
        String why = error;
        ObjectNode row = tx.execute(s -> {
            ObjectNode v = rows.object();
            v.put("id", id.toString());
            v.put("interop_switch_id", Rows.str(sw, "id"));
            v.put("interop_quote_id", Rows.str(quote, "id"));
            v.put("transfer_id", transferId);
            v.put("direction", "INBOUND");
            if (quote.hasNonNull("payee_wallet_id")) {
                v.put("payee_wallet_id", Rows.str(quote, "payee_wallet_id"));
            }
            v.put("counterparty_fsp_id", Optional.ofNullable(Rows.str(p, "payer_fsp_id"))
                    .orElse(Optional.ofNullable(Rows.str(quote, "payer_fsp_id")).orElse("unknown")));
            if (p.hasNonNull("payer_party_id")) {
                v.put("counterparty_party_id", Rows.str(p, "payer_party_id"));
            }
            v.put("amount", amount.toPlainString());
            v.put("currency", Rows.str(p, "currency"));
            v.put("fee_amount", "0");
            v.put("ilp_condition", Rows.str(quote, "ilp_condition"));
            v.put("status", "RESERVED");
            v.put("idempotency_key", "switch:" + Rows.str(sw, "code") + ":" + transferId);
            ObjectNode r = rows.insert(TABLE, v);
            rows.execute("update interop_transfer set expires_at = now() + make_interval(secs => ?) where id = ?",
                    Rows.integer(sw, "transfer_timeout_seconds"), id);
            return r;
        });
        if (why != null) {
            return response(finish(id, "ABORTED", "PREPARE_REJECTED", why, null, null), true);
        }
        JsonNode wtx;
        try {
            wtx = downstream.credit("itr-in-" + transferId, id, Rows.uuid(quote, "payee_wallet_id"), settlement, amount,
                    Rows.str(quote, "currency"), "Interop transfer from " + Rows.str(row, "counterparty_fsp_id") + " " + transferId);
        } catch (ApiException e) {
            if (e.status() >= 500) {
                throw e; // outcome unknown: the switch retries the prepare, the credit key makes it idempotent
            }
            return response(finish(id, "ABORTED", e.code(), e.getMessage(), null, null), true);
        }
        ObjectNode done = finish(id, "COMMITTED", null, null, Rows.str(quote, "fulfilment"),
                UUID.fromString(wtx.path("id").asString()));
        return response(done, true);
    }

    /** TRANSFER_FULFIL for our outbound transfer: the fulfilment must hash to the condition. */
    public ObjectNode onFulfil(ObjectNode sw, JsonNode p) {
        ObjectNode t = outbound(sw, p);
        if (InteropRules.isFinal(Rows.str(t, "status"))) {
            return response(t, false);
        }
        String state = Rows.str(p, "transfer_state");
        if (state != null && !"COMMITTED".equals(state)) {
            return response(abort(t, "SWITCH_" + state, "Switch reported " + state), false);
        }
        if (rows.scalar("select expires_at < now() from interop_transfer where id = ?", Boolean.class, Rows.uuid(t, "id"))
                .orElse(false)) {
            abort(t, "EXPIRED", "Fulfilment arrived after the transfer expired");
            throw ApiException.unprocessable("transfer_expired", "The transfer expired before the fulfilment arrived");
        }
        String fulfilment = QuoteService.required(p, "fulfilment");
        if (!InteropCrypto.verify(fulfilment, Rows.str(t, "ilp_condition"))) {
            abort(t, "INVALID_FULFILMENT", "The fulfilment does not match the condition");
            throw ApiException.unprocessable("invalid_fulfilment", "The fulfilment does not match the transfer condition");
        }
        downstream.confirm("itr-" + Rows.str(t, "id") + "-commit", Rows.uuid(t, "wallet_transaction_id"));
        return response(finish(Rows.uuid(t, "id"), "COMMITTED", null, null, fulfilment, null), false);
    }

    /** TRANSFER_ERROR for our outbound transfer: release the reservation. */
    public ObjectNode onError(ObjectNode sw, JsonNode p) {
        ObjectNode t = outbound(sw, p);
        if (InteropRules.isFinal(Rows.str(t, "status"))) {
            return response(t, false);
        }
        return response(abort(t, Optional.ofNullable(Rows.str(p, "error_code")).orElse("SWITCH_ERROR"),
                Optional.ofNullable(Rows.str(p, "error_description")).orElse("Rejected by the switch")), false);
    }

    private ObjectNode outbound(ObjectNode sw, JsonNode p) {
        String transferId = QuoteService.required(p, "transfer_id");
        return byTransferId(transferId)
                .filter(x -> "OUTBOUND".equals(Rows.str(x, "direction")) && Rows.str(sw, "id").equals(Rows.str(x, "interop_switch_id")))
                .orElseThrow(() -> ApiException.notFound("interop transfer", transferId));
    }

    private ObjectNode response(JsonNode t, boolean revealFulfilment) {
        ObjectNode out = rows.object();
        out.put("transfer_id", Rows.str(t, "transfer_id"));
        out.put("status", Rows.str(t, "status"));
        if (revealFulfilment && "COMMITTED".equals(Rows.str(t, "status"))) {
            out.put("fulfilment", Rows.str(t, "fulfilment"));
        }
        if (t.hasNonNull("error_code")) {
            out.put("error_code", Rows.str(t, "error_code"));
            out.put("error_description", Rows.str(t, "error_description"));
        }
        if (t.hasNonNull("completed_at")) {
            out.put("completed_at", Rows.str(t, "completed_at"));
        }
        return out;
    }
}
