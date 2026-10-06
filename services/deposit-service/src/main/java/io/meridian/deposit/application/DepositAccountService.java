package io.meridian.deposit.application;

import static io.meridian.deposit.infrastructure.Rows.date;
import static io.meridian.deposit.infrastructure.Rows.dec;
import static io.meridian.deposit.infrastructure.Rows.decOr0;
import static io.meridian.deposit.infrastructure.Rows.str;
import static io.meridian.deposit.infrastructure.Rows.ts;
import static io.meridian.deposit.infrastructure.Rows.uuid;

import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.deposit.config.DepositProperties;
import io.meridian.deposit.domain.AccountNumbers;
import io.meridian.deposit.domain.AccountRestrictions;
import io.meridian.deposit.domain.Currencies;
import io.meridian.deposit.domain.Schedule;
import io.meridian.deposit.infrastructure.ProductCatalogClient;
import io.meridian.deposit.infrastructure.Rows;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Deposit account lifecycle: open (PENDING_APPROVAL, generated account number and IBAN), approve (four-eyes,
 * opens the ledger account), activate, freeze/unfreeze, dormancy and reactivation, product change, ledger views.
 */
@Service
public class DepositAccountService {
    private static final String TABLE = AccountSupport.TABLE;
    private static final Set<String> AUTO = Set.of("", "AUTO", "GENERATE");
    private static final Set<String> FREEZABLE = Set.of("ACTIVE", "BLOCKED_DEBIT", "BLOCKED_CREDIT", "BLOCKED",
            "INACTIVE", "DORMANT");

    private final Rows rows;
    private final AccountSupport accounts;
    private final LedgerPort ledger;
    private final Events events;
    private final DepositProperties props;
    private final ProductCatalogClient catalog;
    private final SecureRandom random = new SecureRandom();
    private final Approvals approvals;

    /** Approval resource type for account openings in workflow-service (G11). */
    public static final String APPROVAL_RESOURCE = "deposit_account";

    public DepositAccountService(Rows rows, AccountSupport accounts, LedgerPort ledger, Events events,
                                 DepositProperties props, ProductCatalogClient catalog, Approvals approvals) {
        this.approvals = approvals;
        this.rows = rows;
        this.accounts = accounts;
        this.ledger = ledger;
        this.events = events;
        this.props = props;
        this.catalog = catalog;
    }

    // ------------------------------------------------------------------ open

    @Transactional
    public ObjectNode open(Object body) {
        ObjectNode v = rows.columns(TABLE, body);
        String currency = str(v, "currency");
        if (currency == null || !currency.matches("[A-Z]{3}") || !validCurrency(currency)) {
            throw ApiException.unprocessable("invalid_currency", "currency must be an ISO 4217 code");
        }
        String type = str(v, "account_type");
        if ("NOTICE".equals(type) && (v.path("notice_period_days").asInt(0) <= 0)) {
            throw ApiException.unprocessable("notice_period_required", "NOTICE accounts need notice_period_days > 0");
        }
        int cycleDay = v.path("statement_cycle_day").asInt(1);
        if (cycleDay < 1 || cycleDay > 31) {
            throw ApiException.unprocessable("invalid_statement_cycle_day", "statement_cycle_day must be 1-31");
        }
        BigDecimal minBalance = dec(v, "minimum_balance");
        if (minBalance != null && minBalance.signum() < 0) {
            throw ApiException.unprocessable("invalid_minimum_balance", "minimum_balance must not be negative");
        }
        ObjectNode terms = catalog.snapshot(uuid(v, "product_id"), uuid(v, "product_version_id"), currency);
        ProductSnapshot snapshot = ProductSnapshot.terms(terms);
        snapshot.rate("CREDIT_INTEREST"); // validates the pinned rate configuration early
        snapshot.rate("OVERDRAFT_INTEREST");
        v.set("product_terms", terms);

        String number = uniqueAccountNumber();
        v.put("account_number", number);
        String iban = str(v, "iban");
        if (iban == null || AUTO.contains(iban.trim().toUpperCase(Locale.ROOT))) {
            Optional<String> built = safeIban(number);
            if (built.isPresent()) {
                v.put("iban", built.get());
                v.put("bban", built.get().substring(4));
            } else {
                v.remove("iban");
            }
        } else {
            String normalised = iban.replace(" ", "").toUpperCase(Locale.ROOT);
            if (!AccountNumbers.isValidIban(normalised)) {
                throw ApiException.unprocessable("invalid_iban", "iban " + iban + " fails the ISO 13616 mod-97 check");
            }
            v.put("iban", normalised);
        }
        if (!v.has("routing_code") && props.branchCode() != null) {
            v.put("routing_code", props.branchCode());
        }
        v.put("status", "PENDING_APPROVAL");
        ObjectNode row = rows.insert(TABLE, v);
        UUID id = uuid(row, "id");
        accounts.history(id, null, "PENDING_APPROVAL", "OPENED", "Account opened");
        ObjectNode holder = rows.object();
        holder.put("deposit_account_id", id.toString());
        holder.put("customer_id", str(row, "customer_id"));
        holder.put("role", "MINOR".equals(type) ? "MINOR" : "PRIMARY");
        ObjectNode primary = rows.insert("account_holder", holder);
        events.publish("accounts.deposit_account.opened", TABLE, row);
        events.publish("accounts.account_holder.added", "account_holder", primary);
        // Central maker-checker: opening is the maker's submission; the checker decides in the workflow inbox.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("account_number", str(row, "account_number"));
        payload.put("account_type", type);
        payload.put("customer_id", str(row, "customer_id"));
        payload.put("product_id", str(row, "product_id"));
        payload.put("product_version_id", str(row, "product_version_id"));
        payload.put("currency", currency);
        payload.put("iban", str(row, "iban"));
        approvals.request(ApprovalSpec.of(APPROVAL_RESOURCE, id, "approve",
                "Open " + type + " account " + str(row, "account_number") + " (" + currency + ")", payload), "open:" + id);
        return row;
    }

    private String uniqueAccountNumber() {
        int length = AccountNumbers.accountLength(props.country(), props.accountNumberLength());
        for (int attempt = 0; attempt < 20; attempt++) {
            String candidate = AccountNumbers.generate(length, random);
            if (rows.scalar("select 1 from deposit_account where account_number = ?", Integer.class, candidate).isEmpty()) {
                return candidate;
            }
        }
        throw ApiException.conflict("account_number_exhausted", "Could not allocate a unique account number");
    }

    private Optional<String> safeIban(String accountNumber) {
        try {
            return AccountNumbers.iban(props.country(), props.bankCode(), props.branchCode(), accountNumber);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static boolean validCurrency(String c) {
        try {
            java.util.Currency.getInstance(c);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ approve / activate

    @Transactional
    public ObjectNode approve(UUID id, String comment) {
        ObjectNode acc = accounts.lock(id);
        io.meridian.deposit.domain.Lifecycle.DEPOSIT_ACCOUNT.require(str(acc, "status"), "PENDING_FUNDING");
        AccountSupport.requireFourEyes(acc, "deposit account");
        if ("MINOR".equals(str(acc, "account_type")) && rows.scalar("""
                select count(*) from account_holder where deposit_account_id = ? and role = 'GUARDIAN' and status = 'ACTIVE'""",
                Long.class, id).orElse(0L) == 0) {
            throw ApiException.unprocessable("guardian_required", "A MINOR account needs an active GUARDIAN holder");
        }
        ObjectNode extra = rows.object();
        extra.put("ledger_account_id", ensureLedgerAccount(acc).toString());
        ObjectNode approved = accounts.transition(acc, "PENDING_FUNDING", "APPROVED", comment == null ? "Approved" : comment,
                extra, null);
        approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "approved directly");
        return approved;
    }

    /**
     * Declines an account opening (PENDING_APPROVAL -> REJECTED). The API has no reject endpoint for accounts; this
     * applies a rejection decided in workflow-service (G11).
     */
    @Transactional
    public ObjectNode reject(UUID id, String reason) {
        ObjectNode acc = accounts.lock(id);
        io.meridian.deposit.domain.Lifecycle.DEPOSIT_ACCOUNT.require(str(acc, "status"), "REJECTED");
        return accounts.transition(acc, "REJECTED", "REJECTED", reason, null, null);
    }

    /** True while the account still awaits opening approval for the same account number. */
    @Transactional(readOnly = true)
    public boolean awaitsApproval(UUID id, String accountNumber) {
        return rows.find(TABLE, id)
                .filter(a -> "PENDING_APPROVAL".equals(str(a, "status")))
                .filter(a -> accountNumber == null || accountNumber.equals(str(a, "account_number")))
                .isPresent();
    }

    @Transactional
    public ObjectNode activate(UUID id) {
        ObjectNode acc = accounts.lock(id);
        String status = str(acc, "status");
        if (!"PENDING_FUNDING".equals(status)) {
            throw ApiException.conflict("invalid_transition", "Only an approved account (PENDING_FUNDING) can be activated; "
                    + "this one is " + status);
        }
        ObjectNode extra = rows.object();
        extra.put("ledger_account_id", ensureLedgerAccount(acc).toString());
        OffsetDateTime now = accounts.now();
        extra.put("opened_at", now.toString());
        extra.put("last_customer_activity_at", now.toString());
        LocalDate today = accounts.today();
        extra.put("next_capitalisation_date",
                Schedule.advance(today, ProductSnapshot.of(acc).capitalisationFrequency()).toString());
        return accounts.transition(acc, "ACTIVE", "ACTIVATED", "Account activated", extra,
                "accounts.deposit_account.activated");
    }

    private UUID ensureLedgerAccount(JsonNode acc) {
        UUID existing = uuid(acc, "ledger_account_id");
        if (existing != null) {
            return existing;
        }
        UUID id = uuid(acc, "id");
        String name = str(acc, "nickname") != null ? str(acc, "nickname") : str(acc, "account_type") + " " + str(acc, "account_number");
        return ledger.openAccount(LedgerPort.key("open-ledger-account", id, null), str(acc, "account_number"), name,
                str(acc, "currency"), decOr0(acc, "overdraft_limit"), null, "DEPOSIT_ACCOUNT", id);
    }

    // ------------------------------------------------------------------ freeze / unfreeze / reactivate

    @Transactional
    public ObjectNode freeze(UUID id, String reason) {
        ObjectNode acc = accounts.lock(id);
        String status = str(acc, "status");
        if ("FROZEN".equals(status)) {
            throw ApiException.conflict("already_frozen", "Deposit account " + id + " is already frozen");
        }
        if (!FREEZABLE.contains(status)) {
            throw ApiException.conflict("invalid_transition", "Cannot freeze an account in status " + status);
        }
        return accounts.transition(acc, "FROZEN", "FROZEN", reason, null, null);
    }

    @Transactional
    public ObjectNode unfreeze(UUID id, String reason) {
        ObjectNode acc = accounts.lock(id);
        if (!"FROZEN".equals(str(acc, "status"))) {
            throw ApiException.conflict("not_frozen", "Deposit account " + id + " is not frozen");
        }
        String before = rows.scalar("""
                select from_status from account_status_history where deposit_account_id = ? and to_status = 'FROZEN'
                order by created_at desc, id desc limit 1""", String.class, id).orElse("ACTIVE");
        String target = Set.of("INACTIVE", "DORMANT").contains(before) ? before
                : AccountRestrictions.blockedStatus(accounts.activeBlockScopes(id));
        return accounts.transition(acc, target, "UNFROZEN", reason, null, null);
    }

    @Transactional
    public ObjectNode reactivate(UUID id, String reason) {
        ObjectNode acc = accounts.lock(id);
        String status = str(acc, "status");
        if (!"DORMANT".equals(status) && !"INACTIVE".equals(status)) {
            throw ApiException.conflict("invalid_transition", "Only DORMANT or INACTIVE accounts can be reactivated; "
                    + "this one is " + status);
        }
        OffsetDateTime now = accounts.now();
        ObjectNode dormancy = rows.object();
        dormancy.put("deposit_account_id", id.toString());
        dormancy.put("event_type", "REACTIVATED");
        dormancy.put("inactive_days", inactiveDays(acc, now));
        dormancy.put("note", reason);
        rows.insert("account_dormancy_event", dormancy);
        ObjectNode extra = rows.object();
        extra.put("last_customer_activity_at", now.toString());
        extra.putNull("dormant_since");
        String target = AccountRestrictions.blockedStatus(accounts.activeBlockScopes(id));
        return accounts.transition(acc, target, "REACTIVATED", reason, extra, null);
    }

    private static long inactiveDays(JsonNode acc, OffsetDateTime now) {
        OffsetDateTime last = ts(acc, "last_customer_activity_at");
        if (last == null) {
            last = ts(acc, "opened_at");
        }
        if (last == null) {
            last = ts(acc, "created_at");
        }
        return Math.max(0, ChronoUnit.DAYS.between(last.toLocalDate(), now.toLocalDate()));
    }

    /**
     * Dormancy run for one business date: warns, marks INACTIVE and then DORMANT accounts without customer activity
     * for the configured number of days. Returns the number of accounts changed.
     */
    @Transactional
    public int runDormancy(LocalDate asOf) {
        DepositProperties.Dormancy d = props.dormancy();
        OffsetDateTime now = asOf.atStartOfDay().atOffset(java.time.ZoneOffset.UTC);
        int changed = 0;
        for (ObjectNode acc : rows.query("""
                select row_to_json(a)::text from deposit_account a where status in ('ACTIVE', 'INACTIVE')
                order by id for update""")) {
            long days = inactiveDays(acc, now);
            UUID id = uuid(acc, "id");
            String status = str(acc, "status");
            if (days >= d.dormantDays()) {
                ObjectNode ev = dormancyEvent(id, "MARKED_DORMANT", days, "No customer activity for " + days + " days");
                ObjectNode extra = rows.object();
                extra.put("dormant_since", asOf.toString());
                ObjectNode moved = "ACTIVE".equals(status)
                        ? accounts.transition(acc, "INACTIVE", "INACTIVITY", "Inactive " + days + " days", null, null) : acc;
                accounts.transition(moved, "DORMANT", "DORMANCY", "Dormant after " + days + " days", extra, null);
                events.publish("accounts.dormancy.marked_dormant", "account_dormancy_event", ev);
                changed++;
            } else if (days >= d.inactiveDays() && "ACTIVE".equals(status)) {
                dormancyEvent(id, "MARKED_INACTIVE", days, null);
                accounts.transition(acc, "INACTIVE", "INACTIVITY", "Inactive " + days + " days", null, null);
                changed++;
            } else if (days == d.warningDays() && "ACTIVE".equals(status)) {
                dormancyEvent(id, "WARNING_SENT", days, "Dormancy warning");
            }
        }
        return changed;
    }

    private ObjectNode dormancyEvent(UUID accountId, String type, long days, String note) {
        ObjectNode ev = rows.object();
        ev.put("deposit_account_id", accountId.toString());
        ev.put("event_type", type);
        ev.put("inactive_days", days);
        if (note != null) {
            ev.put("note", note);
        }
        return rows.insert("account_dormancy_event", ev);
    }

    // ------------------------------------------------------------------ product change

    @Transactional
    public ObjectNode changeProduct(UUID id, UUID productId, UUID versionId, LocalDate effectiveDate) {
        ObjectNode acc = accounts.lock(id);
        accounts.requireNotClosed(acc);
        if ("CLOSING".equals(str(acc, "status")) || "PENDING_APPROVAL".equals(str(acc, "status"))) {
            throw ApiException.conflict("invalid_transition", "Cannot change the product of an account in status "
                    + str(acc, "status"));
        }
        if (versionId.toString().equals(str(acc, "product_version_id"))) {
            throw ApiException.conflict("same_product_version", "The account is already on product version " + versionId);
        }
        LocalDate today = accounts.today();
        if (effectiveDate.isBefore(today)) {
            throw ApiException.unprocessable("effective_date_in_past",
                    "effective_date must be today or later; use recalculate-interest for back-valued corrections");
        }
        ObjectNode terms = catalog.snapshot(productId, versionId, str(acc, "currency"));
        ProductSnapshot.terms(terms).rate("CREDIT_INTEREST"); // reject an invalid rate configuration up front
        if (effectiveDate.isAfter(today)) {
            ObjectNode scheduled = rows.object();
            scheduled.put("product_id", productId.toString());
            scheduled.put("product_version_id", versionId.toString());
            scheduled.put("effective_date", effectiveDate.toString());
            scheduled.set("product_terms", terms);
            ObjectNode values = rows.object();
            values.set("pending_product_change", scheduled);
            return rows.update(TABLE, id, values);
        }
        return applyProduct(acc, productId, versionId, terms);
    }

    private ObjectNode applyProduct(ObjectNode acc, UUID productId, UUID versionId, JsonNode terms) {
        ObjectNode values = rows.object();
        values.put("product_id", productId.toString());
        values.put("product_version_id", versionId.toString());
        values.set("product_terms", terms);
        values.putNull("pending_product_change");
        ObjectNode updated = rows.update(TABLE, uuid(acc, "id"), values);
        events.publish("accounts.deposit_account.product_changed", TABLE, updated);
        return updated;
    }

    /** Applies product changes scheduled on or before {@code asOf} (end-of-day job). */
    @Transactional
    public int applyScheduledProductChanges(LocalDate asOf) {
        int n = 0;
        for (ObjectNode acc : rows.query("""
                select row_to_json(a)::text from deposit_account a
                where pending_product_change is not null
                  and (pending_product_change->>'effective_date')::date <= ? for update""", asOf)) {
            JsonNode s = acc.get("pending_product_change");
            applyProduct(acc, UUID.fromString(s.get("product_id").asString()),
                    UUID.fromString(s.get("product_version_id").asString()), s.get("product_terms"));
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ ledger views

    public JsonNode balance(UUID id) {
        return ledger.balance(accounts.requireLedger(accounts.get(id)));
    }

    public JsonNode transactions(UUID id, OffsetDateTime from, OffsetDateTime to, Integer limit, String cursor) {
        if (from != null && to != null && to.isBefore(from)) {
            throw ApiException.badRequest("invalid_range", "to must not be before from");
        }
        return ledger.entries(accounts.requireLedger(accounts.get(id)), from, to, limit, cursor);
    }

    /** Plain-text account confirmation letter (rendered for print / PDF conversion by document-service). */
    public String confirmationLetter(UUID id) {
        ObjectNode acc = accounts.get(id);
        String status = str(acc, "status");
        if (!List.of("ACTIVE", "BLOCKED_DEBIT", "BLOCKED_CREDIT", "BLOCKED", "FROZEN", "INACTIVE", "DORMANT")
                .contains(status)) {
            throw ApiException.conflict("account_not_open", "A confirmation letter needs an open account; this one is "
                    + status);
        }
        JsonNode bal = ledger.balance(accounts.requireLedger(acc));
        LocalDate today = accounts.today();
        String ccy = str(acc, "currency");
        StringBuilder sb = new StringBuilder();
        sb.append("ACCOUNT CONFIRMATION LETTER\n");
        sb.append("Reference: ACL-").append(id).append('\n');
        sb.append("Date: ").append(today).append("\n\n");
        sb.append("We confirm that the following account is held with us:\n\n");
        sb.append("Account number : ").append(str(acc, "account_number")).append('\n');
        if (str(acc, "iban") != null) {
            sb.append("IBAN           : ").append(str(acc, "iban")).append('\n');
        }
        if (str(acc, "routing_code") != null) {
            sb.append("Routing code   : ").append(str(acc, "routing_code")).append('\n');
        }
        sb.append("Account type   : ").append(str(acc, "account_type")).append('\n');
        sb.append("Currency       : ").append(ccy).append('\n');
        sb.append("Customer id    : ").append(str(acc, "customer_id")).append('\n');
        sb.append("Opened on      : ").append(date(acc, "opened_at") == null ? "-" : date(acc, "opened_at")).append('\n');
        sb.append("Status         : ").append(status).append('\n');
        sb.append("Balance as of ").append(today).append(": ")
                .append(Currencies.round(LedgerPort.posted(bal), ccy).toPlainString()).append(' ').append(ccy).append("\n\n");
        sb.append("This letter is issued at the request of the account holder and without liability.\n");
        return sb.toString();
    }

    static UUID newId() {
        return Ids.newId();
    }
}
