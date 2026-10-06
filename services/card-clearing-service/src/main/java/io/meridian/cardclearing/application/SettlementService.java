package io.meridian.cardclearing.application;

import io.meridian.cardclearing.config.ClearingProperties;
import io.meridian.cardclearing.domain.Settlement;
import io.meridian.cardclearing.infrastructure.Clients;
import io.meridian.cardclearing.infrastructure.Clients.Leg;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Scheme settlement positions per (scheme, program, settlement date, currency). Sign convention: net_amount =
 * purchases - refunds - chargebacks - interchange + scheme fees; positive = the issuer pays the scheme.
 */
@Service
public class SettlementService {
    private final ClearingStore store;
    private final JdbcClient jdbc;
    private final Events events;
    private final Clients clients;
    private final ClearingProperties props;
    private final TransactionTemplate tx;

    public SettlementService(ClearingStore store, JdbcClient jdbc, Events events, Clients clients,
                             ClearingProperties props, TransactionTemplate tx) {
        this.store = store;
        this.jdbc = jdbc;
        this.events = events;
        this.clients = clients;
        this.props = props;
        this.tx = tx;
    }

    /**
     * Adds a file's totals to the PENDING position (creating it); returns a problem text instead when the
     * position was already confirmed. Runs in the caller's transaction.
     */
    String accumulate(String scheme, UUID programId, LocalDate date, String currency, Settlement.Totals t) {
        Optional<String> existing = jdbc.sql("""
                select row_to_json(s)::text from card_settlement_record s
                where scheme = ? and card_program_id is not distinct from ?::uuid and settlement_date = ? and currency = ?
                for update""")
                .params(scheme, programId == null ? null : programId.toString(), date, currency)
                .query(String.class).optional();
        JsonNode row;
        if (existing.isEmpty()) {
            ObjectNode v = store.json().createObjectNode();
            v.put("scheme", scheme);
            ClearingStore.put(v, "card_program_id", programId);
            v.put("settlement_date", date.toString());
            v.put("currency", currency);
            fill(v, t.count, t.purchases, t.refunds, t.chargebacks, t.interchange, t.schemeFees);
            row = store.insert("card_settlement_record", v);
        } else {
            JsonNode cur = store.json().readTree(existing.get());
            if (!"PENDING".equals(ClearingStore.text(cur, "status"))) {
                return "settlement " + ClearingStore.text(cur, "id") + " (" + scheme + " " + date + " " + currency
                        + ") is " + ClearingStore.text(cur, "status") + "; the file's amounts were not added";
            }
            ObjectNode v = store.json().createObjectNode();
            fill(v, cur.get("transaction_count").asInt() + t.count,
                    dec(cur, "gross_purchases").add(t.purchases), dec(cur, "gross_refunds").add(t.refunds),
                    dec(cur, "chargebacks").add(t.chargebacks), dec(cur, "interchange").add(t.interchange),
                    dec(cur, "scheme_fees").add(t.schemeFees));
            row = store.update("card_settlement_record", UUID.fromString(ClearingStore.text(cur, "id")), v);
        }
        events.publish("cards.card_settlement.calculated", "card_settlement_record", row);
        return null;
    }

    /** PENDING -> CONFIRMED after the bank statement matched; posts the net settlement against the nostro. */
    public JsonNode confirm(UUID id, String reconReference) {
        JsonNode s = store.row("card_settlement_record", id);
        if (!"PENDING".equals(ClearingStore.text(s, "status"))) {
            throw ApiException.conflict("invalid_transition",
                    "Only PENDING settlements can be confirmed (settlement is " + ClearingStore.text(s, "status") + ")");
        }
        String scheme = ClearingStore.text(s, "scheme");
        String currency = ClearingStore.text(s, "currency");
        BigDecimal net = dec(s, "net_amount");
        UUID journal = null;
        if (net.signum() != 0) {
            UUID settle = props.clearing().settlementAccount(scheme);
            if (settle == null) {
                throw new ApiException(500, "settlement_account_not_configured",
                        "No settlement account is configured for scheme " + scheme);
            }
            UUID nostro = props.clearing().nostroAccount();
            BigDecimal abs = net.abs();
            List<Leg> legs = net.signum() > 0
                    ? List.of(new Leg(settle, "DEBIT", abs, "Scheme settlement paid"), new Leg(nostro, "CREDIT", abs, "Nostro"))
                    : List.of(new Leg(nostro, "DEBIT", abs, "Nostro"), new Leg(settle, "CREDIT", abs, "Scheme settlement received"));
            journal = clients.post(props.ledgerId(), "CARD_SETTLEMENT", scheme + " settlement "
                    + ClearingStore.text(s, "settlement_date"), currency, "CARD_SETTLEMENT", id, reconReference, legs,
                    "settlement-confirm:" + id);
        }
        UUID j = journal;
        return tx.execute(st -> {
            int n = jdbc.sql("""
                    update card_settlement_record set status = 'CONFIRMED', recon_reference = ?, confirmed_at = now(),
                      confirmed_by = ?, journal_entry_id = ?, updated_by = ?
                    where id = ? and status = 'PENDING'""")
                    .params(reconReference, TenantContext.principal(), j, TenantContext.principal(), id).update();
            if (n == 0) {
                throw ApiException.conflict("invalid_transition", "The settlement changed concurrently");
            }
            JsonNode row = store.row("card_settlement_record", id);
            events.publish("cards.card_settlement.confirmed", "card_settlement_record", row);
            return row;
        });
    }

    private static void fill(ObjectNode v, int count, BigDecimal purchases, BigDecimal refunds, BigDecimal chargebacks,
                             BigDecimal interchange, BigDecimal schemeFees) {
        v.put("transaction_count", count);
        v.put("gross_purchases", purchases.toPlainString());
        v.put("gross_refunds", refunds.toPlainString());
        v.put("chargebacks", chargebacks.toPlainString());
        v.put("interchange", interchange.toPlainString());
        v.put("scheme_fees", schemeFees.toPlainString());
        v.put("net_amount", Settlement.net(purchases, refunds, chargebacks, interchange, schemeFees).toPlainString());
    }

    private static BigDecimal dec(JsonNode n, String f) {
        BigDecimal d = ClearingStore.dec(n, f);
        return d == null ? BigDecimal.ZERO : d;
    }
}
