package io.meridian.liquidity.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.liquidity.domain.LcrCalculator;
import io.meridian.liquidity.domain.NsfrCalculator;
import io.meridian.liquidity.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Basel III ratios: one LCR and one NSFR snapshot per legal entity, date and reporting currency. Recalculation
 * replaces a snapshot until it is approved for reporting; approval is four-eyes (not the calculator).
 */
@Service
public class RatioService {
    static final String LCR = "lcr_calculation";
    static final String NSFR = "nsfr_calculation";
    static final Set<String> LOCKED = Set.of("APPROVED", "SUBMITTED");
    static final Set<String> APPROVABLE = Set.of("CALCULATED", "REVIEWED");

    private final Rows rows;
    private final Events events;

    public RatioService(Rows rows, Events events) {
        this.rows = rows;
        this.events = events;
    }

    @Transactional
    public ObjectNode calculateLcr(JsonNode req) {
        Key key = key(req);
        BigDecimal minimum = minimum(req);
        List<LcrCalculator.Line> lines = new ArrayList<>();
        List<JsonNode> positions = Inputs.list(req, "positions");
        for (int i = 0; i < positions.size(); i++) {
            JsonNode p = positions.get(i);
            String ctx = "positions[" + i + "]";
            String code = Inputs.requiredText(p, "code", ctx).toUpperCase(Locale.ROOT);
            LcrCalculator.Category derived = LcrCalculator.categoryOf(code);
            if (derived == null) {
                throw ApiException.unprocessable("unknown_code", ctx + ": " + code + " is not a standard LCR code");
            }
            String cat = Inputs.text(p, "category");
            if (cat != null && !cat.equalsIgnoreCase(derived.name())) {
                throw ApiException.unprocessable("category_mismatch", ctx + ": code " + code + " is " + derived);
            }
            lines.add(new LcrCalculator.Line(line(p, i), code, derived, Inputs.decimal(p, "amount", ctx, true),
                    Inputs.decimal(p, "factor", ctx, false)));
        }
        LcrCalculator.Result r = Inputs.domain(() -> LcrCalculator.calculate(lines, minimum));
        ObjectNode v = rows.object();
        v.put("hqla_level1", r.level1().toPlainString());
        v.put("hqla_level2a", r.level2a().toPlainString());
        v.put("hqla_level2b", r.level2b().toPlainString());
        v.put("cap_adjustment", r.capAdjustment().toPlainString());
        v.put("total_hqla", r.totalHqla().toPlainString());
        v.put("outflows_30d", r.outflows().toPlainString());
        v.put("uncapped_inflows", r.uncappedInflows().toPlainString());
        v.put("inflows_30d", r.inflows().toPlainString());
        v.put("net_outflows", r.netOutflows().toPlainString());
        v.put("lcr_ratio", r.ratio().toPlainString());
        v.put("breach", r.breach());
        ArrayNode comps = v.putArray("components");
        for (LcrCalculator.Component c : r.components()) {
            ObjectNode n = comps.addObject();
            n.put("line", c.line());
            n.put("code", c.code());
            n.put("category", c.category().name());
            n.put("amount", c.amount().toPlainString());
            n.put("factor", c.factor().toPlainString());
            n.put("weighted", c.weighted().toPlainString());
        }
        ObjectNode row = save(LCR, key, minimum, v);
        events.publish("treasury.lcr.calculated", LCR, row);
        if (r.breach()) {
            events.publish("treasury.lcr.breached", LCR, row);
        }
        return row;
    }

    @Transactional
    public ObjectNode calculateNsfr(JsonNode req) {
        Key key = key(req);
        BigDecimal minimum = minimum(req);
        List<NsfrCalculator.Line> lines = new ArrayList<>();
        List<JsonNode> positions = Inputs.list(req, "positions");
        for (int i = 0; i < positions.size(); i++) {
            JsonNode p = positions.get(i);
            String ctx = "positions[" + i + "]";
            String code = Inputs.requiredText(p, "code", ctx).toUpperCase(Locale.ROOT);
            String side = Inputs.text(p, "side");
            NsfrCalculator.Side s = null;
            if (side != null) {
                try {
                    s = NsfrCalculator.Side.valueOf(side.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    throw ApiException.unprocessable("invalid_side", ctx + ": side must be ASF or RSF");
                }
            }
            lines.add(new NsfrCalculator.Line(line(p, i), code, s, Inputs.decimal(p, "amount", ctx, true),
                    Inputs.decimal(p, "factor", ctx, false)));
        }
        NsfrCalculator.Result r = Inputs.domain(() -> NsfrCalculator.calculate(lines, minimum));
        ObjectNode v = rows.object();
        v.put("available_stable_funding", r.asf().toPlainString());
        v.put("required_stable_funding", r.rsf().toPlainString());
        v.put("nsfr_ratio", r.ratio().toPlainString());
        v.put("breach", r.breach());
        ArrayNode comps = v.putArray("components");
        for (NsfrCalculator.Component c : r.components()) {
            ObjectNode n = comps.addObject();
            n.put("line", c.line());
            n.put("code", c.code());
            n.put("side", c.side().name());
            n.put("amount", c.amount().toPlainString());
            n.put("factor", c.factor().toPlainString());
            n.put("weighted", c.weighted().toPlainString());
        }
        ObjectNode row = save(NSFR, key, minimum, v);
        events.publish("treasury.nsfr.calculated", NSFR, row);
        if (r.breach()) {
            events.publish("treasury.nsfr.breached", NSFR, row);
        }
        return row;
    }

    /** Approves an LCR or NSFR snapshot for regulatory reporting (four-eyes). */
    @Transactional
    public ObjectNode approve(String table, UUID id) {
        ObjectNode row = rows.lock(table, id);
        String status = Rows.str(row, "status");
        if (!APPROVABLE.contains(status)) {
            throw ApiException.conflict("invalid_transition", "a " + status + " calculation cannot be approved");
        }
        String me = TenantContext.principal();
        if (me.equals(Rows.str(row, "created_by")) || me.equals(Rows.str(row, "updated_by"))) {
            throw ApiException.unprocessable("four_eyes_violation", "the calculator cannot approve the calculation");
        }
        rows.execute("update " + table + " set status = 'APPROVED', approved_by = ?, approved_at = now(), updated_by = ?"
                + " where id = ?", me, me, id);
        ObjectNode approved = rows.get(table, id);
        events.publish(LCR.equals(table) ? "treasury.lcr.approved" : "treasury.nsfr.approved", table, approved);
        return approved;
    }

    private ObjectNode save(String table, Key key, BigDecimal minimum, ObjectNode v) {
        v.put("regulatory_minimum", (minimum == null ? BigDecimal.ONE : minimum).toPlainString());
        v.put("status", "CALCULATED");
        v.putNull("approved_by");
        v.putNull("approved_at");
        Optional<ObjectNode> existing = rows.queryOne("select row_to_json(t)::text from " + table + " t"
                + " where legal_entity_id = ? and as_of_date = ? and currency = ? for update",
                key.entity(), key.date(), key.currency());
        if (existing.isPresent()) {
            String status = Rows.str(existing.get(), "status");
            if (LOCKED.contains(status)) {
                throw ApiException.conflict("calculation_locked", "the " + key.date() + " " + key.currency()
                        + " calculation is " + status + " and can no longer be recalculated");
            }
            return rows.update(table, Rows.uuid(existing.get(), "id"), v);
        }
        v.put("legal_entity_id", key.entity().toString());
        v.put("as_of_date", key.date().toString());
        v.put("currency", key.currency());
        return rows.insert(table, v);
    }

    private static Key key(JsonNode req) {
        UUID entity = Inputs.uuid(req, "legal_entity_id", "request");
        LocalDate date = Inputs.date(req, "as_of_date", "request");
        String ccy = Inputs.currency(req, "currency", "request", true);
        if (entity == null || date == null) {
            throw ApiException.unprocessable("missing_field", "legal_entity_id and as_of_date are required");
        }
        return new Key(entity, date, ccy);
    }

    private static BigDecimal minimum(JsonNode req) {
        BigDecimal m = Inputs.decimal(req, "regulatory_minimum", "request", false);
        if (m != null && (m.signum() <= 0 || m.compareTo(BigDecimal.TEN) > 0)) {
            throw ApiException.unprocessable("invalid_regulatory_minimum", "regulatory_minimum must be > 0 and <= 10");
        }
        return m;
    }

    private static String line(JsonNode p, int i) {
        String l = Inputs.text(p, "line");
        return l == null ? String.valueOf(i + 1) : l;
    }

    private record Key(UUID entity, LocalDate date, String currency) {}
}
