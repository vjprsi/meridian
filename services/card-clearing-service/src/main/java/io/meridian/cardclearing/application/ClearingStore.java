package io.meridian.cardclearing.application;

import io.meridian.cardclearing.application.RecordProcessor.Fee;
import io.meridian.cardclearing.application.RecordProcessor.Outcome;
import io.meridian.cardclearing.domain.ParsedRecord;
import io.meridian.common.error.ApiException;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Row access for clearing tables (generic inserts through CrudEngine, a few explicit queries). */
@Component
public class ClearingStore {
    private final CrudEngine crud;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Events events;

    public ClearingStore(CrudEngine crud, JdbcClient jdbc, JsonMapper json, Events events) {
        this.crud = crud;
        this.jdbc = jdbc;
        this.json = json;
        this.events = events;
    }

    public JsonMapper json() {
        return json;
    }

    public JsonNode row(String table, UUID id) {
        return crud.findOne(table, Map.of("id", id)).map(json::readTree)
                .orElseThrow(() -> ApiException.notFound(table.replace('_', ' '), id));
    }

    public JsonNode insert(String table, ObjectNode values) {
        return json.readTree(crud.insert(table, values));
    }

    public JsonNode update(String table, UUID id, ObjectNode values) {
        return crud.updateRow(table, Map.of("id", id), values, null).map(json::readTree)
                .orElseThrow(() -> ApiException.notFound(table.replace('_', ' '), id));
    }

    /** Inserts the clearing record and its fee rows and publishes their events (caller's transaction). */
    public JsonNode insertRecord(UUID fileId, String scheme, ParsedRecord r, Outcome o, UUID resolves) {
        ObjectNode v = json.createObjectNode();
        v.put("id", o.recordId.toString());
        v.put("clearing_file_id", fileId.toString());
        v.put("record_type", r.recordType());
        v.put("scheme", scheme);
        put(v, "card_id", o.cardId);
        put(v, "card_program_id", o.programId);
        put(v, "card_authorization_id", o.authId);
        put(v, "hold_id", o.holdId);
        v.put("match_status", o.matchStatus);
        put(v, "match_rule", o.matchRule);
        put(v, "force_matched_from_id", resolves);
        put(v, "network_transaction_id", r.networkTransactionId());
        put(v, "arn", r.arn());
        put(v, "rrn", r.rrn());
        put(v, "auth_code", r.authCode());
        put(v, "merchant_id", r.merchantId());
        put(v, "merchant_name", r.merchantName());
        put(v, "mcc", r.mcc());
        put(v, "merchant_country", r.merchantCountry());
        v.put("transaction_date", r.transactionDate().toString());
        put(v, "transaction_amount", r.transactionAmount());
        v.put("transaction_currency", r.transactionCurrency());
        put(v, "billing_amount", r.billingAmount());
        v.put("billing_currency", r.billingCurrency());
        put(v, "settlement_amount", r.settlementAmount());
        v.put("settlement_currency", r.settlementCurrency());
        put(v, "authorized_amount", o.authorized);
        put(v, "adjustment_amount", o.adjustment);
        put(v, "interchange_amount", o.interchange);
        put(v, "interchange_rate_code", r.interchangeRateCode());
        put(v, "scheme_fee_amount", r.schemeFeeAmount());
        put(v, "journal_entry_id", o.journalId);
        if (o.exceptionReason != null) {
            v.put("exception_reason", o.exceptionReason.length() > 255 ? o.exceptionReason.substring(0, 255)
                    : o.exceptionReason);
        }
        ObjectNode raw = v.putObject("raw");
        r.raw().forEach(raw::put);
        JsonNode rec = insert("clearing_record", v);
        if (o.posted() && o.journalId != null) {
            events.publish("cards.clearing_record.posted", "clearing_record", rec);
        } else if ("UNMATCHED".equals(o.matchStatus)) {
            events.publish("cards.clearing_record.unmatched", "clearing_record", rec);
        }
        for (Fee f : o.fees) {
            ObjectNode fv = json.createObjectNode();
            fv.put("id", f.id().toString());
            fv.put("card_id", o.cardId.toString());
            put(fv, "ledger_account_id", f.ledgerAccountId());
            fv.put("fee_type", f.feeType());
            put(fv, "amount", f.amount());
            fv.put("currency", f.currency());
            put(fv, "card_authorization_id", o.authId);
            fv.put("clearing_record_id", o.recordId.toString());
            put(fv, "journal_entry_id", f.journalEntryId());
            put(fv, "description", f.description());
            JsonNode fee = insert("card_fee", fv);
            if (f.charged()) {
                events.publish("cards.card_fee.charged", "card_fee", fee);
            }
        }
        return rec;
    }

    public List<JsonNode> recordsOfFile(UUID fileId) {
        return jdbc.sql("select row_to_json(t)::text from clearing_record t where clearing_file_id = ? order by id")
                .param(fileId).query(String.class).list().stream().map(json::readTree).toList();
    }

    public boolean resolved(UUID recordId) {
        return jdbc.sql("select count(*) from clearing_record where force_matched_from_id = ?").param(recordId)
                .query(Long.class).single() > 0;
    }

    /** Rebuilds the parsed form of a stored record (for force match and reprocess). */
    public static ParsedRecord parsed(JsonNode rec) {
        Map<String, String> raw = new LinkedHashMap<>();
        JsonNode r = rec.get("raw");
        if (r != null && r.isObject()) {
            Iterator<String> it = r.propertyNames().iterator();
            while (it.hasNext()) {
                String k = it.next();
                raw.put(k, r.get(k).asString());
            }
        }
        return new ParsedRecord(text(rec, "record_type"), uuid(rec, "card_id"), text(rec, "network_transaction_id"),
                text(rec, "arn"), text(rec, "rrn"), text(rec, "auth_code"), text(rec, "merchant_id"),
                text(rec, "merchant_name"), text(rec, "mcc"), text(rec, "merchant_country"),
                LocalDate.parse(text(rec, "transaction_date")), dec(rec, "transaction_amount"),
                text(rec, "transaction_currency"), dec(rec, "billing_amount"), text(rec, "billing_currency"),
                dec(rec, "settlement_amount"), text(rec, "settlement_currency"), dec(rec, "interchange_amount"),
                text(rec, "interchange_rate_code"), dec(rec, "scheme_fee_amount"), raw);
    }

    static void put(ObjectNode n, String f, Object v) {
        if (v == null) {
            return;
        }
        if (v instanceof BigDecimal d) {
            n.put(f, d.toPlainString());
        } else {
            n.put(f, v.toString());
        }
    }

    static String text(JsonNode n, String f) {
        JsonNode v = n == null ? null : n.get(f);
        return v == null || v.isNull() ? null : v.asString();
    }

    static UUID uuid(JsonNode n, String f) {
        String s = text(n, f);
        return s == null ? null : UUID.fromString(s);
    }

    static BigDecimal dec(JsonNode n, String f) {
        String s = text(n, f);
        return s == null ? null : new BigDecimal(s);
    }
}
