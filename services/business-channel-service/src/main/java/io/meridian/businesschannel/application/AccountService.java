package io.meridian.businesschannel.application;

import static io.meridian.businesschannel.application.CurrentUser.business;

import io.meridian.businesschannel.application.Access.Need;
import io.meridian.businesschannel.application.Downstream.Query;
import io.meridian.businesschannel.domain.AccountingExport;
import io.meridian.common.error.ApiException;
import io.meridian.common.time.Clocks;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Accounts of the business: list, balance, entries, statements, accounting exports and the cash position. */
@Service
public class AccountService {
    static final String DEPOSITS = "deposit-service";
    static final String STATEMENTS = "statement-service";
    static final int MAX_EXPORT_ENTRIES = 20_000;

    private final Downstream down;
    private final Access access;
    private final ActionAudit audit;
    private final Rows rows;

    public AccountService(Downstream down, Access access, ActionAudit audit, Rows rows) {
        this.down = down;
        this.access = access;
        this.audit = audit;
        this.rows = rows;
    }

    public JsonNode accounts() {
        return down.page(access.viewableAccounts());
    }

    public JsonNode account(UUID id) {
        return access.account(id, Need.VIEW, "businessAccountsGet");
    }

    public JsonNode balance(UUID id) {
        access.account(id, Need.VIEW, "businessAccountsBalance");
        return down.get(DEPOSITS, "/v1/deposit-accounts/" + id + "/balance");
    }

    public JsonNode transactions(UUID id, OffsetDateTime from, OffsetDateTime to, Integer limit, String cursor) {
        access.account(id, Need.VIEW, "businessAccountsTransactions");
        return down.get(DEPOSITS, "/v1/deposit-accounts/" + id + "/transactions",
                Query.of("from", from).with("to", to).paging(limit, cursor));
    }

    // ------------------------------------------------------------------ statements

    public JsonNode statements(UUID accountId, Integer limit, String cursor) {
        access.account(accountId, Need.VIEW, "businessAccountsStatements");
        return down.page(down.get(STATEMENTS, "/v1/statements",
                        Query.of("deposit_account_id", accountId).paging(limit, cursor)),
                r -> accountId.toString().equals(r.path("deposit_account_id").asString(null)));
    }

    public JsonNode requestStatement(UUID accountId, JsonNode req, String key) {
        String action = "businessAccountsAddStatements";
        JsonNode account = access.account(accountId, Need.VIEW, action);
        LocalDate start = Req.date(req, "period_start");
        LocalDate end = Req.date(req, "period_end");
        if (start == null || end == null) {
            throw ApiException.unprocessable("missing_field", "period_start and period_end are required");
        }
        if (end.isBefore(start)) {
            throw ApiException.unprocessable("invalid_period", "period_end must not be before period_start");
        }
        String format = Req.text(req, "format");
        ObjectNode body = down.json().createObjectNode()
                .put("deposit_account_id", accountId.toString())
                .put("period_start", start.toString()).put("period_end", end.toString())
                .put("format", format == null ? "PDF" : format)
                .put("currency", account.path("currency").asString())
                .put("is_on_demand", true);
        JsonNode created = down.post(STATEMENTS, "/v1/statements", body, key, "create");
        audit.succeeded(action, "ACCOUNT", accountId, created.path("id").asString(null));
        return created;
    }

    public ServiceClient.Download statementFile(UUID accountId, UUID statementId) {
        access.account(accountId, Need.VIEW, "businessAccountsStatementsDownload");
        access.statement(accountId, statementId, "businessAccountsStatementsDownload");
        return down.bytes(STATEMENTS, "/v1/statements/" + statementId + "/download");
    }

    // ------------------------------------------------------------------ accounting export

    /** File name, content type and content of an accounting export (CSV or camt.053). */
    public record Export(String fileName, String contentType, String content) {}

    public Export export(UUID accountId, LocalDate from, LocalDate to, String format) {
        String action = "businessAccountsExport";
        JsonNode account = access.account(accountId, Need.VIEW, action);
        if (from == null || to == null || to.isBefore(from)) {
            throw ApiException.unprocessable("invalid_period", "from and to are required and to must not be before from");
        }
        if (from.plusDays(400).isBefore(to)) {
            throw ApiException.unprocessable("period_too_long", "An export covers at most 400 days");
        }
        String fmt = format == null ? "CSV" : format;
        if (!fmt.equals("CSV") && !fmt.equals("CAMT_053")) {
            throw ApiException.unprocessable("invalid_format", "format must be CSV or CAMT_053");
        }
        List<JsonNode> raw = down.all(DEPOSITS, "/v1/deposit-accounts/" + accountId + "/transactions",
                Query.of("from", from.atStartOfDay().atOffset(ZoneOffset.UTC))
                        .with("to", to.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC).minusNanos(1000)),
                MAX_EXPORT_ENTRIES);
        List<AccountingExport.Entry> entries = new ArrayList<>();
        for (JsonNode e : raw) {
            entries.add(new AccountingExport.Entry(e.path("id").asString(null), e.path("journal_entry_id").asString(null),
                    e.path("account_version").asLong(0), e.path("direction").asString("DEBIT"),
                    Req.rowDecimal(e, "amount") == null ? BigDecimal.ZERO : Req.rowDecimal(e, "amount"),
                    e.path("currency").asString(account.path("currency").asString()),
                    date(e.path("effective_at").asString(null)), date(e.path("value_date").asString(null)),
                    Req.rowDecimal(e, "balance_after"), e.path("reference").asString(null),
                    e.path("description").asString(null)));
        }
        String number = account.path("account_number").asString(accountId.toString());
        audit.succeeded(action, "ACCOUNT", accountId, null);
        if (fmt.equals("CSV")) {
            return new Export("transactions-" + number + "-" + from + "-" + to + ".csv", "text/csv; charset=UTF-8",
                    AccountingExport.csv(entries));
        }
        BigDecimal current = null;
        if (entries.isEmpty()) {
            JsonNode bal = down.get(DEPOSITS, "/v1/deposit-accounts/" + accountId + "/balance");
            current = Req.rowDecimal(bal, "posted_balance");
        }
        JsonNode customer = down.get("customer-service", "/v1/customers/" + business());
        String owner = customer.path("legal_name").asString(customer.path("display_name").asString(null));
        AccountingExport.Account acct = new AccountingExport.Account(accountId.toString(), number,
                account.path("iban").asString(null), account.path("currency").asString(), account.path("bic").asString(null),
                owner);
        String id = "STMT-" + number + "-" + from.toString().replace("-", "") + "-" + to.toString().replace("-", "");
        return new Export("camt053-" + number + "-" + from + "-" + to + ".xml", "application/xml; charset=UTF-8",
                AccountingExport.camt053(id.length() > 35 ? id.substring(0, 35) : id, acct, from, to, entries, current,
                        Clocks.now()));
    }

    private static LocalDate date(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return v.length() > 10 ? OffsetDateTime.parse(v).withOffsetSameInstant(ZoneOffset.UTC).toLocalDate()
                    : LocalDate.parse(v);
        } catch (RuntimeException e) {
            return LocalDate.parse(v.substring(0, 10));
        }
    }

    // ------------------------------------------------------------------ cash position

    /** Balances of every account the user sees, totals per currency, and what is waiting for approval. */
    public JsonNode cashPosition() {
        ObjectNode out = rows.object();
        ArrayNode accounts = out.putArray("accounts");
        Map<String, BigDecimal[]> totals = new TreeMap<>();
        Map<String, Integer> counts = new TreeMap<>();
        List<UUID> ids = new ArrayList<>();
        for (JsonNode a : access.viewableAccounts()) {
            UUID id = UUID.fromString(a.path("id").asString());
            ids.add(id);
            JsonNode bal = down.get(DEPOSITS, "/v1/deposit-accounts/" + id + "/balance");
            String ccy = a.path("currency").asString();
            BigDecimal available = nz(Req.rowDecimal(bal, "available_balance"));
            BigDecimal posted = nz(Req.rowDecimal(bal, "posted_balance"));
            accounts.addObject().put("account_id", id.toString())
                    .put("account_number", a.path("account_number").asString(null))
                    .put("nickname", a.path("nickname").asString(null))
                    .put("account_type", a.path("account_type").asString(null))
                    .put("currency", ccy)
                    .put("available_balance", available.toPlainString())
                    .put("posted_balance", posted.toPlainString());
            BigDecimal[] t = totals.computeIfAbsent(ccy, k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            t[0] = t[0].add(available);
            t[1] = t[1].add(posted);
            counts.merge(ccy, 1, Integer::sum);
        }
        ArrayNode tot = out.putArray("totals");
        totals.forEach((ccy, t) -> tot.addObject().put("currency", ccy).put("available_balance", t[0].toPlainString())
                .put("posted_balance", t[1].toPlainString()).put("accounts", counts.get(ccy)));
        ArrayNode pending = out.putArray("pending_approval");
        if (!ids.isEmpty()) {
            for (ObjectNode p : rows.list("select json_build_object('currency', currency, 'amount', sum(amount)::text, "
                    + "'count', count(*))::text from business_payment where business_customer_id = ? "
                    + "and status in ('PENDING_APPROVAL', 'APPROVED', 'RELEASE_FAILED') "
                    + "and debtor_account_id = any(?::uuid[]) group by currency order by currency",
                    business(), Rows.uuidArray(ids))) {
                pending.add(p);
            }
        }
        return out;
    }

    private static BigDecimal nz(BigDecimal d) {
        return d == null ? BigDecimal.ZERO : d;
    }
}
