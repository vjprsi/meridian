package io.meridian.treasurydeal.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.treasurydeal.domain.Identifiers;
import io.meridian.treasurydeal.domain.Lifecycle;
import io.meridian.treasurydeal.infrastructure.Rows;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Treasury counterparties (maker-checker onboarding, suspension) and their standing settlement instructions. */
@Service
public class CounterpartyService {
    static final String TABLE = "treasury_counterparty";
    static final String SSI = "treasury_ssi";

    private final Rows rows;
    private final Events events;

    public CounterpartyService(Rows rows, Events events) {
        this.rows = rows;
        this.events = events;
    }

    @Transactional
    public ObjectNode create(JsonNode req) {
        validate(req);
        if (rows.scalar("select count(*) from treasury_counterparty where code = ?", Integer.class,
                Rows.str(req, "code")).orElse(0) > 0) {
            throw ApiException.conflict("duplicate_code", "A counterparty with code " + Rows.str(req, "code") + " exists");
        }
        UUID parent = Rows.uuid(req, "parent_counterparty_id");
        if (parent != null) {
            rows.get(TABLE, parent);
        }
        ObjectNode v = rows.columns(TABLE, req);
        v.put("status", "PENDING_APPROVAL");
        ObjectNode out = rows.insert(TABLE, v);
        events.publish("treasury.counterparty.created", TABLE, out);
        return out;
    }

    /** Validates identifiers of a create or a PATCH body (fields present only). */
    public static void validate(JsonNode req) {
        String bic = Rows.str(req, "bic");
        if (bic != null && !Identifiers.isValidBic(bic)) {
            throw ApiException.unprocessable("invalid_bic", "bic " + bic + " is not a valid ISO 9362 BIC");
        }
        String lei = Rows.str(req, "lei");
        if (lei != null && !Identifiers.isValidLei(lei)) {
            throw ApiException.unprocessable("invalid_lei", "lei " + lei + " fails the ISO 17442 check digits");
        }
        String country = Rows.str(req, "country_code");
        if (country != null && !java.util.Locale.getISOCountries(java.util.Locale.IsoCountryCode.PART1_ALPHA2).contains(country)) {
            throw ApiException.unprocessable("invalid_country", "country_code " + country + " is not ISO 3166");
        }
    }

    @Transactional
    public ObjectNode approve(UUID id) {
        ObjectNode cp = rows.lock(TABLE, id);
        Support.transition(Lifecycle.COUNTERPARTY, cp, "ACTIVE", "counterparty");
        Support.fourEyes(cp, Rows.str(cp, "updated_by"));
        ObjectNode v = rows.object();
        v.put("status", "ACTIVE");
        v.put("approved_by", TenantContext.principal());
        v.put("approved_at", rows.now());
        ObjectNode out = rows.update(TABLE, id, v);
        events.publish("treasury.counterparty.approved", TABLE, out);
        return out;
    }

    @Transactional
    public ObjectNode suspend(UUID id, String reason) {
        return status(id, "SUSPENDED", reason, "treasury.counterparty.suspended");
    }

    @Transactional
    public ObjectNode reactivate(UUID id, String reason) {
        ObjectNode cp = rows.get(TABLE, id);
        if (!"SUSPENDED".equals(Rows.str(cp, "status"))) {
            throw ApiException.conflict("invalid_transition", "Only a SUSPENDED counterparty can be reactivated");
        }
        return status(id, "ACTIVE", reason, "treasury.counterparty.reactivated");
    }

    private ObjectNode status(UUID id, String to, String reason, String event) {
        Support.required(reason, "reason");
        ObjectNode cp = rows.lock(TABLE, id);
        Support.transition(Lifecycle.COUNTERPARTY, cp, to, "counterparty");
        ObjectNode v = rows.object();
        v.put("status", to);
        v.put("status_reason", reason);
        ObjectNode out = rows.update(TABLE, id, v);
        events.publish(event, TABLE, out);
        return out;
    }

    /** The counterparty for dealing: must exist and be ACTIVE. */
    public ObjectNode requireActive(UUID id) {
        ObjectNode cp = rows.find(TABLE, id).orElseThrow(() -> ApiException.unprocessable("unknown_counterparty",
                "treasury counterparty " + id + " does not exist"));
        if (!"ACTIVE".equals(Rows.str(cp, "status"))) {
            throw ApiException.unprocessable("counterparty_not_active",
                    "Counterparty " + Rows.str(cp, "code") + " is " + Rows.str(cp, "status"));
        }
        return cp;
    }

    /** Limits with utilisation and headroom plus outstanding deals by family (counterparty and its group parent). */
    @Transactional(readOnly = true)
    public ObjectNode exposure(UUID id) {
        ObjectNode cp = rows.get(TABLE, id);
        ObjectNode out = rows.object();
        out.put("treasury_counterparty_id", id.toString());
        out.put("code", Rows.str(cp, "code"));
        out.put("status", Rows.str(cp, "status"));
        out.put("as_of", rows.now());
        ArrayNode limits = out.putArray("limits");
        rows.query("""
                select row_to_json(l)::text from treasury_counterparty_limit l
                where l.treasury_counterparty_id = ? or l.treasury_counterparty_id =
                      (select parent_counterparty_id from treasury_counterparty where id = ?)
                order by l.limit_type, l.currency""", id, id).forEach(l -> {
            ObjectNode n = limits.addObject();
            for (String f : new String[] {"id", "treasury_counterparty_id", "limit_type", "currency", "limit_amount",
                    "utilised_amount", "available_amount", "status", "max_tenor_days"}) {
                n.set(f, l.get(f));
            }
            n.put("group_limit", !id.toString().equals(Rows.str(l, "treasury_counterparty_id")));
            java.math.BigDecimal amt = Rows.dec(l, "limit_amount");
            n.put("utilisation_pct", amt.signum() == 0 ? "0" : Rows.dec(l, "utilised_amount")
                    .divide(amt, java.math.MathContext.DECIMAL64).setScale(4, java.math.RoundingMode.HALF_EVEN).toPlainString());
        });
        ArrayNode deals = out.putArray("outstanding");
        rows.query("""
                select json_build_object('deal_family', 'MONEY_MARKET', 'deal_type', deal_type, 'currency', currency,
                       'count', count(*), 'amount', sum(principal))::text
                from money_market_deal where treasury_counterparty_id = ? and status not in ('MATURED', 'CANCELLED')
                group by deal_type, currency
                union all
                select json_build_object('deal_family', 'FX', 'deal_type', d.deal_type, 'currency', l.base_currency,
                       'count', count(distinct d.id), 'amount', sum(l.base_amount))::text
                from treasury_fx_deal d join treasury_fx_deal_leg l on l.treasury_fx_deal_id = d.id
                where d.treasury_counterparty_id = ? and d.status not in ('SETTLED', 'CANCELLED')
                  and l.settlement_status = 'PENDING'
                group by d.deal_type, l.base_currency
                union all
                select json_build_object('deal_family', 'SECURITY', 'deal_type', 'ISSUER_HOLDINGS', 'currency', s.currency,
                       'count', count(*), 'amount', sum(h.nominal))::text
                from securities_holding h join treasury_security s on s.id = h.treasury_security_id
                where s.issuer_counterparty_id = ? and h.nominal > 0 group by s.currency""", id, id, id)
                .forEach(deals::add);
        return out;
    }

    // ------------------------------------------------------------------ SSIs

    @Transactional
    public ObjectNode createSsi(JsonNode req) {
        UUID cp = Rows.uuid(req, "treasury_counterparty_id");
        Support.required(cp, "treasury_counterparty_id");
        rows.get(TABLE, cp);
        validateSsi(req);
        if (rows.scalar("""
                select count(*) from treasury_ssi where treasury_counterparty_id = ? and currency = ? and valid_from = ?""",
                Integer.class, cp, Rows.str(req, "currency"), Rows.date(req, "valid_from")).orElse(0) > 0) {
            throw ApiException.conflict("duplicate_ssi", "An SSI for this currency already starts on that date");
        }
        return rows.insert(SSI, rows.columns(SSI, req));
    }

    /** PATCH of an SSI: changing payment details clears the verification (must be re-verified by a checker). */
    @Transactional
    public ObjectNode updateSsi(UUID id, int expectedVersion, JsonNode req) {
        ObjectNode ssi = rows.lock(SSI, id);
        if (Rows.integer(ssi, "row_version") != expectedVersion) {
            throw ApiException.conflict("version_conflict", "If-Match does not match the current version; reload and retry");
        }
        if (req.hasNonNull("treasury_counterparty_id") || req.hasNonNull("currency")) {
            throw ApiException.unprocessable("field_not_updatable", "treasury_counterparty_id and currency cannot be changed");
        }
        ObjectNode merged = ssi.deepCopy();
        req.propertyNames().forEach(n -> {
            if (!req.get(n).isNull()) {
                merged.set(n, req.get(n));
            }
        });
        validateSsi(merged);
        ObjectNode v = rows.columns(SSI, req);
        boolean paymentDetails = java.util.stream.Stream.of("beneficiary_bic", "beneficiary_account", "intermediary_bic",
                "our_nostro_account_id").anyMatch(f -> req.hasNonNull(f) && !req.get(f).asString().equals(Rows.str(ssi, f)));
        if (paymentDetails) {
            v.putNull("verified_by");
            v.putNull("verified_at");
        }
        return rows.update(SSI, id, v);
    }

    private static void validateSsi(JsonNode req) {
        Support.currency(Rows.str(req, "currency"), "currency");
        for (String f : new String[] {"beneficiary_bic", "intermediary_bic"}) {
            String bic = Rows.str(req, f);
            if (bic != null && !Identifiers.isValidBic(bic)) {
                throw ApiException.unprocessable("invalid_bic", f + " " + bic + " is not a valid BIC");
            }
        }
        Support.ordered(Rows.date(req, "valid_from"), Rows.date(req, "valid_to"), "valid_to must not be before valid_from");
    }

    @Transactional
    public ObjectNode verifySsi(UUID id) {
        ObjectNode ssi = rows.lock(SSI, id);
        if (Rows.str(ssi, "verified_by") != null) {
            throw ApiException.conflict("already_verified", "The SSI is already verified");
        }
        Support.fourEyes(ssi, Rows.str(ssi, "updated_by"));
        ObjectNode v = rows.object();
        v.put("verified_by", TenantContext.principal());
        v.put("verified_at", rows.now());
        ObjectNode out = rows.update(SSI, id, v);
        events.publish("treasury.ssi.verified", SSI, out);
        return out;
    }

    /** Verified SSI of the counterparty for a currency valid on the date (default first), if any. */
    public java.util.Optional<ObjectNode> ssiFor(UUID counterpartyId, String currency, LocalDate on) {
        return rows.queryOne("""
                select row_to_json(s)::text from treasury_ssi s
                where s.treasury_counterparty_id = ? and s.currency = ? and s.verified_by is not null
                  and s.valid_from <= ? and (s.valid_to is null or s.valid_to >= ?)
                order by s.is_default desc, s.valid_from desc limit 1""", counterpartyId, currency, on, on);
    }
}
