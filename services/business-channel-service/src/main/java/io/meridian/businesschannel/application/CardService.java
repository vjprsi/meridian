package io.meridian.businesschannel.application;

import static io.meridian.businesschannel.application.CurrentUser.business;
import static io.meridian.businesschannel.application.CurrentUser.userId;

import io.meridian.businesschannel.application.Access.Need;
import io.meridian.businesschannel.application.Downstream.Query;
import io.meridian.common.error.ApiException;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Expense cards for employees: virtual debit cards issued under the business ({@code business_customer_id} and
 * {@code customer_id} = the business, cardholder name = the employee) on one of its accounts, with channel blocks and
 * spend limits. Issuing and controlling cards is for ADMIN users; others see the cards of accounts they may see.
 */
@Service
public class CardService {
    static final String CARDS = "card-service";
    static final Set<String> HIDDEN = Set.of("CLOSED", "REPLACED");
    static final Set<String> CHANNELS = Set.of("ECOM", "POS", "ATM", "CONTACTLESS");

    private final Downstream down;
    private final Access access;
    private final StepUp stepUp;
    private final ActionAudit audit;

    public CardService(Downstream down, Access access, StepUp stepUp, ActionAudit audit) {
        this.down = down;
        this.access = access;
        this.stepUp = stepUp;
        this.audit = audit;
    }

    public JsonNode cards(Integer limit, String cursor) {
        Set<UUID> viewable = CurrentUser.admin() ? null : access.viewableAccountIds();
        return down.page(down.get(CARDS, "/v1/cards", Query.of("customer_id", business()).paging(limit, cursor)),
                r -> Access.businessCard(r) && !HIDDEN.contains(r.path("status").asString(""))
                        && (viewable == null || viewable.contains(Req.rowUuid(r, "deposit_account_id"))));
    }

    public JsonNode products(Integer limit, String cursor) {
        return down.page(down.get(CARDS, "/v1/card-products", Query.of("status", "ACTIVE").paging(limit, cursor)),
                r -> "ACTIVE".equals(r.path("status").asString("")));
    }

    public JsonNode card(UUID id) {
        return access.card(id, "businessCardsGet");
    }

    public JsonNode issue(JsonNode req, String key) {
        String action = "businessCardsCreate";
        access.requireAdmin(action, "CARD", null);
        UUID accountId = Req.uuid(req, "deposit_account_id", true);
        JsonNode account = access.account(accountId, Need.VIEW, action);
        if (!"ACTIVE".equals(account.path("status").asString(""))) {
            throw ApiException.unprocessable("account_not_active", "The account is " + account.path("status").asString());
        }
        String ledgerAccount = account.path("ledger_account_id").asString(null);
        if (ledgerAccount == null) {
            throw ApiException.unprocessable("account_not_ready", "The account has no ledger account yet");
        }
        UUID productId = Req.uuid(req, "card_product_id", true);
        JsonNode product = access.fetch(CARDS, "/v1/card-products/" + productId, "CARD_PRODUCT", productId);
        if (!"ACTIVE".equals(product.path("status").asString(""))) {
            throw ApiException.unprocessable("product_not_active", "The card product is not active");
        }
        String name = Req.required(req, "cardholder_name").toUpperCase();
        ObjectNode body = down.json().createObjectNode()
                .put("card_program_id", product.path("card_program_id").asString())
                .put("card_product_id", productId.toString())
                .put("customer_id", business().toString())
                .put("business_customer_id", business().toString())
                .put("cardholder_name", name)
                .put("form_factor", "VIRTUAL")
                .put("linked_account_type", "DEPOSIT_ACCOUNT")
                .put("ledger_account_id", ledgerAccount)
                .put("deposit_account_id", accountId.toString())
                .put("nickname", Req.text(req, "nickname"));
        body.putObject("metadata").put("issued_via", "business-portal").put("issued_by", userId().toString());
        stepUp.require(action, "ACCOUNT", accountId);
        JsonNode created = down.post(CARDS, "/v1/cards", body, key, "issue");
        audit.succeeded(action, "ACCOUNT", accountId, created.path("id").asString(null));
        return created;
    }

    public JsonNode freeze(UUID id, String key) {
        String action = "businessCardsFreeze";
        access.requireAdmin(action, "CARD", id);
        access.card(id, action);
        JsonNode r = down.post(CARDS, "/v1/cards/" + id + "/freeze",
                down.json().createObjectNode().put("reason", "Frozen in the business portal"), key, "freeze");
        audit.succeeded(action, "CARD", id, null);
        return r;
    }

    public JsonNode unfreeze(UUID id, String key) {
        String action = "businessCardsUnfreeze";
        access.requireAdmin(action, "CARD", id);
        access.card(id, action);
        stepUp.require(action, "CARD", id);
        JsonNode r = down.post(CARDS, "/v1/cards/" + id + "/unfreeze", null, key, "unfreeze");
        audit.succeeded(action, "CARD", id, null);
        return r;
    }

    public JsonNode close(UUID id, JsonNode req, String key) {
        String action = "businessCardsClose";
        access.requireAdmin(action, "CARD", id);
        access.card(id, action);
        stepUp.require(action, "CARD", id);
        String reason = Req.text(req, "reason");
        JsonNode r = down.post(CARDS, "/v1/cards/" + id + "/close", down.json().createObjectNode()
                .put("reason", reason == null ? "Closed in the business portal" : reason), key, "close");
        audit.succeeded(action, "CARD", id, null);
        return r;
    }

    public JsonNode controls(UUID id, Integer limit, String cursor) {
        access.card(id, "businessCardsControls");
        return down.page(down.get(CARDS, "/v1/cards/" + id + "/controls", Query.none().paging(limit, cursor)),
                r -> id.toString().equals(r.path("card_id").asString(null)));
    }

    public JsonNode authorizations(UUID id, Integer limit, String cursor) {
        access.card(id, "businessCardsAuthorizations");
        return down.page(down.get(CARDS, "/v1/cards/" + id + "/authorizations", Query.none().paging(limit, cursor)),
                r -> !r.has("card_id") || id.toString().equals(r.path("card_id").asString(null)));
    }

    /** Creates or updates the card's CHANNEL_DENY control for one channel. */
    public JsonNode setChannel(UUID id, String channel, boolean blocked, String key) {
        String action = "businessCardsChannels";
        access.requireAdmin(action, "CARD", id);
        access.card(id, action);
        if (!CHANNELS.contains(channel)) {
            throw ApiException.unprocessable("invalid_channel", "channel must be one of " + CHANNELS);
        }
        JsonNode existing = null;
        for (JsonNode c : down.all(CARDS, "/v1/cards/" + id + "/controls", Query.none(), 500)) {
            if (id.toString().equals(c.path("card_id").asString(null))
                    && "CHANNEL_DENY".equals(c.path("control_type").asString(null))
                    && channel.equals(c.path("channel").asString(null))) {
                existing = c;
                break;
            }
        }
        JsonNode result = existing != null
                ? down.patch(CARDS, "/v1/card-controls/" + existing.path("id").asString(),
                        down.json().createObjectNode().put("is_active", blocked), existing.path("row_version").asInt())
                : down.post(CARDS, "/v1/card-controls", down.json().createObjectNode()
                        .put("card_id", id.toString()).put("control_type", "CHANNEL_DENY").put("channel", channel)
                        .put("is_active", blocked), key, "control-" + channel);
        audit.succeeded(action, "CARD", id, result.path("id").asString(null));
        return result;
    }

    public JsonNode limits(UUID id, Integer limit, String cursor) {
        access.card(id, "businessCardsLimits");
        return down.page(down.get(CARDS, "/v1/cards/" + id + "/limits", Query.none().paging(limit, cursor)),
                r -> id.toString().equals(r.path("card_id").asString(null)));
    }

    /** Creates or updates the card's limit for one period and channel, in the linked account's currency. */
    public JsonNode setLimit(UUID id, JsonNode req, String key) {
        String action = "businessCardsReplaceLimits";
        access.requireAdmin(action, "CARD", id);
        JsonNode card = access.card(id, action);
        String period = Req.required(req, "period");
        String channel = Req.text(req, "channel") == null ? "ANY" : Req.text(req, "channel");
        String amount = Req.amount(req, "max_amount").toPlainString();
        UUID accountId = Req.rowUuid(card, "deposit_account_id");
        String currency = accountId == null ? null
                : access.businessAccount(accountId, action).path("currency").asString(null);
        stepUp.require(action, "CARD", id);
        JsonNode existing = null;
        for (JsonNode l : down.all(CARDS, "/v1/cards/" + id + "/limits", Query.none(), 500)) {
            if (id.toString().equals(l.path("card_id").asString(null)) && period.equals(l.path("period").asString(null))
                    && channel.equals(l.path("channel").asString(null))) {
                existing = l;
                break;
            }
        }
        JsonNode result;
        if (existing != null) {
            result = down.patch(CARDS, "/v1/card-spend-limits/" + existing.path("id").asString(),
                    down.json().createObjectNode().put("max_amount", amount).put("is_active", true),
                    existing.path("row_version").asInt());
        } else {
            if (currency == null) {
                throw ApiException.unprocessable("currency_unknown", "The card has no linked account currency");
            }
            result = down.post(CARDS, "/v1/card-spend-limits", down.json().createObjectNode()
                    .put("card_id", id.toString()).put("period", period).put("channel", channel)
                    .put("max_amount", amount).put("currency", currency).put("is_active", true), key, "limit");
        }
        audit.succeeded(action, "CARD", id, result.path("id").asString(null));
        return result;
    }
}
