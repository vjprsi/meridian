package io.meridian.deposit.application;

import static io.meridian.deposit.infrastructure.Rows.decOr0;
import static io.meridian.deposit.infrastructure.Rows.str;
import static io.meridian.deposit.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.deposit.config.DepositProperties;
import io.meridian.deposit.domain.ClosureCalculator;
import io.meridian.deposit.domain.Currencies;
import io.meridian.deposit.domain.FeeCalculator;
import io.meridian.deposit.domain.Lifecycle;
import io.meridian.deposit.infrastructure.Rows;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Account closure: quote (balance + net accrued interest - debit interest - closure fee), request (account moves
 * to CLOSING), four-eyes approval that settles interest and fees, pays out the balance through the ledger and
 * closes the account only once the ledger balance is zero.
 */
@Service
public class ClosureService {
    static final String CLOSURE_FEE = "ACCOUNT_CLOSURE";
    private static final Set<String> CLOSABLE = Set.of("ACTIVE", "INACTIVE", "DORMANT", "PENDING_FUNDING",
            "BLOCKED_CREDIT");

    private final Rows rows;
    private final AccountSupport accounts;
    private final LedgerPort ledger;
    private final Events events;
    private final InterestService interest;
    private final FeeService fees;
    private final DepositProperties props;

    public ClosureService(Rows rows, AccountSupport accounts, LedgerPort ledger, Events events, InterestService interest,
                          FeeService fees, DepositProperties props) {
        this.rows = rows;
        this.accounts = accounts;
        this.ledger = ledger;
        this.events = events;
        this.interest = interest;
        this.fees = fees;
        this.props = props;
    }

    public ObjectNode quote(UUID accountId) {
        ObjectNode acc = accounts.reference(accountId, "deposit_account_id");
        return quoteFor(acc);
    }

    private ObjectNode quoteFor(ObjectNode acc) {
        UUID accountId = uuid(acc, "id");
        String ccy = str(acc, "currency");
        UUID ledgerAccount = uuid(acc, "ledger_account_id");
        JsonNode balance = ledgerAccount == null ? null : ledger.balance(ledgerAccount);
        BigDecimal posted = balance == null ? BigDecimal.ZERO : LedgerPort.posted(balance);
        Optional<FeeCalculator.FeeDefinition> fee = ProductSnapshot.of(acc).fee(CLOSURE_FEE);
        BigDecimal feeAmount = fee.map(f -> FeeCalculator.compute(f, posted.max(BigDecimal.ZERO), ccy).amount()).orElse(BigDecimal.ZERO);
        ClosureCalculator.Quote q = ClosureCalculator.quote(posted, decOr0(acc, "accrued_credit_interest"),
                decOr0(acc, "accrued_debit_interest"),
                ProductSnapshot.of(acc).withholdingTaxRate().orElse(props.withholdingTaxRate()), feeAmount,
                fee.map(FeeCalculator.FeeDefinition::taxRate).orElse(BigDecimal.ZERO), ccy);
        ObjectNode out = rows.object();
        out.put("deposit_account_id", accountId.toString());
        out.put("currency", ccy);
        out.put("as_of", accounts.today().toString());
        out.put("ledger_balance", q.ledgerBalance().toPlainString());
        if (balance != null) {
            out.put("available_balance", LedgerPort.available(balance).toPlainString());
        }
        out.put("accrued_credit_interest", q.creditInterest().toPlainString());
        out.put("withholding_tax", q.interestTax().toPlainString());
        out.put("accrued_debit_interest", q.debitInterest().toPlainString());
        out.put("closure_fee", q.fees().toPlainString());
        out.put("closure_fee_tax", q.feeTax().toPlainString());
        out.put("final_payout", q.finalPayout().toPlainString());
        ArrayNode blockers = out.putArray("blockers");
        blockers(acc, balance).forEach(blockers::add);
        out.put("closable", blockers.isEmpty());
        return out;
    }

    private List<String> blockers(JsonNode acc, JsonNode balance) {
        UUID id = uuid(acc, "id");
        List<String> out = new ArrayList<>();
        String status = str(acc, "status");
        if (!CLOSABLE.contains(status) && !"CLOSING".equals(status)) {
            out.add("account status " + status + " does not allow closure");
        }
        long hard = rows.scalar("""
                select count(*) from account_block where deposit_account_id = ? and status = 'ACTIVE'
                  and block_type in ('LEGAL','GARNISHMENT','COURT_ORDER','TAX_LIEN','AML','FRAUD','REGULATORY','LIEN')""",
                Long.class, id).orElse(0L);
        if (hard > 0) {
            out.add(hard + " active legal/regulatory block(s)");
        }
        long pots = rows.scalar("select count(*) from savings_pot where deposit_account_id = ? and status <> 'CLOSED'",
                Long.class, id).orElse(0L);
        if (pots > 0) {
            out.add(pots + " open savings pot(s); close them first");
        }
        long vas = rows.scalar("select count(*) from virtual_account where master_account_id = ? and status <> 'CLOSED'",
                Long.class, id).orElse(0L);
        if (vas > 0) {
            out.add(vas + " open virtual account(s)");
        }
        long tds = rows.scalar("""
                select count(*) from term_deposit where (funding_account_id = ? or payout_account_id = ?)
                  and status in ('PENDING_FUNDING', 'ACTIVE')""", Long.class, id, id).orElse(0L);
        if (tds > 0) {
            out.add(tds + " live term deposit(s) pay out to this account");
        }
        if (balance != null && new BigDecimal(balance.path("pending_debits").asString("0")).signum() != 0) {
            out.add("pending holds on the account");
        }
        return out;
    }

    @Transactional
    public ObjectNode request(Object body) {
        ObjectNode v = rows.columns("account_closure", body);
        UUID accountId = uuid(v, "deposit_account_id");
        accounts.reference(accountId, "deposit_account_id");
        ObjectNode acc = accounts.lock(accountId);
        if (rows.scalar("""
                select count(*) from account_closure where deposit_account_id = ?
                  and status in ('REQUESTED', 'PENDING_APPROVAL', 'SETTLING')""", Long.class, accountId).orElse(0L) > 0) {
            throw ApiException.conflict("closure_in_progress", "A closure request is already open for this account");
        }
        String status = str(acc, "status");
        if (!CLOSABLE.contains(status)) {
            throw ApiException.conflict("invalid_transition", "An account in status " + status + " cannot be closed");
        }
        ObjectNode quote = quoteFor(acc);
        if (!quote.path("blockers").isEmpty()) {
            throw ApiException.unprocessable("closure_blocked", "Account cannot be closed: " + quote.get("blockers"));
        }
        BigDecimal payout = new BigDecimal(quote.get("final_payout").asString());
        UUID target = uuid(v, "transfer_to_account_id");
        if (target != null) {
            if (target.equals(accountId)) {
                throw ApiException.unprocessable("invalid_transfer_account", "The balance cannot be transferred to the account being closed");
            }
            ObjectNode t = accounts.reference(target, "transfer_to_account_id");
            accounts.requireCurrency(t, str(acc, "currency"));
            accounts.requireCreditAllowed(t);
            accounts.requireLedger(t);
        }
        if (payout.signum() > 0 && target == null && !v.hasNonNull("transfer_to_payee_id")) {
            throw ApiException.unprocessable("payout_destination_required",
                    "The account will pay out " + payout.toPlainString() + "; give transfer_to_account_id or transfer_to_payee_id");
        }
        if (payout.signum() < 0) {
            throw ApiException.unprocessable("negative_balance", "The account owes " + payout.negate().toPlainString()
                    + " (overdraft, interest or fees); it must be repaid before closure");
        }
        v.put("status", "PENDING_APPROVAL");
        v.put("final_interest", new BigDecimal(quote.get("accrued_credit_interest").asString())
                .subtract(new BigDecimal(quote.get("withholding_tax").asString()))
                .subtract(new BigDecimal(quote.get("accrued_debit_interest").asString())).toPlainString());
        v.put("final_fees", new BigDecimal(quote.get("closure_fee").asString())
                .add(new BigDecimal(quote.get("closure_fee_tax").asString())).toPlainString());
        v.put("final_balance", payout.toPlainString());
        ObjectNode closure = rows.insert("account_closure", v);
        accounts.transition(acc, "CLOSING", str(v, "reason_code"), "Closure requested: " + str(closure, "id"), null, null);
        return closure;
    }

    @Transactional
    public ObjectNode approve(UUID closureId, String comment) {
        ObjectNode c = rows.lock("account_closure", closureId);
        Lifecycle.ACCOUNT_CLOSURE.require(str(c, "status"), "SETTLING");
        AccountSupport.requireFourEyes(c, "account closure");
        UUID accountId = uuid(c, "deposit_account_id");
        ObjectNode acc = accounts.lock(accountId);
        if (!"CLOSING".equals(str(acc, "status"))) {
            throw ApiException.conflict("invalid_transition", "Account is " + str(acc, "status") + ", expected CLOSING");
        }
        String ccy = str(acc, "currency");
        UUID ledgerAccount = uuid(acc, "ledger_account_id");
        BigDecimal interestNet = BigDecimal.ZERO;
        BigDecimal feeTotal = BigDecimal.ZERO;
        BigDecimal paid = BigDecimal.ZERO;
        if (ledgerAccount != null) {
            InterestService.Capitalised caps = interest.capitalise(accountId, false);
            for (ObjectNode cap : caps.capitalisations()) {
                BigDecimal net = decOr0(cap, "net_amount");
                interestNet = "CREDIT".equals(str(cap, "kind")) ? interestNet.add(net) : interestNet.subtract(net);
            }
            acc = accounts.lock(accountId);
            Optional<FeeCalculator.FeeDefinition> fee = ProductSnapshot.of(acc).fee(CLOSURE_FEE);
            if (fee.isPresent()) {
                BigDecimal bal = LedgerPort.posted(ledger.balance(ledgerAccount));
                FeeCalculator.Charge ch = FeeCalculator.compute(fee.get(), bal.max(BigDecimal.ZERO), ccy);
                if (ch.amount().signum() > 0) {
                    fees.chargeLocked(acc, CLOSURE_FEE, ch.amount(), null, "ACCOUNT_CLOSURE", closureId,
                            "Account closure fee", "closure:" + closureId);
                    feeTotal = ch.total();
                }
            }
            JsonNode balance = ledger.balance(ledgerAccount);
            BigDecimal posted = LedgerPort.posted(balance);
            if (posted.signum() < 0) {
                throw ApiException.unprocessable("negative_balance", "The account owes " + posted.negate().toPlainString()
                        + "; it must be repaid before closure");
            }
            if (new BigDecimal(balance.path("pending_debits").asString("0")).signum() != 0) {
                throw ApiException.unprocessable("holds_outstanding", "Release pending holds before closing the account");
            }
            if (posted.signum() > 0) {
                UUID destination;
                String narrative;
                if (uuid(c, "transfer_to_account_id") != null) {
                    ObjectNode target = accounts.get(uuid(c, "transfer_to_account_id"));
                    accounts.requireCreditAllowed(target);
                    destination = accounts.requireLedger(target);
                    narrative = "Closing balance transfer to " + str(target, "account_number");
                } else if (uuid(c, "transfer_to_payee_id") != null) {
                    destination = props.gl().clearing();
                    narrative = "Closing balance to payee " + str(c, "transfer_to_payee_id");
                } else {
                    throw ApiException.unprocessable("payout_destination_required",
                            "Closing balance " + posted.toPlainString() + " needs a payout destination");
                }
                ledger.post(new LedgerPort.Posting(LedgerPort.key("closure-payout", closureId, null), "TRANSFER",
                        narrative, ccy, "ACCOUNT_CLOSURE", closureId, null, List.of(
                                LedgerPort.Leg.debit(ledgerAccount, posted, narrative),
                                LedgerPort.Leg.credit(destination, posted, narrative))));
                paid = posted;
            }
            BigDecimal after = LedgerPort.posted(ledger.balance(ledgerAccount));
            if (after.signum() != 0) {
                throw ApiException.conflict("balance_not_zero", "Ledger balance is " + after.toPlainString()
                        + " after settlement; the account can only be closed at zero");
            }
            ledger.restrict(ledgerAccount, "ACTIVE", "CLOSED", "Account closure " + closureId,
                    LedgerPort.key("closure-close", closureId, null));
        }
        ObjectNode extra = rows.object();
        extra.put("closed_at", accounts.now().toString());
        ObjectNode closedAccount = accounts.transition(acc, "CLOSED", str(c, "reason_code"),
                comment == null ? "Closed" : comment, extra, "accounts.deposit_account.closed");
        rows.execute("update account_holder set status = 'REMOVED' where deposit_account_id = ? and status <> 'REMOVED'",
                accountId);
        ObjectNode v = rows.object();
        v.put("status", "COMPLETED");
        v.put("final_interest", Currencies.round(interestNet, ccy).toPlainString());
        v.put("final_fees", feeTotal.toPlainString());
        v.put("final_balance", paid.toPlainString());
        v.put("completed_at", accounts.now().toString());
        Lifecycle.ACCOUNT_CLOSURE.require("SETTLING", "COMPLETED"); // settled within this transaction
        return rows.update("account_closure", closureId, v);
    }

    @Transactional
    public ObjectNode reject(UUID closureId, String reason) {
        return end(closureId, "REJECTED", reason);
    }

    @Transactional
    public ObjectNode cancel(UUID closureId, String reason) {
        return end(closureId, "CANCELLED", reason);
    }

    private ObjectNode end(UUID closureId, String status, String reason) {
        ObjectNode c = rows.lock("account_closure", closureId);
        Lifecycle.ACCOUNT_CLOSURE.require(str(c, "status"), status);
        ObjectNode acc = accounts.lock(uuid(c, "deposit_account_id"));
        if ("CLOSING".equals(str(acc, "status"))) {
            String before = rows.scalar("""
                    select from_status from account_status_history where deposit_account_id = ? and to_status = 'CLOSING'
                    order by created_at desc, id desc limit 1""", String.class, uuid(acc, "id")).orElse("ACTIVE");
            accounts.transition(acc, before, "CLOSURE_" + status, reason, null, null);
        }
        ObjectNode v = rows.object();
        v.put("status", status);
        if (reason != null) {
            v.put("reason", reason);
        }
        return rows.update("account_closure", closureId, v);
    }
}
