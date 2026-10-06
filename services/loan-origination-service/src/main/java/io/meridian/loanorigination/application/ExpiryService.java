package io.meridian.loanorigination.application;

import static io.meridian.loanorigination.infrastructure.Rows.str;
import static io.meridian.loanorigination.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.loanorigination.infrastructure.Rows;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

/**
 * End-of-day expiry for one tenant, callable by business-day-service. Validity windows are compared with the
 * database clock ({@code now()}), so the job is naturally idempotent: a second run for the same business date finds
 * nothing left to expire.
 */
@Service
public class ExpiryService {
    private static final String PRE_DECISION = "'DRAFT', 'SUBMITTED', 'IN_REVIEW', 'BUREAU_PENDING', 'SCORED', 'REFERRED'";

    private final Support support;
    private final Rows rows;
    private final Events events;
    private final OriginationApprovals approvals;

    public ExpiryService(Support support, OriginationApprovals approvals) {
        this.approvals = approvals;
        this.support = support;
        this.rows = support.rows();
        this.events = support.events();
    }

    @Transactional
    public ObjectNode expire(LocalDate businessDate) {
        if (businessDate == null) {
            throw ApiException.badRequest("business_date_required", "business_date is required");
        }
        int offers = 0;
        for (ObjectNode o : rows.query("""
                select row_to_json(o)::text from loan_offer o where status = 'ISSUED' and expires_at <= now() for update""")) {
            ObjectNode v = rows.object();
            v.put("status", "EXPIRED");
            v.put("status_reason", "Offer validity lapsed");
            events.publish("lending.loan_offer.expired", OfferService.OFFER, rows.update(OfferService.OFFER, uuid(o, "id"), v));
            offers++;
        }
        int envelopes = 0;
        for (ObjectNode e : rows.query("""
                select row_to_json(e)::text from e_signature_request e
                where status in ('CREATED', 'SENT', 'VIEWED') and expires_at <= now() for update""")) {
            ObjectNode v = rows.object();
            v.put("status", "EXPIRED");
            v.put("status_reason", "Envelope validity lapsed");
            events.publish("lending.e_signature_request.expired", SignatureService.ESIGN,
                    rows.update(SignatureService.ESIGN, uuid(e, "id"), v));
            ObjectNode offer = rows.lock(OfferService.OFFER, uuid(e, "loan_offer_id"));
            if ("ACCEPTED".equals(str(offer, "status"))) {
                ObjectNode w = rows.object();
                w.put("status", "EXPIRED");
                w.put("status_reason", "Agreement not signed in time");
                events.publish("lending.loan_offer.expired", OfferService.OFFER, rows.update(OfferService.OFFER, uuid(offer, "id"), w));
                ObjectNode app = support.lockApp(uuid(offer, "loan_application_id"));
                if ("OFFER_ACCEPTED".equals(str(app, "status"))) {
                    support.transition(app, "APPROVED");
                }
            }
            envelopes++;
        }
        int applications = 0;
        for (ObjectNode a : rows.query("""
                select row_to_json(a)::text from loan_application a where expires_at <= now() and (status in (%s)
                   or (status in ('APPROVED', 'COUNTER_OFFERED') and not exists (
                        select 1 from loan_offer o where o.loan_application_id = a.id and o.status in ('ISSUED', 'ACCEPTED'))))
                for update""".formatted(PRE_DECISION))) {
            ObjectNode extra = rows.object();
            approvals.cancelForApplication(UUID.fromString(str(a, "id")), "application expired");
            rows.execute("""
                    update credit_decision_request set status = 'CANCELLED' where loan_application_id = ? and status = 'PENDING_APPROVAL'""",
                    UUID.fromString(str(a, "id")));
            events.publish("lending.loan_application.expired", Support.APP, support.transition(a, "EXPIRED", extra));
            applications++;
        }
        ObjectNode out = rows.object();
        out.put("business_date", businessDate.toString());
        out.put("offers_expired", offers);
        out.put("envelopes_expired", envelopes);
        out.put("applications_expired", applications);
        out.put("as_of", support.dbNow().toString());
        return out;
    }
}
