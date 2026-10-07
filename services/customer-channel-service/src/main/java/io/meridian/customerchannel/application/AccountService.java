package io.meridian.customerchannel.application;



import static io.meridian.customerchannel.application.ProfileService.required;
import static io.meridian.customerchannel.application.ProfileService.text;

import io.meridian.common.error.ApiException;
import io.meridian.customerchannel.application.Downstream.Query;
import io.meridian.customerchannel.application.Ownership.Access;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Deposit accounts, their balances, entries, statements and confirmation letters, and standing orders. */
@Service
public class AccountService {
    private static final String DEPOSITS = "deposit-service";
    private static final String STATEMENTS = "statement-service";

    private final Downstream down;
    private final Ownership own;
    private final StepUp stepUp;
    private final ActionAudit audit;

    public AccountService(Downstream down, Ownership own, StepUp stepUp, ActionAudit audit) {
        this.down = down;
        this.own = own;
        this.stepUp = stepUp;
        this.audit = audit;
    }

    public JsonNode accounts() {
        return down.page(own.accounts());
    }

    public JsonNode holdings() {
        return down.page(own.holdings());
    }

    public JsonNode account(UUID id) {
        return own.account(id, Access.VIEW, "myAccountsGet");
    }

    public JsonNode balance(UUID id) {
        own.account(id, Access.VIEW, "myAccountsBalance");
        return down.get(DEPOSITS, "/v1/deposit-accounts/" + id + "/balance");
    }

    public JsonNode transactions(UUID id, OffsetDateTime from, OffsetDateTime to, Integer limit, String cursor) {
        own.account(id, Access.VIEW, "myAccountsTransactions");
        return down.get(DEPOSITS, "/v1/deposit-accounts/" + id + "/transactions",
                Query.of("from", from).with("to", to).paging(limit, cursor));
    }

    // ------------------------------------------------------------------ statements

    public JsonNode statements(UUID accountId, Integer limit, String cursor) {
        own.account(accountId, Access.VIEW, "myAccountsStatements");
        return down.page(down.get(STATEMENTS, "/v1/statements",
                        Query.of("deposit_account_id", accountId).paging(limit, cursor)),
                r -> accountId.toString().equals(r.path("deposit_account_id").asString(null)));
    }

    public JsonNode requestStatement(UUID accountId, JsonNode req, String key) {
        JsonNode account = own.account(accountId, Access.VIEW, "myAccountsAddStatements");
        LocalDate start = LocalDate.parse(required(req, "period_start"));
        LocalDate end = LocalDate.parse(required(req, "period_end"));
        if (end.isBefore(start)) {
            throw ApiException.unprocessable("invalid_period", "period_end must not be before period_start");
        }
        String format = text(req, "format");
        ObjectNode body = down.json().createObjectNode()
                .put("deposit_account_id", accountId.toString())
                .put("period_start", start.toString()).put("period_end", end.toString())
                .put("format", format == null ? "PDF" : format)
                .put("currency", account.path("currency").asString())
                .put("is_on_demand", true);
        JsonNode created = down.post(STATEMENTS, "/v1/statements", body, key, "create");
        audit.succeeded("myAccountsAddStatements", "ACCOUNT", accountId, created.path("id").asString(null));
        return created;
    }

    public ServiceClient.Download statementFile(UUID accountId, UUID statementId) {
        own.account(accountId, Access.VIEW, "myAccountsStatementsDownload");
        own.statement(accountId, statementId, "myAccountsStatementsDownload");
        return down.bytes(STATEMENTS, "/v1/statements/" + statementId + "/download");
    }

    public ServiceClient.Download confirmationLetter(UUID accountId) {
        own.account(accountId, Access.VIEW, "myAccountsConfirmationLetter");
        return down.bytes(DEPOSITS, "/v1/deposit-accounts/" + accountId + "/confirmation-letter");
    }

    // ------------------------------------------------------------------ standing orders

    public JsonNode standingOrders(UUID accountId, Integer limit, String cursor) {
        if (accountId != null) {
            own.account(accountId, Access.VIEW, "myStandingOrdersList");
            return down.page(down.get(DEPOSITS, "/v1/standing-orders",
                            Query.of("deposit_account_id", accountId).paging(limit, cursor)),
                    r -> accountId.toString().equals(r.path("deposit_account_id").asString(null)));
        }
        List<JsonNode> rows = new ArrayList<>();
        for (JsonNode a : own.accounts()) {
            String id = a.path("id").asString();
            for (JsonNode o : down.all(DEPOSITS, "/v1/standing-orders", Query.of("deposit_account_id", id), 500)) {
                if (id.equals(o.path("deposit_account_id").asString(null))) {
                    rows.add(o);
                }
            }
        }
        return down.page(rows);
    }

    /** Built server-side: the debit account must be transactable by the customer, the target theirs. */
    public JsonNode createStandingOrder(JsonNode req, String key) {
        String action = "myStandingOrdersCreate";
        UUID from = uuid(req, "account_id", true);
        JsonNode account = own.account(from, Access.TRANSACT, action);
        UUID payeeId = uuid(req, "payee_id", false);
        UUID beneficiary = uuid(req, "beneficiary_account_id", false);
        if ((payeeId == null) == (beneficiary == null)) {
            throw ApiException.unprocessable("target_required", "Give exactly one of payee_id or beneficiary_account_id");
        }
        if (payeeId != null) {
            JsonNode payee = own.payee(payeeId, action);
            if (!"ACTIVE".equals(payee.path("status").asString(""))) {
                throw ApiException.unprocessable("payee_not_active", "The payee is not active yet");
            }
        } else {
            if (beneficiary.equals(from)) {
                throw ApiException.unprocessable("same_account", "Debit and beneficiary account are the same");
            }
            own.account(beneficiary, Access.VIEW, action);
        }
        BigDecimal amount = positive(req, "amount");
        stepUp.require(action, "ACCOUNT", from);
        ObjectNode body = down.json().createObjectNode()
                .put("deposit_account_id", from.toString())
                .put("amount", amount.toPlainString())
                .put("currency", account.path("currency").asString())
                .put("frequency", required(req, "frequency"))
                .put("start_date", LocalDate.parse(required(req, "start_date")).toString())
                .put("reference", text(req, "reference"));
        if (payeeId != null) {
            body.put("payee_id", payeeId.toString());
        } else {
            body.put("beneficiary_account_id", beneficiary.toString());
        }
        String end = text(req, "end_date");
        if (end != null) {
            body.put("end_date", LocalDate.parse(end).toString());
        }
        JsonNode created = down.post(DEPOSITS, "/v1/standing-orders", body, key, "create");
        audit.succeeded(action, "ACCOUNT", from, created.path("id").asString(null));
        return created;
    }

    public JsonNode standingOrderAction(UUID id, String op, String key) {
        String action = switch (op) {
            case "pause" -> "myStandingOrdersPause";
            case "resume" -> "myStandingOrdersResume";
            default -> "myStandingOrdersCancel";
        };
        own.standingOrder(id, Access.TRANSACT, action);
        if (op.equals("resume")) {
            stepUp.require(action, "STANDING_ORDER", id);            // restarts money movement
        }
        ObjectNode body = op.equals("resume") ? null : down.json().createObjectNode().put("reason", "Customer request");
        JsonNode r = down.post(DEPOSITS, "/v1/standing-orders/" + id + "/" + op, body, key, op);
        audit.succeeded(action, "STANDING_ORDER", id, null);
        return r;
    }

    // ------------------------------------------------------------------ helpers

    static UUID uuid(JsonNode req, String field, boolean required) {
        String v = text(req, field);
        if (v == null || v.isBlank()) {
            if (required) {
                throw ApiException.unprocessable("missing_field", field + " is required");
            }
            return null;
        }
        try {
            return UUID.fromString(v);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_value", field + " must be a UUID");
        }
    }

    static BigDecimal positive(JsonNode req, String field) {
        BigDecimal amount;
        try {
            amount = new BigDecimal(required(req, field));
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("invalid_value", field + " must be a decimal number");
        }
        if (amount.signum() <= 0) {
            throw ApiException.unprocessable("invalid_amount", field + " must be greater than zero");
        }
        return amount;
    }
}
