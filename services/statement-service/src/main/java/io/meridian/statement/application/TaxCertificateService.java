package io.meridian.statement.application;

import static io.meridian.statement.infrastructure.Rows.date;
import static io.meridian.statement.infrastructure.Rows.dec;
import static io.meridian.statement.infrastructure.Rows.str;
import static io.meridian.statement.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.events.OutboxWriter;
import io.meridian.statement.domain.TaxCertificates;
import io.meridian.statement.infrastructure.BankData;
import io.meridian.statement.infrastructure.Rows;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Interest and withholding-tax certificates: sums the credit-interest capitalisations deposit-service booked in the
 * period (one account, or every account of the customer in the certificate currency).
 */
@Service
public class TaxCertificateService {
    static final String TABLE = "tax_certificate";

    private final Rows rows;
    private final BankData bank;
    private final OutboxWriter outbox;
    private final Clock clock;

    public TaxCertificateService(Rows rows, BankData bank, OutboxWriter outbox, Clock clock) {
        this.rows = rows;
        this.bank = bank;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional
    public ObjectNode create(Object body) {
        ObjectNode v = rows.columns(TABLE, body);
        int year = v.get("tax_year").asInt();
        LocalDate start = date(v, "period_start");
        LocalDate end = date(v, "period_end");
        TaxCertificates.validate(year, start, end, LocalDate.now(clock));
        UUID customer = uuid(v, "customer_id");
        UUID accountId = uuid(v, "deposit_account_id");
        String type = str(v, "certificate_type");
        String currency = str(v, "currency");
        if (TaxCertificates.NEEDS_ACCOUNT.contains(type) && accountId == null) {
            throw ApiException.unprocessable("deposit_account_required", type + " certificates are issued per account");
        }
        if (rows.scalar("""
                select 1 from tax_certificate where customer_id = ? and certificate_type = ? and tax_year = ?
                  and currency = ? and coalesce(deposit_account_id::text, '') = coalesce(?::text, '')
                  and status in ('READY', 'DELIVERED')""", Integer.class, customer, type, year, currency,
                accountId == null ? null : accountId.toString()).isPresent()) {
            throw ApiException.conflict("certificate_exists", "A " + type + " for " + year + " was already issued");
        }
        List<JsonNode> accounts = new ArrayList<>();
        if (accountId != null) {
            JsonNode acc = bank.account(accountId);
            if (!customer.toString().equals(acc.path("customer_id").asString())) {
                throw ApiException.unprocessable("account_not_owned", "The account does not belong to customer " + customer);
            }
            if (!currency.equals(acc.path("currency").asString())) {
                throw ApiException.unprocessable("currency_mismatch", "currency must be the account currency");
            }
            accounts.add(acc);
        } else {
            accounts.addAll(bank.accountsOfCustomer(customer, currency));
        }
        List<TaxCertificates.Posting> postings = new ArrayList<>();
        for (JsonNode acc : accounts) {
            for (JsonNode cap : bank.capitalisations(UUID.fromString(acc.get("id").asString()))) {
                postings.add(new TaxCertificates.Posting(
                        OffsetDateTime.parse(cap.get("created_at").asString()).toLocalDate(), cap.path("kind").asString(),
                        cap.path("currency").asString(), new BigDecimal(cap.path("gross_amount").asString("0")),
                        new BigDecimal(cap.path("tax_amount").asString("0"))));
            }
        }
        TaxCertificates.Totals totals = TaxCertificates.sum(postings, start, end, currency);
        v.put("gross_interest", totals.grossInterest().toPlainString());
        v.put("tax_withheld", totals.taxWithheld().toPlainString());
        v.put("status", "READY");
        ObjectNode cert = rows.insert(TABLE, v);
        ObjectNode payload = cert.deepCopy();
        payload.put("accounts", accounts.size());
        payload.put("postings", totals.postings());
        outbox.publish("accounts.tax_certificate.generated", TABLE, uuid(cert, "id"), payload);
        return cert;
    }

    public String render(UUID id) {
        ObjectNode c = rows.get(TABLE, id);
        if (!"READY".equals(str(c, "status")) && !"DELIVERED".equals(str(c, "status"))) {
            throw ApiException.conflict("certificate_not_ready", "Certificate " + id + " is " + str(c, "status"));
        }
        String ccy = str(c, "currency");
        BigDecimal gross = dec(c, "gross_interest").setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal tax = dec(c, "tax_withheld").setScale(2, RoundingMode.HALF_EVEN);
        return str(c, "certificate_type").replace('_', ' ') + "\n"
                + "Certificate id : " + id + "\n"
                + "Tax year       : " + c.get("tax_year").asInt() + " (" + str(c, "period_start") + " to "
                + str(c, "period_end") + ")\n"
                + "Customer id    : " + str(c, "customer_id") + "\n"
                + "Account        : " + (str(c, "deposit_account_id") == null ? "all accounts" : str(c, "deposit_account_id")) + "\n"
                + "Gross interest : " + gross.toPlainString() + " " + ccy + "\n"
                + "Tax withheld   : " + tax.toPlainString() + " " + ccy + "\n"
                + "Net interest   : " + gross.subtract(tax).toPlainString() + " " + ccy + "\n"
                + "Issued         : " + str(c, "created_at") + "\n";
    }
}
