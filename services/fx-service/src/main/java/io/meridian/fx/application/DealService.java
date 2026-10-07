package io.meridian.fx.application;

import static io.meridian.fx.infrastructure.Rows.date;
import static io.meridian.fx.infrastructure.Rows.dec;
import static io.meridian.fx.infrastructure.Rows.str;

import io.meridian.common.error.ApiException;
import io.meridian.fx.config.FxProperties;
import io.meridian.fx.domain.FxPricing;
import io.meridian.fx.infrastructure.LedgerClient;
import io.meridian.fx.infrastructure.LedgerClient.Leg;
import io.meridian.fx.infrastructure.Rows;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

/**
 * FX deal settlement and cancellation. Settlement posts one journal entry balanced per currency:
 * sell currency DEBIT clearing / CREDIT FX position, buy currency DEBIT FX position / CREDIT clearing.
 */
@Service
public class DealService {
    static final String DEAL = QuoteService.DEAL;

    private final Rows rows;
    private final LedgerClient ledger;
    private final Events events;
    private final FxProperties props;

    public DealService(Rows rows, LedgerClient ledger, Events events, FxProperties props) {
        this.rows = rows;
        this.ledger = ledger;
        this.events = events;
        this.props = props;
    }

    @Transactional
    public ObjectNode settle(UUID id) {
        ObjectNode d = rows.lock(DEAL, id);
        String status = str(d, "status");
        if ("SETTLED".equals(status)) {
            throw ApiException.conflict("deal_already_settled", "The deal is already settled");
        }
        if (!"BOOKED".equals(status)) {
            throw ApiException.conflict("invalid_transition", "Only a BOOKED deal can be settled (status " + status + ")");
        }
        String sell = str(d, "sell_currency");
        String buy = str(d, "buy_currency");
        BigDecimal sellAmount = FxPricing.round(dec(d, "sell_amount"), sell);
        BigDecimal buyAmount = FxPricing.round(dec(d, "buy_amount"), buy);
        String text = "FX deal " + id + " " + sell + "/" + buy + " @ " + dec(d, "rate").toPlainString();
        UUID je = ledger.post(props.ledgerId(), "fxd-settle:" + id, text, "FX_DEAL", id, date(d, "value_date"), List.of(
                new Leg(account(props.clearingAccount(sell), sell, "clearing"), "DEBIT", sellAmount, sell, "Customer sells " + sell),
                new Leg(account(props.positionAccount(sell), sell, "position"), "CREDIT", sellAmount, sell, "FX position " + sell),
                new Leg(account(props.positionAccount(buy), buy, "position"), "DEBIT", buyAmount, buy, "FX position " + buy),
                new Leg(account(props.clearingAccount(buy), buy, "clearing"), "CREDIT", buyAmount, buy, "Customer buys " + buy)));
        ObjectNode row = rows.update(DEAL, id, rows.object().put("status", "SETTLED").put("journal_entry_id", je.toString()));
        events.publish("payments.fx_deal.settled", DEAL, row);
        return row;
    }

    @Transactional
    public ObjectNode cancel(UUID id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.unprocessable("reason_required", "reason is required");
        }
        ObjectNode d = rows.lock(DEAL, id);
        if (!"BOOKED".equals(str(d, "status"))) {
            throw ApiException.conflict("invalid_transition", "Only a BOOKED deal can be cancelled (status "
                    + str(d, "status") + ")");
        }
        ObjectNode row = rows.update(DEAL, id, rows.object().put("status", "CANCELLED").put("cancellation_reason", reason));
        events.publish("payments.fx_deal.cancelled", DEAL, row);
        return row;
    }

    private static UUID account(UUID a, String ccy, String kind) {
        if (a == null) {
            throw ApiException.unprocessable("ledger_account_not_configured",
                    "No FX " + kind + " ledger account is configured for " + ccy);
        }
        return a;
    }
}
