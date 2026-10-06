package io.meridian.treasurydeal.application;

import io.meridian.common.error.ApiException;
import io.meridian.treasurydeal.domain.Lifecycle;
import io.meridian.treasurydeal.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Settlement instructions of deal cash flows: created when a deal leg settles or matures, released as payments
 * through payment-service (pay flows), confirmed from the nostro statement or failed. Also projects the contractual
 * cash flows of live deals for liquidity-service.
 */
@Service
public class SettlementService {
    static final String TABLE = "treasury_settlement";

    private final Rows rows;
    private final Events events;
    private final CounterpartyService counterparties;
    private final Ports.Payments payments;

    public SettlementService(Rows rows, Events events, CounterpartyService counterparties, Ports.Payments payments) {
        this.rows = rows;
        this.events = events;
        this.counterparties = counterparties;
        this.payments = payments;
    }

    /** A cash flow to instruct: pay flows (direction CREDIT on our nostro) need a verified counterparty SSI. */
    public record Flow(String flowType, String direction, BigDecimal amount, String currency, LocalDate valueDate) {
        public static Flow pay(String type, BigDecimal amount, String currency, LocalDate valueDate) {
            return new Flow(type, "CREDIT", amount, currency, valueDate);
        }

        public static Flow receive(String type, BigDecimal amount, String currency, LocalDate valueDate) {
            return new Flow(type, "DEBIT", amount, currency, valueDate);
        }
    }

    /** Creates the settlement rows of a deal event (idempotent per deal, flow type and value date). */
    public void create(String dealType, UUID dealId, UUID counterpartyId, UUID nostroAccountId, UUID journalEntryId,
                       java.util.List<Flow> flows) {
        for (Flow f : flows) {
            if (f.amount().signum() == 0) {
                continue;
            }
            var ssi = counterparties.ssiFor(counterpartyId, f.currency(), f.valueDate());
            if (ssi.isEmpty() && "CREDIT".equals(f.direction())) {
                throw ApiException.unprocessable("ssi_missing", "No verified standing settlement instruction for "
                        + f.currency() + " on " + f.valueDate() + "; capture and verify an SSI first");
            }
            if (rows.scalar("select count(*) from treasury_settlement where deal_id = ? and flow_type = ? and value_date = ?",
                    Integer.class, dealId, f.flowType(), f.valueDate()).orElse(0) > 0) {
                continue;
            }
            ObjectNode v = rows.object();
            v.put("deal_type", dealType);
            v.put("deal_id", dealId.toString());
            v.put("flow_type", f.flowType());
            v.put("direction", f.direction());
            v.put("amount", f.amount().toPlainString());
            v.put("currency", f.currency());
            v.put("value_date", f.valueDate().toString());
            v.put("status", "PENDING");
            ssi.ifPresent(s -> {
                v.put("treasury_ssi_id", Rows.str(s, "id"));
                if (nostroAccountId == null && Rows.str(s, "our_nostro_account_id") != null) {
                    v.put("nostro_account_id", Rows.str(s, "our_nostro_account_id"));
                }
            });
            if (nostroAccountId != null) {
                v.put("nostro_account_id", nostroAccountId.toString());
            }
            if (journalEntryId != null) {
                v.put("journal_entry_id", journalEntryId.toString());
            }
            rows.insert(TABLE, v);
        }
    }

    /** Releases a pay flow as an outgoing payment; a receive flow is marked as awaited (INSTRUCTED, no payment). */
    @Transactional
    public ObjectNode instruct(UUID id) {
        ObjectNode s = rows.lock(TABLE, id);
        Support.transition(Lifecycle.SETTLEMENT, s, "INSTRUCTED", "settlement");
        ObjectNode v = rows.object();
        v.put("status", "INSTRUCTED");
        v.put("instructed_at", rows.now());
        v.putNull("failure_reason");
        if ("CREDIT".equals(Rows.str(s, "direction"))) {
            UUID ssiId = Rows.uuid(s, "treasury_ssi_id");
            if (ssiId == null) {
                throw ApiException.unprocessable("ssi_missing", "The settlement has no standing settlement instruction");
            }
            ObjectNode ssi = rows.get(CounterpartyService.SSI, ssiId);
            if (Rows.str(ssi, "verified_by") == null) {
                throw ApiException.unprocessable("ssi_not_verified", "The SSI must be verified before payments are released");
            }
            ObjectNode cp = rows.get(CounterpartyService.TABLE, Rows.uuid(ssi, "treasury_counterparty_id"));
            String ref = dealRef(Rows.str(s, "deal_type"), Rows.uuid(s, "deal_id"));
            String e2e = (ref + "-" + Rows.str(s, "flow_type")).replace("_", "");
            UUID payment = payments.create(new Ports.PaymentOrder(
                    "treasury-settlement:" + id + ":" + Rows.integer(s, "row_version"), Rows.str(cp, "name"),
                    Rows.str(ssi, "beneficiary_account"), Rows.str(ssi, "beneficiary_bic"), Rows.str(ssi, "intermediary_bic"),
                    Rows.str(cp, "country_code"), Rows.dec(s, "amount"), Rows.str(s, "currency"), Rows.date(s, "value_date"),
                    e2e.length() > 35 ? e2e.substring(0, 35) : e2e, ref, null));
            v.put("payment_id", payment.toString());
        }
        ObjectNode out = rows.update(TABLE, id, v);
        events.publish("treasury.settlement.instructed", TABLE, out);
        return out;
    }

    @Transactional
    public ObjectNode markSettled(UUID id, String reference) {
        ObjectNode s = rows.lock(TABLE, id);
        Support.transition(Lifecycle.SETTLEMENT, s, "SETTLED", "settlement");
        ObjectNode v = rows.object();
        v.put("status", "SETTLED");
        v.put("settled_at", rows.now());
        if (reference != null) {
            v.put("settlement_reference", reference);
        }
        ObjectNode out = rows.update(TABLE, id, v);
        events.publish("treasury.settlement.settled", TABLE, out);
        return out;
    }

    @Transactional
    public ObjectNode fail(UUID id, String reason) {
        Support.required(reason, "reason");
        ObjectNode s = rows.lock(TABLE, id);
        Support.transition(Lifecycle.SETTLEMENT, s, "FAILED", "settlement");
        ObjectNode v = rows.object();
        v.put("status", "FAILED");
        v.put("failure_reason", reason);
        ObjectNode out = rows.update(TABLE, id, v);
        events.publish("treasury.settlement.failed", TABLE, out);
        return out;
    }

    /** Cancels the settlements of a deal that have not been released. */
    public int cancelOpen(UUID dealId) {
        return rows.execute("update treasury_settlement set status = 'CANCELLED' where deal_id = ? and status = 'PENDING'",
                dealId);
    }

    private String dealRef(String dealType, UUID dealId) {
        String table = switch (dealType) {
            case "MONEY_MARKET" -> "money_market_deal";
            case "FX" -> "treasury_fx_deal";
            default -> "securities_trade";
        };
        return rows.find(table, dealId).map(d -> Rows.str(d, "deal_ref")).orElse(dealId.toString().substring(0, 8));
    }

    // ------------------------------------------------------------------ projection for liquidity-service

    /**
     * Contractual cash flows with value dates in [from, to]: open settlement rows plus flows of live deals not yet
     * instructed (money market start and maturity legs, unsettled FX legs, fixed NDFs, pending securities trades).
     */
    @Transactional(readOnly = true)
    public ObjectNode projectedFlows(LocalDate from, LocalDate to, UUID legalEntityId, String currency) {
        if (to.isBefore(from)) {
            throw ApiException.unprocessable("invalid_dates", "to must not be before from");
        }
        if (java.time.temporal.ChronoUnit.DAYS.between(from, to) > 3660) {
            throw ApiException.unprocessable("range_too_large", "The projection range is limited to ten years");
        }
        ObjectNode out = rows.object();
        out.put("from", from.toString());
        out.put("to", to.toString());
        ArrayNode flows = out.putArray("flows");
        String ent = legalEntityId == null ? null : legalEntityId.toString();
        // open settlement instructions
        rows.query("""
                select json_build_object('value_date', s.value_date, 'currency', s.currency,
                  'direction', case when s.direction = 'DEBIT' then 'IN' else 'OUT' end, 'amount', s.amount,
                  'category', case when s.deal_type = 'SECURITY' then 'SECURITIES' else 'TREASURY_DEALS' end,
                  'deal_type', s.deal_type, 'deal_id', s.deal_id, 'flow_type', s.flow_type,
                  'nostro_account_id', s.nostro_account_id, 'source', 'SETTLEMENT',
                  'legal_entity_id', coalesce(m.legal_entity_id, f.legal_entity_id, t.legal_entity_id))::text
                from treasury_settlement s
                left join money_market_deal m on s.deal_type = 'MONEY_MARKET' and m.id = s.deal_id
                left join treasury_fx_deal f on s.deal_type = 'FX' and f.id = s.deal_id
                left join securities_trade t on s.deal_type = 'SECURITY' and t.id = s.deal_id
                where s.status in ('PENDING', 'INSTRUCTED', 'FAILED') and s.value_date between ? and ?
                  and (?::uuid is null or coalesce(m.legal_entity_id, f.legal_entity_id, t.legal_entity_id) = ?::uuid)
                  and (?::text is null or s.currency = ?)""", from, to, ent, ent, currency, currency).forEach(flows::add);
        // money market start legs not yet settled
        rows.query("""
                select json_build_object('value_date', value_date, 'currency', currency,
                  'direction', case when deal_type in ('PLACEMENT','CALL_PLACEMENT','REVERSE_REPO','CD_PURCHASED')
                                    then 'OUT' else 'IN' end,
                  'amount', principal, 'category', 'TREASURY_DEALS', 'deal_type', 'MONEY_MARKET', 'deal_id', id,
                  'flow_type', case when deal_type in ('PLACEMENT','CALL_PLACEMENT','REVERSE_REPO','CD_PURCHASED')
                                    then 'PRINCIPAL_OUT' else 'PRINCIPAL_IN' end,
                  'nostro_account_id', nostro_account_id, 'source', 'DEAL', 'legal_entity_id', legal_entity_id)::text
                from money_market_deal where status in ('CAPTURED', 'VERIFIED', 'CONFIRMED') and rolled_from_deal_id is null
                  and value_date between ? and ? and (?::uuid is null or legal_entity_id = ?::uuid)
                  and (?::text is null or currency = ?)""", from, to, ent, ent, currency, currency).forEach(flows::add);
        // money market maturities (principal + interest) of live deals
        rows.query("""
                select json_build_object('value_date', maturity_date, 'currency', currency,
                  'direction', case when deal_type in ('PLACEMENT','CALL_PLACEMENT','REVERSE_REPO','CD_PURCHASED')
                                    then 'IN' else 'OUT' end,
                  'amount', principal + coalesce(interest_amount, 0), 'category', 'TREASURY_DEALS',
                  'deal_type', 'MONEY_MARKET', 'deal_id', id, 'flow_type', 'MATURITY',
                  'nostro_account_id', nostro_account_id, 'source', 'DEAL', 'legal_entity_id', legal_entity_id)::text
                from money_market_deal where status in ('CAPTURED', 'VERIFIED', 'CONFIRMED', 'SETTLED')
                  and maturity_date between ? and ? and (?::uuid is null or legal_entity_id = ?::uuid)
                  and (?::text is null or currency = ?)""", from, to, ent, ent, currency, currency).forEach(flows::add);
        // unsettled deliverable FX legs: receive one currency, pay the other
        rows.query("""
                select json_build_object('value_date', l.value_date, 'currency', x.ccy, 'direction', x.dir,
                  'amount', x.amt, 'category', 'TREASURY_DEALS', 'deal_type', 'FX', 'deal_id', d.id,
                  'flow_type', case when x.dir = 'IN' then 'FX_RECEIVE' else 'FX_PAY' end,
                  'nostro_account_id', null, 'source', 'DEAL', 'legal_entity_id', d.legal_entity_id)::text
                from treasury_fx_deal d join treasury_fx_deal_leg l on l.treasury_fx_deal_id = d.id
                cross join lateral (values
                    (l.base_currency, case when l.buy_sell = 'BUY' then 'IN' else 'OUT' end, l.base_amount),
                    (l.quote_currency, case when l.buy_sell = 'BUY' then 'OUT' else 'IN' end, l.quote_amount)) x(ccy, dir, amt)
                where d.status in ('CAPTURED', 'VERIFIED', 'CONFIRMED') and not d.non_deliverable
                  and l.settlement_status = 'PENDING' and l.value_date between ? and ?
                  and (?::uuid is null or d.legal_entity_id = ?::uuid) and (?::text is null or x.ccy = ?)""",
                from, to, ent, ent, currency, currency).forEach(flows::add);
        // fixed NDFs not yet settled: net amount in the settlement currency
        rows.query("""
                select json_build_object('value_date', l.value_date, 'currency', d.settlement_currency,
                  'direction', case when d.ndf_settlement_amount >= 0 then 'IN' else 'OUT' end,
                  'amount', abs(d.ndf_settlement_amount), 'category', 'TREASURY_DEALS', 'deal_type', 'FX',
                  'deal_id', d.id, 'flow_type', case when d.ndf_settlement_amount >= 0 then 'NDF_RECEIVE' else 'NDF_PAY' end,
                  'nostro_account_id', null, 'source', 'DEAL', 'legal_entity_id', d.legal_entity_id)::text
                from treasury_fx_deal d join treasury_fx_deal_leg l on l.treasury_fx_deal_id = d.id
                where d.non_deliverable and d.ndf_settlement_amount is not null and d.ndf_settlement_amount <> 0
                  and d.status in ('VERIFIED', 'CONFIRMED') and l.settlement_status = 'PENDING'
                  and l.value_date between ? and ? and (?::uuid is null or d.legal_entity_id = ?::uuid)
                  and (?::text is null or d.settlement_currency = ?)""", from, to, ent, ent, currency, currency)
                .forEach(flows::add);
        // pending securities trades
        rows.query("""
                select json_build_object('value_date', settlement_date, 'currency', currency,
                  'direction', case when buy_sell = 'BUY' then 'OUT' else 'IN' end, 'amount', settlement_amount,
                  'category', 'SECURITIES', 'deal_type', 'SECURITY', 'deal_id', id,
                  'flow_type', case when buy_sell = 'BUY' then 'SECURITY_CASH_PAY' else 'SECURITY_CASH_RECEIVE' end,
                  'nostro_account_id', null, 'source', 'DEAL', 'legal_entity_id', legal_entity_id)::text
                from securities_trade where status = 'PENDING' and settlement_date between ? and ?
                  and (?::uuid is null or legal_entity_id = ?::uuid) and (?::text is null or currency = ?)""",
                from, to, ent, ent, currency, currency).forEach(flows::add);
        // totals per value date and currency
        Map<String, BigDecimal[]> totals = new TreeMap<>();
        flows.forEach(f -> {
            BigDecimal[] t = totals.computeIfAbsent(Rows.str(f, "value_date") + "|" + Rows.str(f, "currency"),
                    k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            int i = "IN".equals(Rows.str(f, "direction")) ? 0 : 1;
            t[i] = t[i].add(Rows.dec(f, "amount"));
        });
        ArrayNode tot = out.putArray("totals");
        totals.forEach((k, t) -> {
            ObjectNode n = tot.addObject();
            n.put("value_date", k.split("\\|")[0]);
            n.put("currency", k.split("\\|")[1]);
            n.put("inflows", t[0].toPlainString());
            n.put("outflows", t[1].toPlainString());
            n.put("net", t[0].subtract(t[1]).toPlainString());
        });
        out.put("count", flows.size());
        return out;
    }
}
