package io.meridian.loanorigination.application;

import static io.meridian.loanorigination.infrastructure.Rows.str;

import io.meridian.common.error.ApiException;
import io.meridian.loanorigination.infrastructure.LoanServiceClient;
import io.meridian.loanorigination.infrastructure.Rows;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Books the loan of a signed application in loan-service. The remote call runs outside any local transaction with
 * the deterministic idempotency key {@code loan-application:<id>} (a retry replays the same loan); the outcome is
 * recorded afterwards: BOOKED with the loan id, or the booking error kept on the SIGNED application for a retry.
 */
@Service
public class BookingService {
    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final Support support;
    private final Rows rows;
    private final LoanServiceClient loans;
    private final TransactionTemplate tx;

    public BookingService(Support support, LoanServiceClient loans, TransactionTemplate tx) {
        this.support = support;
        this.rows = support.rows();
        this.loans = loans;
        this.tx = tx;
    }

    /**
     * Books the loan; {@code failLoudly} rethrows a loan-service failure (the /book action), otherwise it is only
     * recorded (callback path, where the provider must get an acknowledgement).
     */
    public ObjectNode book(UUID appId, boolean failLoudly) {
        ObjectNode request = tx.execute(s -> {
            ObjectNode app = support.app(appId);
            String status = str(app, "status");
            if ("BOOKED".equals(status) || "DISBURSED".equals(status)) {
                return null;
            }
            if (!"SIGNED".equals(status)) {
                throw ApiException.conflict("invalid_transition", "Application " + appId + " is " + status
                        + "; only signed applications are booked");
            }
            return body(app);
        });
        if (request == null) {
            return support.app(appId);
        }
        JsonNode loan;
        try {
            loan = loans.createLoan(request, "loan-application:" + appId);
        } catch (RuntimeException e) {
            String message = e instanceof ApiException a ? a.code() + ": " + a.getMessage() : e.getClass().getSimpleName()
                    + ": " + e.getMessage();
            log.warn("booking the loan of application {} failed: {}", appId, message);
            tx.executeWithoutResult(s -> {
                ObjectNode v = rows.object();
                v.put("booking_error", message.length() > 500 ? message.substring(0, 500) : message);
                rows.update(Support.APP, appId, v);
            });
            if (failLoudly) {
                throw e;
            }
            return support.app(appId);
        }
        return tx.execute(s -> {
            ObjectNode app = support.lockApp(appId);
            if (!"SIGNED".equals(str(app, "status"))) {
                return app;
            }
            ObjectNode extra = rows.object();
            extra.put("loan_id", loan.path("id").asString());
            extra.putNull("booking_error");
            ObjectNode updated = support.transition(app, "BOOKED", extra);
            support.events().publish("lending.loan_application.booked", Support.APP, updated);
            return updated;
        });
    }

    /** LoanCreate body for loan-service from the application and its accepted offer. */
    ObjectNode body(ObjectNode app) {
        UUID appId = Rows.uuid(app, "id");
        ObjectNode offer = rows.queryOne("""
                select row_to_json(o)::text from loan_offer o where loan_application_id = ? and status = 'ACCEPTED'
                order by accepted_at desc limit 1""", appId)
                .orElseThrow(() -> ApiException.conflict("no_accepted_offer", "Application " + appId + " has no accepted offer"));
        if (app.get("legal_entity_id") == null || app.get("legal_entity_id").isNull()) {
            throw ApiException.unprocessable("legal_entity_required", "The application has no lending legal entity");
        }
        ObjectNode b = rows.object();
        b.put("customer_id", str(app, "customer_id"));
        b.put("loan_application_id", appId.toString());
        b.put("loan_offer_id", str(offer, "id"));
        b.put("product_id", str(app, "product_id"));
        b.put("product_version_id", str(app, "product_version_id"));
        b.put("legal_entity_id", str(app, "legal_entity_id"));
        for (String f : List.of("branch_id", "disbursement_account_id", "repayment_account_id", "risk_grade", "merchant_id")) {
            if (app.hasNonNull(f)) {
                b.put(f, str(app, f));
            }
        }
        b.put("loan_type", str(app, "loan_type"));
        b.put("currency", str(offer, "currency"));
        b.put("principal_amount", str(offer, "offer_amount"));
        boolean zero = Rows.dec(offer, "interest_rate").signum() == 0;
        b.put("interest_rate_type", zero ? "ZERO" : "FIXED");
        b.put("interest_rate", str(offer, "interest_rate"));
        b.put("schedule_type", str(offer, "schedule_type"));
        b.put("repayment_frequency", str(offer, "repayment_frequency"));
        b.put("term_months", Rows.integer(offer, "term_months"));
        if (offer.hasNonNull("first_repayment_date")) {
            b.put("first_repayment_date", str(offer, "first_repayment_date"));
        }
        if (offer.hasNonNull("balloon_amount")) {
            b.put("balloon_amount", str(offer, "balloon_amount"));
        }
        b.put("origination_fee", str(offer, "upfront_fees"));
        b.put("is_nano", "NANO".equals(str(app, "loan_type")));
        ObjectNode meta = b.putObject("metadata");
        meta.put("application_number", str(app, "application_number"));
        meta.put("apr", str(offer, "apr"));
        return b;
    }
}
