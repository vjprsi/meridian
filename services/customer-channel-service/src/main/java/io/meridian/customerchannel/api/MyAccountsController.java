package io.meridian.customerchannel.api;

import io.meridian.customerchannel.api.generated.MyAccountsApi;
import io.meridian.customerchannel.api.generated.model.AccountBalance;
import io.meridian.customerchannel.api.generated.model.AccountHolderPage;
import io.meridian.customerchannel.api.generated.model.DepositAccount;
import io.meridian.customerchannel.api.generated.model.DepositAccountPage;
import io.meridian.customerchannel.api.generated.model.LedgerEntryPage;
import io.meridian.customerchannel.api.generated.model.MyAccountsAddStatementsRequest;
import io.meridian.customerchannel.api.generated.model.Statement;
import io.meridian.customerchannel.api.generated.model.StatementPage;
import io.meridian.customerchannel.application.AccountService;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Accounts the customer owns or holds, with balances, entries, statements and confirmation letters. */
@RestController
public class MyAccountsController implements MyAccountsApi {
    private final AccountService accounts;
    private final Http http;

    public MyAccountsController(AccountService accounts, Http http) {
        this.accounts = accounts;
        this.http = http;
    }

    @Override
    public ResponseEntity<DepositAccountPage> myAccountsList(Integer limit, String cursor) {
        return http.ok(accounts.accounts(), DepositAccountPage.class);
    }

    @Override
    public ResponseEntity<AccountHolderPage> myAccountsHoldings(Integer limit, String cursor) {
        return http.ok(accounts.holdings(), AccountHolderPage.class);
    }

    @Override
    public ResponseEntity<DepositAccount> myAccountsGet(UUID accountId) {
        return http.ok(accounts.account(accountId), DepositAccount.class);
    }

    @Override
    public ResponseEntity<AccountBalance> myAccountsBalance(UUID accountId) {
        return http.ok(accounts.balance(accountId), AccountBalance.class);
    }

    @Override
    public ResponseEntity<LedgerEntryPage> myAccountsTransactions(UUID accountId, OffsetDateTime from,
                                                                  OffsetDateTime to, Integer limit, String cursor) {
        return http.ok(accounts.transactions(accountId, from, to, limit, cursor), LedgerEntryPage.class);
    }

    @Override
    public ResponseEntity<StatementPage> myAccountsStatements(UUID accountId, Integer limit, String cursor) {
        return http.ok(accounts.statements(accountId, limit, cursor), StatementPage.class);
    }

    @Override
    public ResponseEntity<Statement> myAccountsAddStatements(UUID accountId, String idempotencyKey,
                                                             MyAccountsAddStatementsRequest body) {
        return http.ok(accounts.requestStatement(accountId, http.body(body), idempotencyKey), Statement.class);
    }

    @Override
    public ResponseEntity<Resource> myAccountsStatementsDownload(UUID accountId, UUID statementId) {
        return Http.binary(accounts.statementFile(accountId, statementId), "statement-" + statementId);
    }

    @Override
    public ResponseEntity<String> myAccountsConfirmationLetter(UUID accountId) {
        return Http.text(accounts.confirmationLetter(accountId), "confirmation-" + accountId + ".txt");
    }
}
