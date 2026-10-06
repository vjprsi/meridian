package io.meridian.deposit.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.deposit.api.generated.DepositAccountsApi;
import io.meridian.deposit.api.generated.model.AccountBalance;
import io.meridian.deposit.api.generated.model.AccountBlockPage;
import io.meridian.deposit.api.generated.model.AccountHolderPage;
import io.meridian.deposit.api.generated.model.AccountMandatePage;
import io.meridian.deposit.api.generated.model.AccountStatusHistoryPage;
import io.meridian.deposit.api.generated.model.DepositAccount;
import io.meridian.deposit.api.generated.model.DepositAccountCreate;
import io.meridian.deposit.api.generated.model.DepositAccountsApproveRequest;
import io.meridian.deposit.api.generated.model.DepositAccountsChangeProductRequest;
import io.meridian.deposit.api.generated.model.DepositAccountsChargeFeeRequest;
import io.meridian.deposit.api.generated.model.DepositAccountsFreezeRequest;
import io.meridian.deposit.api.generated.model.DepositAccountsReactivateRequest;
import io.meridian.deposit.api.generated.model.DepositAccountsRecalculateInterestRequest;
import io.meridian.deposit.api.generated.model.DepositAccountsUnfreezeRequest;
import io.meridian.deposit.api.generated.model.FeeCharge;
import io.meridian.deposit.api.generated.model.FeeChargePage;
import io.meridian.deposit.api.generated.model.InterestAccrualPage;
import io.meridian.deposit.api.generated.model.InterestCapitalisationPage;
import io.meridian.deposit.api.generated.model.LedgerEntryPage;
import io.meridian.deposit.application.AccountSupport;
import io.meridian.deposit.application.DepositAccountService;
import io.meridian.deposit.application.FeeService;
import io.meridian.deposit.application.InterestService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Deposit accounts: opening with generated numbers, lifecycle actions, interest and fees, ledger views. */
@RestController
public class DepositAccountsController implements DepositAccountsApi {
    private final DepositAccountService service;
    private final InterestService interest;
    private final FeeService fees;
    private final AccountSupport accounts;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public DepositAccountsController(DepositAccountService service, InterestService interest, FeeService fees,
                                     AccountSupport accounts, CrudEngine crud, Idempotency idempotency, JsonMapper json) {
        this.service = service;
        this.interest = interest;
        this.fees = fees;
        this.accounts = accounts;
        this.crud = crud;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<DepositAccount> depositAccountsCreate(String idempotencyKey, DepositAccountCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, DepositAccount.class,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(model(service.open(body))));
    }

    @Override
    public ResponseEntity<DepositAccount> depositAccountsUpdate(UUID id, String ifMatch,
                                                               io.meridian.deposit.api.generated.model.DepositAccountUpdate body) {
        TenantContext.require();
        JsonNode req = tree(body);
        for (String f : java.util.List.of("account_number", "iban", "bban", "currency", "product_id", "product_version_id",
                "legal_entity_id", "account_type")) {
            if (req.hasNonNull(f)) {
                throw io.meridian.common.error.ApiException.unprocessable("field_not_updatable", f
                        + " cannot be changed with PATCH" + (f.startsWith("product") ? "; use change-product" : ""));
            }
        }
        if (req.hasNonNull("statement_cycle_day") && (req.get("statement_cycle_day").asInt() < 1
                || req.get("statement_cycle_day").asInt() > 31)) {
            throw io.meridian.common.error.ApiException.unprocessable("invalid_statement_cycle_day",
                    "statement_cycle_day must be 1-31");
        }
        return crud.update(AccountSupport.TABLE, DepositAccount.class, body, ifMatch, Crud.params().with("id", id));
    }

    @Override
    public ResponseEntity<DepositAccount> depositAccountsApprove(UUID id, String idempotencyKey,
                                                                DepositAccountsApproveRequest body) {
        JsonNode req = tree(body);
        return action(idempotencyKey, id, "approve", body, () -> service.approve(id, Http.text(req, "comment")));
    }

    @Override
    public ResponseEntity<DepositAccount> depositAccountsActivate(UUID id, String idempotencyKey) {
        return action(idempotencyKey, id, "activate", null, () -> service.activate(id));
    }

    @Override
    public ResponseEntity<DepositAccount> depositAccountsFreeze(UUID id, String idempotencyKey,
                                                               DepositAccountsFreezeRequest body) {
        return action(idempotencyKey, id, "freeze", body, () -> service.freeze(id, Http.text(tree(body), "reason")));
    }

    @Override
    public ResponseEntity<DepositAccount> depositAccountsUnfreeze(UUID id, String idempotencyKey,
                                                                 DepositAccountsUnfreezeRequest body) {
        return action(idempotencyKey, id, "unfreeze", body, () -> service.unfreeze(id, Http.text(tree(body), "reason")));
    }

    @Override
    public ResponseEntity<DepositAccount> depositAccountsReactivate(UUID id, String idempotencyKey,
                                                                   DepositAccountsReactivateRequest body) {
        return action(idempotencyKey, id, "reactivate", body, () -> service.reactivate(id, Http.text(tree(body), "reason")));
    }

    @Override
    public ResponseEntity<DepositAccount> depositAccountsChangeProduct(UUID id, String idempotencyKey,
                                                                      DepositAccountsChangeProductRequest body) {
        JsonNode req = tree(body);
        return action(idempotencyKey, id, "change-product", body, () -> service.changeProduct(id,
                UUID.fromString(Http.text(req, "product_id")), UUID.fromString(Http.text(req, "product_version_id")),
                LocalDate.parse(Http.text(req, "effective_date"))));
    }

    @Override
    public ResponseEntity<DepositAccount> depositAccountsCapitaliseInterest(UUID id, String idempotencyKey) {
        return action(idempotencyKey, id, "capitalise-interest", null, () -> interest.capitalise(id, true).account());
    }

    @Override
    public ResponseEntity<DepositAccount> depositAccountsRecalculateInterest(UUID id, String idempotencyKey,
                                                                            DepositAccountsRecalculateInterestRequest body) {
        JsonNode req = tree(body);
        return action(idempotencyKey, id, "recalculate-interest", body, () -> interest.recalculate(id,
                LocalDate.parse(Http.text(req, "from_date")), Http.text(req, "reason")));
    }

    @Override
    public ResponseEntity<FeeCharge> depositAccountsChargeFee(UUID id, String idempotencyKey,
                                                              DepositAccountsChargeFeeRequest body) {
        TenantContext.require();
        JsonNode req = tree(body);
        return idempotency.execute(idempotencyKey, Map.of("id", id, "body", req), FeeCharge.class,
                () -> ResponseEntity.ok(json.treeToValue(fees.charge(id,
                        Http.text(req, "fee_code"), Http.decimal(req, "amount"), Http.text(req, "reason"), idempotencyKey),
                        FeeCharge.class)));
    }

    @Override
    public ResponseEntity<AccountBalance> depositAccountsBalance(UUID id) {
        TenantContext.require();
        return ResponseEntity.ok(json.treeToValue(service.balance(id), AccountBalance.class));
    }

    @Override
    public ResponseEntity<LedgerEntryPage> depositAccountsTransactions(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        return ResponseEntity.ok(json.treeToValue(service.transactions(id, Http.queryTime("from"), Http.queryTime("to"),
                limit, cursor), LedgerEntryPage.class));
    }

    @Override
    public ResponseEntity<Void> depositAccountsInterestProjection(UUID id) {
        TenantContext.require();
        return Http.json(json, interest.projection(id, Http.queryInt("days", 30)));
    }

    @Override
    public ResponseEntity<Void> depositAccountsConfirmationLetter(UUID id) {
        TenantContext.require();
        return Http.text(service.confirmationLetter(id), "confirmation-letter-" + id + ".txt");
    }

    @Override
    public ResponseEntity<AccountHolderPage> depositAccountsHolders(UUID id, Integer limit, String cursor) {
        return child("account_holder", AccountHolderPage.class, id, limit, cursor);
    }

    @Override
    public ResponseEntity<AccountMandatePage> depositAccountsMandates(UUID id, Integer limit, String cursor) {
        return child("account_mandate", AccountMandatePage.class, id, limit, cursor);
    }

    @Override
    public ResponseEntity<AccountStatusHistoryPage> depositAccountsStatusHistory(UUID id, Integer limit, String cursor) {
        return child("account_status_history", AccountStatusHistoryPage.class, id, limit, cursor);
    }

    @Override
    public ResponseEntity<AccountBlockPage> depositAccountsBlocks(UUID id, Integer limit, String cursor) {
        return child("account_block", AccountBlockPage.class, id, limit, cursor);
    }

    @Override
    public ResponseEntity<InterestAccrualPage> depositAccountsAccruals(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        accounts.get(id);
        return crud.list("interest_accrual", InterestAccrualPage.class, Crud.params().with("deposit_account_id", id)
                .with("accrual_date", Http.query("accrual_date")).with("kind", Http.query("kind"))
                .with("limit", limit).with("cursor", cursor));
    }

    @Override
    public ResponseEntity<InterestCapitalisationPage> depositAccountsCapitalisations(UUID id, Integer limit, String cursor) {
        return child("interest_capitalisation", InterestCapitalisationPage.class, id, limit, cursor);
    }

    @Override
    public ResponseEntity<FeeChargePage> depositAccountsFees(UUID id, Integer limit, String cursor) {
        return child("fee_charge", FeeChargePage.class, id, limit, cursor);
    }

    // ------------------------------------------------------------------ helpers

    private <T> ResponseEntity<T> child(String table, Class<T> page, UUID id, Integer limit, String cursor) {
        TenantContext.require();
        accounts.get(id); // 404 for an unknown account rather than an empty page
        return crud.list(table, page, Crud.params().with("deposit_account_id", id).with("limit", limit).with("cursor", cursor));
    }

    private ResponseEntity<DepositAccount> action(String key, UUID id, String action, Object body,
                                                  Supplier<JsonNode> work) {
        TenantContext.require();
        Map<String, Object> fingerprint = body == null ? Map.of("id", id, "action", action)
                : Map.of("id", id, "action", action, "body", tree(body));
        return idempotency.execute(key, fingerprint, DepositAccount.class, () -> ResponseEntity.ok(model(work.get())));
    }

    private DepositAccount model(JsonNode row) {
        return json.treeToValue(row, DepositAccount.class);
    }

    private JsonNode tree(Object body) {
        return body == null ? json.createObjectNode() : json.valueToTree(body);
    }
}
