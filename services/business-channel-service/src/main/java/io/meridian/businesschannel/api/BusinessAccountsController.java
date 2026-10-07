package io.meridian.businesschannel.api;

import io.meridian.businesschannel.api.generated.BusinessAccountsApi;
import io.meridian.businesschannel.api.generated.model.AccountBalance;
import io.meridian.businesschannel.api.generated.model.BusinessAccountsAddStatementsRequest;
import io.meridian.businesschannel.api.generated.model.DepositAccount;
import io.meridian.businesschannel.api.generated.model.DepositAccountPage;
import io.meridian.businesschannel.api.generated.model.LedgerEntryPage;
import io.meridian.businesschannel.api.generated.model.Statement;
import io.meridian.businesschannel.api.generated.model.StatementPage;
import io.meridian.businesschannel.application.AccountService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Accounts of the business: balances, entries, statements and accounting exports. */
@RestController
public class BusinessAccountsController implements BusinessAccountsApi {
    private final AccountService accounts;
    private final Http http;

    public BusinessAccountsController(AccountService accounts, Http http) {
        this.accounts = accounts;
        this.http = http;
    }

    @Override
    public ResponseEntity<DepositAccountPage> businessAccountsList(Integer limit, String cursor) {
        return http.ok(accounts.accounts(), DepositAccountPage.class);
    }

    @Override
    public ResponseEntity<DepositAccount> businessAccountsGet(UUID accountId) {
        return http.ok(accounts.account(accountId), DepositAccount.class);
    }

    @Override
    public ResponseEntity<AccountBalance> businessAccountsBalance(UUID accountId) {
        return http.ok(accounts.balance(accountId), AccountBalance.class);
    }

    @Override
    public ResponseEntity<LedgerEntryPage> businessAccountsTransactions(UUID accountId, OffsetDateTime from,
                                                                        OffsetDateTime to, Integer limit, String cursor) {
        return http.ok(accounts.transactions(accountId, from, to, limit, cursor), LedgerEntryPage.class);
    }

    @Override
    public ResponseEntity<StatementPage> businessAccountsStatements(UUID accountId, Integer limit, String cursor) {
        return http.ok(accounts.statements(accountId, limit, cursor), StatementPage.class);
    }

    @Override
    public ResponseEntity<Statement> businessAccountsAddStatements(UUID accountId, String idempotencyKey,
                                                                   BusinessAccountsAddStatementsRequest body) {
        return http.ok(accounts.requestStatement(accountId, http.body(body), idempotencyKey), Statement.class);
    }

    @Override
    public ResponseEntity<Resource> businessAccountsStatementsDownload(UUID accountId, UUID statementId) {
        return Http.binary(accounts.statementFile(accountId, statementId), "statement-" + statementId);
    }

    @Override
    public ResponseEntity<String> businessAccountsExport(UUID accountId, LocalDate from, LocalDate to, String format) {
        AccountService.Export e = accounts.export(accountId, from, to, format);
        return Http.file(e.content(), e.contentType(), e.fileName());
    }
}
