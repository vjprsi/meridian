package io.meridian.deposit.application;

import static io.meridian.deposit.infrastructure.Rows.date;
import static io.meridian.deposit.infrastructure.Rows.dec;
import static io.meridian.deposit.infrastructure.Rows.str;
import static io.meridian.deposit.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.deposit.config.DepositProperties;
import io.meridian.deposit.domain.Currencies;
import io.meridian.deposit.domain.Lifecycle;
import io.meridian.deposit.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

/**
 * Cheque books and cheques. Issuing a book creates its leaves (ISSUED). Presentment clears the cheque by posting
 * debit drawer / credit clearing; when the ledger refuses (insufficient funds, restricted account) or the cheque
 * is stale, the cheque is returned unpaid. A cleared cheque returned later is reversed in the ledger.
 */
@Service
public class ChequeService {
    private static final Set<String> CHEQUE_ACCOUNT_TYPES = Set.of("CURRENT", "PAYROLL", "ESCROW", "BASIC", "SETTLEMENT");
    private static final int MAX_LEAVES = 200;
    private static final int STALE_MONTHS = 6;

    private final Rows rows;
    private final AccountSupport accounts;
    private final LedgerPort ledger;
    private final Events events;
    private final DepositProperties props;

    public ChequeService(Rows rows, AccountSupport accounts, LedgerPort ledger, Events events, DepositProperties props) {
        this.rows = rows;
        this.accounts = accounts;
        this.ledger = ledger;
        this.events = events;
        this.props = props;
    }

    @Transactional
    public ObjectNode issueBook(Object body) {
        ObjectNode v = rows.columns("cheque_book", body);
        UUID accountId = uuid(v, "deposit_account_id");
        accounts.reference(accountId, "deposit_account_id");
        ObjectNode acc = accounts.lock(accountId);
        if (!"ACTIVE".equals(str(acc, "status"))) {
            throw ApiException.conflict("account_not_active", "Cheque books are issued only on ACTIVE accounts");
        }
        if (!CHEQUE_ACCOUNT_TYPES.contains(str(acc, "account_type"))) {
            throw ApiException.unprocessable("cheques_not_supported", str(acc, "account_type") + " accounts have no cheques");
        }
        String first = str(v, "first_cheque_no");
        String last = str(v, "last_cheque_no");
        if (!first.matches("\\d{1,16}") || !last.matches("\\d{1,16}") || first.length() != last.length()) {
            throw ApiException.unprocessable("invalid_serials", "Cheque serials must be numeric with equal length");
        }
        long from = Long.parseLong(first);
        long to = Long.parseLong(last);
        int leaves = v.path("leaves").asInt();
        if (to < from || to - from + 1 != leaves) {
            throw ApiException.unprocessable("invalid_leaves", "leaves must equal last_cheque_no - first_cheque_no + 1");
        }
        if (leaves < 1 || leaves > MAX_LEAVES) {
            throw ApiException.unprocessable("invalid_leaves", "A cheque book has 1-" + MAX_LEAVES + " leaves");
        }
        long overlap = rows.scalar("""
                select count(*) from cheque where deposit_account_id = ? and length(cheque_no) = ?
                  and cheque_no::numeric between ?::numeric and ?::numeric""", Long.class, accountId, first.length(),
                first, last).orElse(0L);
        if (overlap > 0) {
            throw ApiException.conflict("serials_in_use", "Cheque serials " + first + "-" + last + " overlap an existing book");
        }
        UUID bookId = Ids.newId();
        v.put("id", bookId.toString());
        v.put("status", "REQUESTED");
        ObjectNode book = rows.insert("cheque_book", v);
        for (long n = from; n <= to; n++) {
            ObjectNode c = rows.object();
            c.put("cheque_book_id", bookId.toString());
            c.put("deposit_account_id", accountId.toString());
            c.put("cheque_no", String.format("%0" + first.length() + "d", n));
            c.put("currency", str(acc, "currency"));
            c.put("status", "ISSUED");
            rows.insert("cheque", c);
        }
        return book;
    }

    /** REQUESTED/PRINTED -> DISPATCHED: the book left the bank (courier or collection branch). */
    @Transactional
    public ObjectNode dispatchBook(UUID bookId, String reference, UUID deliveryBranch) {
        ObjectNode book = rows.lock("cheque_book", bookId);
        String status = str(book, "status");
        if (!"REQUESTED".equals(status) && !"PRINTED".equals(status)) {
            throw ApiException.conflict("invalid_transition", "Only a REQUESTED or PRINTED cheque book can be dispatched; "
                    + "this one is " + status);
        }
        ObjectNode v = rows.object();
        v.put("status", "DISPATCHED");
        v.put("dispatched_at", accounts.now().toString());
        if (reference != null) {
            v.put("dispatch_reference", truncate(reference, 64));
        }
        if (deliveryBranch != null) {
            v.put("delivery_branch_id", deliveryBranch.toString());
        }
        return rows.update("cheque_book", bookId, v);
    }

    /** DISPATCHED -> ACTIVE once the customer confirms receipt; only then can its cheques clear. */
    @Transactional
    public ObjectNode activateBook(UUID bookId) {
        ObjectNode book = rows.lock("cheque_book", bookId);
        if (!"DISPATCHED".equals(str(book, "status"))) {
            throw ApiException.conflict("invalid_transition", "Only a DISPATCHED cheque book can be activated; this one is "
                    + str(book, "status"));
        }
        ObjectNode acc = accounts.get(uuid(book, "deposit_account_id"));
        accounts.requireNotClosed(acc);
        ObjectNode v = rows.object();
        v.put("status", "ACTIVE");
        v.put("activated_at", accounts.now().toString());
        return rows.update("cheque_book", bookId, v);
    }

    @Transactional
    public ObjectNode cancelBook(UUID bookId, String reason) {
        ObjectNode book = rows.lock("cheque_book", bookId);
        Lifecycle.CHEQUE_BOOK.require(str(book, "status"), "CANCELLED");
        rows.execute("""
                update cheque set status = 'CANCELLED', stop_reason = ? where cheque_book_id = ? and status = 'ISSUED'""",
                truncate(reason, 255), bookId);
        return rows.update("cheque_book", bookId, rows.object().put("status", "CANCELLED"));
    }

    @Transactional
    public ObjectNode stop(UUID chequeId, String reason) {
        ObjectNode c = rows.lock("cheque", chequeId);
        if ("PRESENTED".equals(str(c, "status")) || "CLEARED".equals(str(c, "status"))) {
            throw ApiException.conflict("cheque_already_presented", "Cheque " + str(c, "cheque_no") + " was already presented");
        }
        Lifecycle.CHEQUE.require(str(c, "status"), "STOPPED");
        ObjectNode v = rows.object();
        v.put("status", "STOPPED");
        v.put("stop_reason", truncate(reason, 255));
        ObjectNode stopped = rows.update("cheque", chequeId, v);
        exhaustIfDone(uuid(c, "cheque_book_id"));
        return stopped;
    }

    @Transactional
    public ObjectNode present(UUID chequeId, BigDecimal amount, String currency, String payeeName, UUID image,
                              LocalDate issueDate, String clientKey) {
        ObjectNode c = rows.lock("cheque", chequeId);
        String status = str(c, "status");
        if ("STOPPED".equals(status)) {
            throw ApiException.conflict("cheque_stopped", "Payment of cheque " + str(c, "cheque_no") + " was stopped");
        }
        Lifecycle.CHEQUE.require(status, "PRESENTED");
        ObjectNode book = rows.lock("cheque_book", uuid(c, "cheque_book_id"));
        if ("CANCELLED".equals(str(book, "status"))) {
            throw ApiException.conflict("cheque_book_cancelled", "The cheque book was cancelled");
        }
        if (!"ACTIVE".equals(str(book, "status"))) {
            throw ApiException.conflict("cheque_book_not_active", "Cheques clear only from an ACTIVE book; this book is "
                    + str(book, "status") + " (dispatch it and activate it on customer receipt)");
        }
        ObjectNode acc = accounts.lock(uuid(c, "deposit_account_id"));
        accounts.requireCurrency(acc, currency);
        if (amount.signum() <= 0 || !Currencies.fitsMinorUnit(amount, currency)) {
            throw ApiException.unprocessable("invalid_amount", "amount must be positive");
        }
        LocalDate today = accounts.today();
        ObjectNode v = rows.object();
        v.put("amount", amount.toPlainString());
        v.put("currency", currency);
        v.put("presented_at", accounts.now().toString());
        if (payeeName != null) {
            v.put("payee_name", payeeName);
        }
        if (image != null) {
            v.put("image_document_id", image.toString());
        }
        if (issueDate != null) {
            v.put("issue_date", issueDate.toString());
        }
        LocalDate written = issueDate != null ? issueDate : date(c, "issue_date");
        String returnReason = null;
        if (written != null && written.plusMonths(STALE_MONTHS).isBefore(today)) {
            returnReason = "STALE_CHEQUE";
        } else if (written != null && written.isAfter(today)) {
            returnReason = "POST_DATED";
        } else if (!io.meridian.deposit.domain.AccountRestrictions.allowsDebit(str(acc, "status"))) {
            returnReason = "ACCOUNT_RESTRICTED";
        } else {
            try {
                java.util.Map<String, String> chequeRefs = new java.util.HashMap<>();
                chequeRefs.put("scheme_reference", str(c, "cheque_no"));
                chequeRefs.put("counterparty_name", payeeName == null || payeeName.length() <= 140 ? payeeName
                        : payeeName.substring(0, 140));
                UUID je = ledger.post(new LedgerPort.Posting(LedgerPort.key("cheque-clear", chequeId, clientKey), "PAYMENT",
                        "Cheque " + str(c, "cheque_no") + (payeeName == null ? "" : " to " + payeeName), currency, "CHEQUE",
                        chequeId, null, List.of(
                                LedgerPort.Leg.debit(accounts.requireLedger(acc), amount, "Cheque " + str(c, "cheque_no")),
                                LedgerPort.Leg.credit(props.gl().clearing(), amount, "Cheque clearing")))
                        .withRefs(str(c, "cheque_no"), chequeRefs));
                v.put("status", "CLEARED");
                v.put("journal_entry_id", je.toString());
            } catch (ApiException e) {
                if (e.status() >= 500) {
                    throw e;
                }
                returnReason = "insufficient_funds".equals(e.code()) ? "INSUFFICIENT_FUNDS" : "REFER_TO_DRAWER";
            }
        }
        ObjectNode result;
        if (returnReason != null) {
            v.put("status", "RETURNED");
            v.put("return_reason", returnReason);
            result = rows.update("cheque", chequeId, v);
            events.publish("accounts.cheque.returned", "cheque", result);
        } else {
            result = rows.update("cheque", chequeId, v);
            rows.execute("update deposit_account set last_customer_activity_at = now() where id = ?", uuid(acc, "id"));
        }
        exhaustIfDone(uuid(c, "cheque_book_id"));
        return result;
    }

    @Transactional
    public ObjectNode returnCheque(UUID chequeId, String returnReason, String clientKey) {
        ObjectNode c = rows.lock("cheque", chequeId);
        Lifecycle.CHEQUE.require(str(c, "status"), "RETURNED");
        if ("CLEARED".equals(str(c, "status")) && uuid(c, "journal_entry_id") != null) {
            ledger.reverse(uuid(c, "journal_entry_id"), "Cheque returned: " + returnReason,
                    LedgerPort.key("cheque-return", chequeId, clientKey));
        }
        ObjectNode v = rows.object();
        v.put("status", "RETURNED");
        v.put("return_reason", truncate(returnReason, 64));
        ObjectNode returned = rows.update("cheque", chequeId, v);
        events.publish("accounts.cheque.returned", "cheque", returned);
        return returned;
    }

    private void exhaustIfDone(UUID bookId) {
        long open = rows.scalar("select count(*) from cheque where cheque_book_id = ? and status = 'ISSUED'", Long.class,
                bookId).orElse(0L);
        if (open == 0) {
            rows.execute("update cheque_book set status = 'EXHAUSTED' where id = ? and status = 'ACTIVE'", bookId);
        }
    }

    private static String truncate(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n);
    }

    static BigDecimal amountOf(ObjectNode c) {
        return dec(c, "amount");
    }
}
