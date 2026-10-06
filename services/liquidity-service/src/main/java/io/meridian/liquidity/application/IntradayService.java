package io.meridian.liquidity.application;

import io.meridian.common.error.ApiException;
import io.meridian.liquidity.domain.IntradayCalculator;
import io.meridian.liquidity.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Intraday liquidity snapshots (BCBS 248) computed from the settled movements up to the snapshot time. */
@Service
public class IntradayService {
    static final String TABLE = "intraday_liquidity_snapshot";

    private final Rows rows;
    private final Events events;

    public IntradayService(Rows rows, Events events) {
        this.rows = rows;
        this.events = events;
    }

    @Transactional
    public ObjectNode create(Object body) {
        JsonNode req = rows.json().valueToTree(body);
        ObjectNode v = rows.columns(TABLE, body);
        OffsetDateTime snapshotAt = Inputs.timestamp(req, "snapshot_at", "request");
        BigDecimal available = Rows.dec(v, "available_liquidity");
        if (available == null || available.signum() < 0) {
            throw ApiException.unprocessable("invalid_available_liquidity", "available_liquidity must not be negative");
        }
        List<IntradayCalculator.Movement> movements = new ArrayList<>();
        List<JsonNode> raw = Inputs.list(req, "movements");
        for (int i = 0; i < raw.size(); i++) {
            JsonNode m = raw.get(i);
            String ctx = "movements[" + i + "]";
            String dir = Inputs.requiredText(m, "direction", ctx).toUpperCase(Locale.ROOT);
            if (!dir.equals("SENT") && !dir.equals("RECEIVED")) {
                throw ApiException.unprocessable("invalid_direction", ctx + ": direction must be SENT or RECEIVED");
            }
            movements.add(new IntradayCalculator.Movement(Inputs.timestamp(m, "at", ctx), dir.equals("SENT"),
                    Inputs.decimal(m, "amount", ctx, true)));
        }
        IntradayCalculator.Result r = Inputs.domain(() -> IntradayCalculator.calculate(movements, snapshotAt,
                Inputs.decimal(req, "expected_daily_payments", "request", false)));
        v.put("payments_sent", r.sent().toPlainString());
        v.put("payments_received", r.received().toPlainString());
        v.put("largest_negative_position", r.largestNegative().toPlainString());
        v.put("largest_positive_position", r.largestPositive().toPlainString());
        v.put("used_liquidity", r.usedLiquidity().toPlainString());
        if (r.throughput() == null) {
            v.putNull("throughput_pct");
        } else {
            v.put("throughput_pct", r.throughput().toPlainString());
        }
        ArrayNode profile = v.putArray("profile");
        for (IntradayCalculator.Point p : r.profile()) {
            ObjectNode n = profile.addObject();
            n.put("at", p.at().toString());
            n.put("net_cumulative", p.netCumulative().toPlainString());
        }
        ObjectNode row = rows.insert(TABLE, v);
        events.publish("treasury.intraday_liquidity.recorded", TABLE, row);
        return row;
    }
}
