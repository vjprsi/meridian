package io.meridian.liquidity.application;

import io.meridian.common.error.ApiException;
import io.meridian.liquidity.domain.Amounts;
import io.meridian.liquidity.domain.CashFlow;
import io.meridian.liquidity.domain.CashPositions;
import io.meridian.liquidity.infrastructure.FlowSources;
import io.meridian.liquidity.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Cash positions per legal entity, currency, nostro and day: manually recorded ACTUAL / FORECAST positions,
 * PROJECTED positions rolled forward from expected flows (treasury deals and scheduled payments), and the end-of-day
 * ACTUAL snapshot.
 */
@Service
public class PositionService {
    static final String TABLE = "liquidity_cash_position";
    static final int MAX_PROJECTION_DAYS = 90;
    private static final Logger log = LoggerFactory.getLogger(PositionService.class);

    private final Rows rows;
    private final Events events;
    private final FlowSources sources;

    public PositionService(Rows rows, Events events, FlowSources sources) {
        this.rows = rows;
        this.events = events;
        this.sources = sources;
    }

    /** Records an ACTUAL or FORECAST position; the closing balance and shortfall are computed. */
    @Transactional
    public ObjectNode create(Object body) {
        ObjectNode v = rows.columns(TABLE, body);
        String type = Rows.str(v, "position_type");
        if ("PROJECTED".equals(type)) {
            throw ApiException.unprocessable("projected_positions_are_computed",
                    "PROJECTED positions are computed by POST /project; record ACTUAL or FORECAST positions");
        }
        BigDecimal opening = Rows.dec(v, "opening_balance");
        BigDecimal in = Rows.decOr0(v, "expected_inflows");
        BigDecimal out = Rows.decOr0(v, "expected_outflows");
        if (in.signum() < 0 || out.signum() < 0) {
            throw ApiException.unprocessable("invalid_flows", "expected_inflows and expected_outflows must not be negative");
        }
        BigDecimal buffer = Rows.dec(v, "minimum_buffer");
        if (buffer != null && buffer.signum() < 0) {
            throw ApiException.unprocessable("invalid_minimum_buffer", "minimum_buffer must not be negative");
        }
        UUID entity = Rows.uuid(v, "legal_entity_id");
        LocalDate date = Rows.date(v, "position_date");
        String currency = Rows.str(v, "currency");
        UUID nostro = Rows.uuid(v, "nostro_account_id");
        if (find(entity, date, currency, nostro, type, false).isPresent()) {
            throw ApiException.conflict("position_exists", "a " + type + " position for " + currency + " on " + date
                    + " already exists for this entity and nostro");
        }
        BigDecimal closing = CashPositions.closing(opening, in, out);
        v.put("expected_inflows", in.toPlainString());
        v.put("expected_outflows", out.toPlainString());
        v.put("closing_balance", closing.toPlainString());
        putOrNull(v, "shortfall", CashPositions.shortfall(closing, buffer));
        ObjectNode row = rows.insert(TABLE, v);
        if (row.hasNonNull("shortfall")) {
            events.publish("treasury.liquidity_position.shortfall", TABLE, row);
        }
        return row;
    }

    /**
     * Recomputes PROJECTED positions for {@code days} days from {@code from} for one legal entity: per currency the
     * opening balance is the one given, else the previous day's ACTUAL (or PROJECTED) closing, else zero; each day's
     * closing rolls into the next day's opening.
     */
    @Transactional
    public ObjectNode project(JsonNode req) {
        UUID entity = Inputs.uuid(req, "legal_entity_id", "request");
        LocalDate from = Inputs.date(req, "from_date", "request");
        Integer days = Inputs.integer(req, "days", "request");
        if (entity == null || from == null || days == null) {
            throw ApiException.unprocessable("missing_field", "legal_entity_id, from_date and days are required");
        }
        if (days < 1 || days > MAX_PROJECTION_DAYS) {
            throw ApiException.unprocessable("invalid_days", "days must be between 1 and " + MAX_PROJECTION_DAYS);
        }
        Map<String, Opening> openings = new LinkedHashMap<>();
        for (JsonNode o : Inputs.list(req, "opening_balances")) {
            String ccy = Inputs.currency(o, "currency", "opening_balances", true);
            BigDecimal amount = Inputs.decimal(o, "amount", "opening_balances[" + ccy + "]", true);
            BigDecimal buffer = Inputs.decimal(o, "minimum_buffer", "opening_balances[" + ccy + "]", false);
            if (buffer != null && buffer.signum() < 0) {
                throw ApiException.unprocessable("invalid_minimum_buffer", "minimum_buffer must not be negative");
            }
            if (openings.put(ccy, new Opening(amount, buffer, Inputs.uuid(o, "nostro_account_id", "opening_balances")))
                    != null) {
                throw ApiException.unprocessable("duplicate_currency", "opening_balances lists " + ccy + " twice");
            }
        }
        LocalDate to = from.plusDays(days - 1L);
        List<String> warnings = new ArrayList<>();
        List<CashFlow> flows = fetchFlows(entity, from, to, warnings);
        Set<String> currencies = new LinkedHashSet<>(openings.keySet());
        flows.forEach(f -> currencies.add(f.currency()));

        ObjectNode summary = rows.object();
        summary.put("legal_entity_id", entity.toString());
        summary.put("from_date", from.toString());
        summary.put("to_date", to.toString());
        summary.put("days", days);
        ArrayNode out = summary.putArray("positions");
        int shortfalls = 0;
        for (String ccy : currencies) {
            Opening given = openings.get(ccy);
            UUID nostro = given == null ? null : given.nostro();
            Optional<ObjectNode> previous = previousClosing(entity, from, ccy, nostro);
            BigDecimal opening = given != null ? given.amount()
                    : previous.map(p -> Rows.decOr0(p, "closing_balance")).orElse(BigDecimal.ZERO);
            BigDecimal buffer = given != null && given.buffer() != null ? given.buffer()
                    : previous.map(p -> Rows.dec(p, "minimum_buffer")).orElse(null);
            List<CashFlow> mine = flows.stream().filter(f -> f.currency().equals(ccy)).toList();
            for (CashPositions.Position p : CashPositions.project(from, days, opening, buffer, mine)) {
                ObjectNode row = upsert(entity, p.date(), ccy, nostro, "PROJECTED", p.opening(), p.inflows(),
                        p.outflows(), p.minimumBuffer(), categories(p.byCategory()));
                events.publish("treasury.liquidity_position.projected", TABLE, row);
                if (row.hasNonNull("shortfall")) {
                    shortfalls++;
                    events.publish("treasury.liquidity_position.shortfall", TABLE, row);
                }
                out.add(row);
            }
        }
        summary.put("shortfalls", shortfalls);
        summary.put("flows_considered", flows.size());
        ArrayNode w = summary.putArray("warnings");
        warnings.forEach(w::add);
        return summary;
    }

    /**
     * End of day: writes the ACTUAL position of every PROJECTED position of {@code date} (all legal entities). The
     * opening is the previous day's ACTUAL closing (else the projected opening) and the day's flows are those of the
     * latest projection for the date. Returns counts.
     */
    @Transactional
    public Map<String, Integer> snapshot(LocalDate date) {
        int written = 0;
        int shortfalls = 0;
        for (ObjectNode p : rows.query("""
                select row_to_json(p)::text from liquidity_cash_position p
                where position_date = ? and position_type = 'PROJECTED' order by legal_entity_id, currency""", date)) {
            UUID entity = Rows.uuid(p, "legal_entity_id");
            String ccy = Rows.str(p, "currency");
            UUID nostro = Rows.uuid(p, "nostro_account_id");
            BigDecimal opening = find(entity, date.minusDays(1), ccy, nostro, "ACTUAL", false)
                    .map(a -> Rows.decOr0(a, "closing_balance")).orElse(Rows.decOr0(p, "opening_balance"));
            ObjectNode row = upsert(entity, date, ccy, nostro, "ACTUAL", opening, Rows.decOr0(p, "expected_inflows"),
                    Rows.decOr0(p, "expected_outflows"), Rows.dec(p, "minimum_buffer"),
                    p.hasNonNull("flows_by_category") ? (ObjectNode) p.get("flows_by_category") : rows.object());
            written++;
            if (row.hasNonNull("shortfall")) {
                shortfalls++;
                events.publish("treasury.liquidity_position.shortfall", TABLE, row);
            }
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("actual_positions", written);
        counts.put("shortfalls", shortfalls);
        return counts;
    }

    // ------------------------------------------------------------------ helpers

    private List<CashFlow> fetchFlows(UUID entity, LocalDate from, LocalDate to, List<String> warnings) {
        List<CashFlow> flows = new ArrayList<>();
        try {
            flows.addAll(sources.treasuryFlows(from, to, entity));
        } catch (ApiException e) {
            log.warn("treasury flows unavailable: {}", e.getMessage());
            warnings.add("treasury-deal-service flows unavailable: " + e.code());
        }
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            try {
                flows.addAll(sources.paymentFlows(d));
            } catch (ApiException e) {
                log.warn("payment flows unavailable for {}: {}", d, e.getMessage());
                warnings.add("payment-service flows unavailable for " + d + ": " + e.code());
                break;
            }
        }
        flows.removeIf(f -> f.valueDate().isBefore(from) || f.valueDate().isAfter(to));
        return flows;
    }

    private ObjectNode categories(Map<String, BigDecimal[]> byCategory) {
        ObjectNode n = rows.object();
        byCategory.forEach((cat, v) -> {
            ObjectNode c = n.putObject(cat);
            c.put("inflows", Amounts.money(v[0]).toPlainString());
            c.put("outflows", Amounts.money(v[1]).toPlainString());
        });
        return n;
    }

    private Optional<ObjectNode> previousClosing(UUID entity, LocalDate from, String ccy, UUID nostro) {
        Optional<ObjectNode> actual = find(entity, from.minusDays(1), ccy, nostro, "ACTUAL", false);
        return actual.isPresent() ? actual : find(entity, from.minusDays(1), ccy, nostro, "PROJECTED", false);
    }

    private Optional<ObjectNode> find(UUID entity, LocalDate date, String ccy, UUID nostro, String type, boolean lock) {
        return rows.queryOne("""
                select row_to_json(p)::text from liquidity_cash_position p
                where legal_entity_id = ? and position_date = ? and currency = ?
                  and nostro_account_id is not distinct from ? and position_type = ?""" + (lock ? " for update" : ""),
                entity, date, ccy, nostro, type);
    }

    private ObjectNode upsert(UUID entity, LocalDate date, String ccy, UUID nostro, String type, BigDecimal opening,
                              BigDecimal in, BigDecimal out, BigDecimal buffer, ObjectNode byCategory) {
        BigDecimal closing = CashPositions.closing(opening, in, out);
        ObjectNode v = rows.object();
        v.put("opening_balance", Amounts.money(opening).toPlainString());
        v.put("expected_inflows", Amounts.money(in).toPlainString());
        v.put("expected_outflows", Amounts.money(out).toPlainString());
        v.put("closing_balance", closing.toPlainString());
        putOrNull(v, "minimum_buffer", buffer);
        putOrNull(v, "shortfall", CashPositions.shortfall(closing, buffer));
        v.set("flows_by_category", byCategory);
        Optional<ObjectNode> existing = find(entity, date, ccy, nostro, type, true);
        if (existing.isPresent()) {
            return rows.update(TABLE, Rows.uuid(existing.get(), "id"), v);
        }
        v.put("legal_entity_id", entity.toString());
        v.put("position_date", date.toString());
        v.put("currency", ccy);
        if (nostro != null) {
            v.put("nostro_account_id", nostro.toString());
        }
        v.put("position_type", type);
        return rows.insert(TABLE, v);
    }

    private static void putOrNull(ObjectNode v, String field, BigDecimal value) {
        if (value == null) {
            v.putNull(field);
        } else {
            v.put(field, value.toPlainString());
        }
    }

    private record Opening(BigDecimal amount, BigDecimal buffer, UUID nostro) {}
}
