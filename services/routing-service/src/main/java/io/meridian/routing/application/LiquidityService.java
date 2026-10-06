package io.meridian.routing.application;

import static io.meridian.routing.infrastructure.Rows.bool;
import static io.meridian.routing.infrastructure.Rows.decOr0;
import static io.meridian.routing.infrastructure.Rows.str;
import static io.meridian.routing.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.routing.config.RoutingProperties;
import io.meridian.routing.infrastructure.Downstream;
import io.meridian.routing.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Intraday liquidity of nostro / settlement accounts. A position is computed from the mirror ledger account balance
 * (current) and the unsettled batches of the rail the nostro funds: projected outflows = debit totals and projected
 * inflows = credit totals of OPEN / CLOSED batches in the nostro currency. Available liquidity = current - projected
 * outflows (inflows are reported but not counted until settled). A position below min_balance is a breach.
 */
@Service
public class LiquidityService {
    static final String NOSTRO = "nostro_account";
    static final String POSITION = "rail_liquidity_position";

    private final Rows rows;
    private final Events events;
    private final Downstream downstream;
    private final RoutingProperties props;

    public LiquidityService(Rows rows, Events events, Downstream downstream, RoutingProperties props) {
        this.rows = rows;
        this.events = events;
        this.downstream = downstream;
        this.props = props;
    }

    @Transactional
    public ObjectNode refresh(UUID nostroId) {
        ObjectNode nostro = rows.lock(NOSTRO, nostroId);
        if (!bool(nostro, "is_active")) {
            throw ApiException.conflict("nostro_inactive", "Nostro account is inactive");
        }
        JsonNode balance = downstream.balance(uuid(nostro, "ledger_account_id"));
        BigDecimal current = new BigDecimal(balance.path("posted_balance").asString("0"));
        BigDecimal outflows = BigDecimal.ZERO;
        BigDecimal inflows = BigDecimal.ZERO;
        UUID railId = uuid(nostro, "payment_rail_id");
        if (railId != null) {
            ObjectNode sums = rows.queryOne("""
                    select json_build_object('debits', coalesce(sum(debit_total), 0), 'credits', coalesce(sum(credit_total), 0))::text
                    from rail_settlement_batch where payment_rail_id = ? and currency = ? and status in ('OPEN', 'CLOSED')""",
                    railId, str(nostro, "currency")).orElseThrow();
            outflows = decOr0(sums, "debits");
            inflows = decOr0(sums, "credits");
        }
        BigDecimal opening = rows.queryOne("""
                select row_to_json(t)::text from rail_liquidity_position t where nostro_account_id = ?
                and as_of < date_trunc('day', now()) order by as_of desc limit 1""", nostroId)
                .map(p -> decOr0(p, "current_balance")).orElse(current);
        BigDecimal available = current.subtract(outflows);
        boolean breach = available.compareTo(decOr0(nostro, "min_balance")) < 0;
        OffsetDateTime now = rows.scalar("select now()", OffsetDateTime.class).orElseThrow();
        ObjectNode p = rows.object();
        p.put("nostro_account_id", nostroId.toString());
        p.put("as_of", now.toString());
        p.put("opening_balance", opening.toPlainString());
        p.put("projected_inflows", inflows.toPlainString());
        p.put("projected_outflows", outflows.toPlainString());
        p.put("current_balance", current.toPlainString());
        p.put("available_liquidity", available.toPlainString());
        p.put("currency", str(nostro, "currency"));
        p.put("breach", breach);
        ObjectNode position = rows.insert(POSITION, p);
        if (breach) {
            events.publish("payments.rail_liquidity_position.breached", POSITION, position);
        }
        return position;
    }

    /** Funds the nostro from the treasury funding account (ledger transfer) and records the refreshed position. */
    @Transactional
    public ObjectNode topUp(UUID nostroId, BigDecimal amount, String reason, String idempotencyKey) {
        if (amount == null || amount.signum() <= 0) {
            throw ApiException.unprocessable("invalid_amount", "amount must be positive");
        }
        ObjectNode nostro = rows.lock(NOSTRO, nostroId);
        if (!bool(nostro, "is_active")) {
            throw ApiException.conflict("nostro_inactive", "Nostro account is inactive");
        }
        UUID funding = props.gl().treasuryFunding();
        if (funding == null) {
            throw new ApiException(500, "treasury_funding_not_configured",
                    "meridian.routing.gl.treasury-funding is not configured");
        }
        UUID journal = downstream.transfer("nos-topup:" + nostroId + ":" + idempotencyKey, uuid(nostro, "ledger_account_id"),
                funding, amount, str(nostro, "currency"),
                "Nostro top-up " + str(nostro, "name") + (reason == null ? "" : ": " + reason), "NOSTRO_ACCOUNT", nostroId);
        ObjectNode event = nostro.deepCopy();
        event.put("top_up_amount", amount.toPlainString());
        event.put("journal_entry_id", journal.toString());
        event.put("reason", reason);
        events.publish("payments.nostro_account.topped_up", NOSTRO, event);
        return refresh(nostroId);
    }
}
