package io.meridian.cardclearing.application;

import io.meridian.cardclearing.config.ClearingProperties;
import io.meridian.cardclearing.infrastructure.Clients;
import io.meridian.cardclearing.infrastructure.Clients.Leg;
import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Cardholder fees: charge (journal debit cardholder, credit fee income, tax to the tax account) and waive (a
 * compensating card_fee row plus the reversal of the original posting; card_fee is append-only).
 */
@Service
public class FeeService {
    private static final Pattern CCY = Pattern.compile("[A-Z]{3}");

    private final ClearingStore store;
    private final Clients clients;
    private final ClearingProperties props;
    private final Events events;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public FeeService(ClearingStore store, Clients clients, ClearingProperties props, Events events, JdbcClient jdbc,
                      TransactionTemplate tx) {
        this.store = store;
        this.clients = clients;
        this.props = props;
        this.events = events;
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public JsonNode charge(JsonNode req) {
        UUID cardId = ClearingStore.uuid(req, "card_id");
        String feeType = ClearingStore.text(req, "fee_type");
        BigDecimal amount = ClearingStore.dec(req, "amount");
        BigDecimal tax = ClearingStore.dec(req, "tax_amount");
        tax = tax == null ? BigDecimal.ZERO : tax;
        String currency = ClearingStore.text(req, "currency");
        if (amount == null || amount.signum() <= 0) {
            throw ApiException.unprocessable("invalid_amount", "amount must be greater than 0");
        }
        if (tax.signum() < 0) {
            throw ApiException.unprocessable("invalid_amount", "tax_amount must be 0 or more");
        }
        if (currency == null || !CCY.matcher(currency).matches()) {
            throw ApiException.unprocessable("invalid_currency", "currency must be an ISO 4217 code");
        }
        if ("INTERCHANGE_INCOME".equals(feeType)) {
            throw ApiException.unprocessable("fee_type_not_chargeable", "interchange is recorded by clearing, not charged");
        }
        UUID account = ClearingStore.uuid(req, "ledger_account_id");
        if (account == null) {
            JsonNode card = clients.card(cardId).orElseThrow(() -> ApiException.unprocessable("card_unknown",
                    "card " + cardId + " was not found"));
            account = ClearingStore.uuid(card, "ledger_account_id");
        }
        UUID feeId = Ids.newId();
        String desc = ClearingStore.text(req, "description") == null ? feeType.replace('_', ' ').toLowerCase() + " fee"
                : ClearingStore.text(req, "description");
        List<Leg> legs = new ArrayList<>();
        legs.add(new Leg(account, "DEBIT", amount.add(tax), desc));
        legs.add(new Leg(props.clearing().feeIncomeAccount(), "CREDIT", amount, "Card fee income"));
        if (tax.signum() > 0) {
            legs.add(new Leg(props.clearing().taxAccount(), "CREDIT", tax, "Tax on card fee"));
        }
        UUID journal = clients.post(props.ledgerId(), "FEE", desc, currency, "CARD_FEE", feeId, null, legs,
                "card-fee:" + feeId);
        ObjectNode v = store.json().createObjectNode();
        v.put("id", feeId.toString());
        v.put("card_id", cardId.toString());
        v.put("ledger_account_id", account.toString());
        v.put("fee_type", feeType);
        v.put("amount", amount.toPlainString());
        v.put("currency", currency);
        v.put("tax_amount", tax.toPlainString());
        for (String f : List.of("card_authorization_id", "clearing_record_id", "custom_field_1", "custom_field_2",
                "custom_field_3", "custom_field_4", "custom_field_5")) {
            if (ClearingStore.text(req, f) != null) {
                v.put(f, ClearingStore.text(req, f));
            }
        }
        v.put("journal_entry_id", journal.toString());
        v.put("description", desc);
        return tx.execute(s -> {
            JsonNode row = store.insert("card_fee", v);
            events.publish("cards.card_fee.charged", "card_fee", row);
            return row;
        });
    }

    public JsonNode waive(UUID feeId, String reason) {
        JsonNode fee = store.row("card_fee", feeId);
        if (fee.path("waived").asBoolean(false)) {
            throw ApiException.unprocessable("not_waivable", "A waiver cannot be waived");
        }
        if (jdbc.sql("select count(*) from card_fee where waives_fee_id = ?").param(feeId).query(Long.class).single() > 0) {
            throw ApiException.conflict("already_waived", "The fee was already waived");
        }
        String creator = ClearingStore.text(fee, "created_by");
        if (creator != null && creator.equals(TenantContext.principal())) {
            throw ApiException.unprocessable("four_eyes_violation", "A fee cannot be waived by the principal that charged it");
        }
        UUID original = ClearingStore.uuid(fee, "journal_entry_id");
        UUID waiverId = Ids.newId();
        UUID reversal = original == null ? null
                : clients.reverse(original, "Fee waived: " + reason, "card-fee-waive:" + feeId);
        ObjectNode v = store.json().createObjectNode();
        v.put("id", waiverId.toString());
        for (String f : List.of("card_id", "ledger_account_id", "fee_type", "amount", "currency", "tax_amount",
                "card_authorization_id", "clearing_record_id")) {
            if (ClearingStore.text(fee, f) != null) {
                v.put(f, ClearingStore.text(fee, f));
            }
        }
        v.put("waived", true);
        v.put("waives_fee_id", feeId.toString());
        if (reversal != null) {
            v.put("journal_entry_id", reversal.toString());
        }
        String desc = "Waiver of " + feeId + ": " + reason;
        v.put("description", desc.length() > 255 ? desc.substring(0, 255) : desc);
        try {
            return tx.execute(s -> {
                JsonNode row = store.insert("card_fee", v);
                events.publish("cards.card_fee.waived", "card_fee", row);
                return row;
            });
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("already_waived", "The fee was already waived");
        }
    }
}
