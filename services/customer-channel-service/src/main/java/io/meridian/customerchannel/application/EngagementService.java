package io.meridian.customerchannel.application;

import static io.meridian.customerchannel.application.AccountService.positive;
import static io.meridian.customerchannel.application.AccountService.uuid;
import static io.meridian.customerchannel.application.Ownership.me;
import static io.meridian.customerchannel.application.Ownership.mine;
import static io.meridian.customerchannel.application.ProfileService.required;
import static io.meridian.customerchannel.application.ProfileService.text;

import io.meridian.common.error.ApiException;
import io.meridian.customerchannel.application.Downstream.Query;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Insights (spending, insight cards), budgets, rewards, announcements and notifications of the customer. */
@Service
public class EngagementService {
    private static final String INSIGHTS = "insights-service";
    private static final String REWARDS = "rewards-service";
    private static final String INBOX = "inbox-service";
    private static final String NOTIFY = "notification-service";
    private static final Set<String> HIDDEN_INSIGHTS = Set.of("DISMISSED", "EXPIRED");

    private final Downstream down;
    private final Ownership own;
    private final ActionAudit audit;

    public EngagementService(Downstream down, Ownership own, ActionAudit audit) {
        this.down = down;
        this.own = own;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ insights

    public JsonNode spending(LocalDate from, LocalDate to, String currency) {
        if (to.isBefore(from)) {
            throw ApiException.unprocessable("invalid_period", "to must not be before from");
        }
        JsonNode r = down.get(INSIGHTS, "/v1/insights/spending/by-category",
                Query.of("customer_id", me()).with("from", from).with("to", to).with("currency", currency));
        String owner = r.path("customer_id").asString(null);
        if (owner != null && !owner.equals(me().toString())) {
            throw Ownership.notFound("CUSTOMER", me());
        }
        return r;
    }

    public JsonNode categories(Integer limit, String cursor) {
        return down.get(INSIGHTS, "/v1/insights/categories", Query.of("is_active", true).paging(limit, cursor));
    }

    public JsonNode insightCards(Integer limit, String cursor) {
        return down.page(down.get(INSIGHTS, "/v1/insights/cards", Query.of("customer_id", me()).paging(limit, cursor)),
                r -> mine(r, "customer_id") && !HIDDEN_INSIGHTS.contains(r.path("delivery_status").asString("")));
    }

    public JsonNode dismissInsight(UUID id, String key) {
        own.insight(id, "myInsightsCardsDismiss");
        JsonNode r = down.post(INSIGHTS, "/v1/insights/cards/" + id + "/dismiss", null, key, "dismiss");
        audit.succeeded("myInsightsCardsDismiss", "INSIGHT", id, null);
        return r;
    }

    // ------------------------------------------------------------------ budgets

    public JsonNode budgets(Integer limit, String cursor) {
        return down.page(down.get(INSIGHTS, "/v1/insights/budgets", Query.of("customer_id", me()).paging(limit, cursor)),
                r -> mine(r, "customer_id") && !"ARCHIVED".equals(r.path("status").asString("")));
    }

    public JsonNode createBudget(JsonNode req, String key) {
        UUID category = uuid(req, "spend_category_id", false);
        String starts = text(req, "starts_on");
        ObjectNode body = down.json().createObjectNode()
                .put("customer_id", me().toString())
                .put("name", required(req, "name"))
                .put("amount", positive(req, "amount").toPlainString())
                .put("currency", required(req, "currency").toUpperCase())
                .put("period_type", text(req, "period_type") == null ? "MONTHLY" : text(req, "period_type"))
                .put("starts_on", starts == null ? LocalDate.now().toString() : LocalDate.parse(starts).toString())
                .put("notify_customer", true);
        if (category != null) {
            body.put("spend_category_id", category.toString());
        }
        JsonNode created = down.post(INSIGHTS, "/v1/insights/budgets", body, key, "create");
        audit.succeeded("myBudgetsCreate", "BUDGET", null, created.path("id").asString(null));
        return created;
    }

    public JsonNode currentPeriod(UUID id) {
        own.budget(id, "myBudgetsCurrentPeriod");
        return down.get(INSIGHTS, "/v1/insights/budgets/" + id + "/current-period");
    }

    public JsonNode archiveBudget(UUID id, String key) {
        own.budget(id, "myBudgetsArchive");
        JsonNode r = down.post(INSIGHTS, "/v1/insights/budgets/" + id + "/archive", null, key, "archive");
        audit.succeeded("myBudgetsArchive", "BUDGET", id, null);
        return r;
    }

    // ------------------------------------------------------------------ rewards

    public JsonNode pointsAccounts(Integer limit, String cursor) {
        return down.page(down.get(REWARDS, "/v1/rewards/points-accounts", Query.of("customer_id", me())
                .paging(limit, cursor)), r -> mine(r, "customer_id"));
    }

    public JsonNode pointsEntries(UUID id, Integer limit, String cursor) {
        own.pointsAccount(id, "myRewardsPointsAccountsEntries");
        return down.get(REWARDS, "/v1/rewards/points-accounts/" + id + "/entries", Query.none().paging(limit, cursor));
    }

    public JsonNode cashback(Integer limit, String cursor) {
        return down.page(down.get(REWARDS, "/v1/rewards/cashback", Query.of("customer_id", me()).paging(limit, cursor)),
                r -> mine(r, "customer_id"));
    }

    // ------------------------------------------------------------------ announcements, notifications

    public JsonNode announcements(String app, String placement, String language, Integer limit, String cursor) {
        return down.get(INBOX, "/v1/inbox/announcements/active", Query.of("customer_id", me()).with("app", app)
                .with("placement", placement).with("language", language).paging(limit, cursor));
    }

    public JsonNode notifications(String channel, Integer limit, String cursor) {
        return down.page(down.get(NOTIFY, "/v1/notifications", Query.of("recipient_type", "CUSTOMER")
                        .with("recipient_id", me()).with("channel", channel).paging(limit, cursor)),
                r -> "CUSTOMER".equals(r.path("recipient_type").asString(null)) && mine(r, "recipient_id"));
    }

    public JsonNode markRead(UUID id, String key) {
        own.notification(id, "myNotificationsMarkRead");
        return down.post(NOTIFY, "/v1/notifications/" + id + "/mark-read", null, key, "read");
    }
}
