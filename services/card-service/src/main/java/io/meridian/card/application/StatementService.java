package io.meridian.card.application;

import io.meridian.card.domain.StatementCalculator;
import io.meridian.card.infrastructure.Peers;
import io.meridian.card.infrastructure.Rows;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Credit card statement cycles: close from the card account's ledger entries and open the next cycle. */
@Service
public class StatementService {
    private final Rows rows;
    private final Events events;
    private final Peers peers;
    private final CardService cards;
    private final TransactionTemplate tx;

    public StatementService(Rows rows, Events events, Peers peers, CardService cards, TransactionTemplate tx) {
        this.rows = rows;
        this.events = events;
        this.peers = peers;
        this.cards = cards;
        this.tx = tx;
    }

    public JsonNode close(UUID id) {
        ObjectNode[] ctx = tx.execute(s -> {
            ObjectNode cycle = rows.get("card_statement_cycle", id);
            check(cycle);
            return new ObjectNode[] {cycle, rows.get("card", Rows.uuid(cycle, "card_id"))};
        });
        ObjectNode cycle = ctx[0];
        ObjectNode card = ctx[1];
        LocalDate start = Rows.date(cycle, "cycle_start");
        LocalDate end = Rows.date(cycle, "cycle_end");
        List<StatementCalculator.Line> lines = new ArrayList<>();
        String cursor = null;
        String currency = Rows.str(cycle, "currency");
        do {
            JsonNode page = peers.ledgerEntries(Rows.uuid(card, "ledger_account_id"), start + "T00:00:00Z",
                    end.plusDays(1) + "T00:00:00Z", cursor);
            for (JsonNode e : page.path("data")) {
                String ccy = Rows.str(e, "currency");
                if (ccy != null && !ccy.equals(currency)) {
                    continue;
                }
                lines.add(new StatementCalculator.Line(Rows.str(e, "direction"), Rows.dec(e, "amount"),
                        Rows.str(e, "description")));
            }
            cursor = page.path("has_more").asBoolean(false) ? Rows.str(page, "next_cursor") : null;
        } while (cursor != null);
        StatementCalculator.Statement st = StatementCalculator.compute(Rows.decOr0(cycle, "opening_balance"), lines);
        return tx.execute(s -> {
            ObjectNode locked = rows.lock("card_statement_cycle", id);
            check(locked);
            rows.execute("""
                    update card_statement_cycle set purchases = ?, cash_advances = ?, fees = ?, interest = ?, payments = ?,
                      closing_balance = ?, minimum_due = ?, status = 'CLOSED', closed_at = now(), updated_by = ?
                    where id = ?""", st.purchases(), st.cashAdvances(), st.fees(), st.interest(), st.payments(),
                    st.closing(), st.minimumDue(), TenantContext.principal(), id);
            ObjectNode closed = rows.get("card_statement_cycle", id);
            if (!java.util.Set.of("CLOSED", "REPLACED").contains(Rows.str(card, "status"))) {
                cards.openCycle(card, end.plusDays(1), st.closing(), currency);
            }
            events.publish("cards.card_statement_cycle.closed", "card_statement_cycle", closed);
            return closed;
        });
    }

    private void check(ObjectNode cycle) {
        if (!"OPEN".equals(Rows.str(cycle, "status"))) {
            throw ApiException.conflict("invalid_transition", "Cannot close a cycle in status " + Rows.str(cycle, "status"));
        }
        if (Rows.date(cycle, "cycle_end").isAfter(rows.dbToday())) {
            throw ApiException.unprocessable("cycle_not_ended", "the cycle ends on " + Rows.str(cycle, "cycle_end"));
        }
    }
}
