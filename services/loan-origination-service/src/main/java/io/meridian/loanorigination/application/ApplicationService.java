package io.meridian.loanorigination.application;

import static io.meridian.loanorigination.infrastructure.Rows.dec;
import static io.meridian.loanorigination.infrastructure.Rows.integer;
import static io.meridian.loanorigination.infrastructure.Rows.str;
import static io.meridian.loanorigination.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.loanorigination.config.OriginationProperties;
import io.meridian.loanorigination.domain.Currencies;
import io.meridian.loanorigination.domain.Lifecycles;
import io.meridian.loanorigination.infrastructure.ProductCatalogClient;
import io.meridian.loanorigination.infrastructure.Rows;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Loan applications: intake with product checks, guarded edits, submission, assignment and withdrawal. */
@Service
public class ApplicationService {
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> PROTECTED = Set.of("customer_id", "product_id", "product_version_id",
            "legal_entity_id", "currency");

    private final Support support;
    private final Rows rows;
    private final Events events;
    private final ProductCatalogClient catalog;
    private final OriginationProperties props;
    private final SignatureService signatures;

    public ApplicationService(Support support, ProductCatalogClient catalog, OriginationProperties props,
                              SignatureService signatures) {
        this.support = support;
        this.rows = support.rows();
        this.events = support.events();
        this.catalog = catalog;
        this.props = props;
        this.signatures = signatures;
    }

    // ------------------------------------------------------------------ intake

    @Transactional
    public ObjectNode create(Object body) {
        ObjectNode v = rows.columns(Support.APP, body);
        UUID productId = uuid(v, "product_id");
        ProductTerms terms = catalog.terms(productId, uuid(v, "product_version_id"));
        String currency = str(v, "currency");
        if (terms.currency() != null && !terms.currency().equals(currency)) {
            throw ApiException.unprocessable("currency_mismatch",
                    "Product " + productId + " is sold in " + terms.currency() + ", not " + currency);
        }
        validateRequest(terms, dec(v, "requested_amount"), integer(v, "requested_term_months"), currency);
        v.put("product_version_id", terms.productVersionId().toString());
        if (!v.hasNonNull("legal_entity_id") && terms.legalEntityId() != null) {
            v.put("legal_entity_id", terms.legalEntityId().toString());
        }
        if (Rows.bool(v, "consent_bureau") && !v.hasNonNull("consent_at")) {
            v.put("consent_at", support.dbNow().toString());
        }
        if (!v.hasNonNull("expires_at")) {
            v.put("expires_at", support.dbNow().plusDays(props.applicationValidityDays()).toString());
        }
        v.put("application_number", number("LA", "application_number"));
        v.put("status", "DRAFT");
        ObjectNode row = rows.insert(Support.APP, v);
        events.publish("lending.loan_application.created", Support.APP, row);
        return row;
    }

    /** Pre-checks of a PATCH (only DRAFT applications; product and parties are fixed once created). */
    @Transactional(readOnly = true)
    public void checkUpdate(UUID id, JsonNode req) {
        ObjectNode app = support.app(id);
        if (!"DRAFT".equals(str(app, "status"))) {
            throw ApiException.conflict("application_not_editable",
                    "Application " + id + " is " + str(app, "status") + "; only DRAFT applications can be changed");
        }
        for (String f : PROTECTED) {
            if (req.hasNonNull(f)) {
                throw ApiException.unprocessable("field_not_updatable", f + " cannot be changed; start a new application");
            }
        }
        if (req.hasNonNull("requested_amount") || req.hasNonNull("requested_term_months")) {
            ProductTerms terms = catalog.terms(uuid(app, "product_id"), uuid(app, "product_version_id"));
            BigDecimal amount = req.hasNonNull("requested_amount") ? new BigDecimal(req.get("requested_amount").asString())
                    : dec(app, "requested_amount");
            Integer term = req.hasNonNull("requested_term_months") ? req.get("requested_term_months").asInt()
                    : integer(app, "requested_term_months");
            validateRequest(terms, amount, term, str(app, "currency"));
        }
    }

    @Transactional
    public ObjectNode submit(UUID id) {
        ObjectNode app = support.lockApp(id);
        Support.require(Lifecycles.APPLICATION, "Loan application", str(app, "status"), "SUBMITTED");
        if (!"NANO".equals(str(app, "loan_type")) && !Rows.bool(app, "consent_bureau")) {
            throw ApiException.unprocessable("consent_required",
                    "The applicant must consent to a credit bureau check (consent_bureau) before submission");
        }
        if (integer(app, "requested_term_months") == null) {
            throw ApiException.unprocessable("term_required", "requested_term_months is required for submission");
        }
        ProductTerms terms = catalog.terms(uuid(app, "product_id"), uuid(app, "product_version_id"));
        validateRequest(terms, dec(app, "requested_amount"), integer(app, "requested_term_months"), str(app, "currency"));
        List<ObjectNode> primaries = rows.query("""
                select row_to_json(a)::text from loan_applicant a where loan_application_id = ? and role = 'PRIMARY'""", id);
        if (primaries.size() > 1) {
            throw ApiException.unprocessable("multiple_primary_applicants", "An application has exactly one PRIMARY applicant");
        }
        if (primaries.isEmpty()) {
            ObjectNode a = rows.object();
            a.put("loan_application_id", id.toString());
            a.put("customer_id", str(app, "customer_id"));
            a.put("role", "PRIMARY");
            a.put("share_of_liability", "1");
            rows.insert("loan_applicant", a);
        } else if (!str(app, "customer_id").equals(str(primaries.getFirst(), "customer_id"))) {
            throw ApiException.unprocessable("primary_applicant_mismatch",
                    "The PRIMARY applicant must be the application's customer");
        }
        ObjectNode extra = rows.object();
        extra.put("submitted_at", support.dbNow().toString());
        extra.put("product_version_id", terms.productVersionId().toString());
        ObjectNode updated = support.transition(app, "SUBMITTED", extra);
        events.publish("lending.loan_application.submitted", Support.APP, updated);
        return updated;
    }

    @Transactional
    public ObjectNode assign(UUID id, String assignee) {
        if (assignee == null || assignee.isBlank()) {
            throw ApiException.unprocessable("field_required", "assigned_to is required");
        }
        ObjectNode app = support.lockApp(id);
        Support.requireStatus(app, Set.of("SUBMITTED", "SCORED", "REFERRED", "IN_REVIEW"), "Application " + id,
                "invalid_transition");
        ObjectNode extra = rows.object();
        extra.put("assigned_to", assignee);
        ObjectNode updated = support.transition(app, "IN_REVIEW", extra);
        events.publish("lending.loan_application.assigned", Support.APP, updated);
        return updated;
    }

    @Transactional
    public ObjectNode withdraw(UUID id, String reason) {
        ObjectNode app = support.lockApp(id);
        Support.require(Lifecycles.APPLICATION, "Loan application", str(app, "status"), "WITHDRAWN");
        for (ObjectNode offer : rows.query("""
                select row_to_json(o)::text from loan_offer o where loan_application_id = ? and status in ('ISSUED', 'ACCEPTED')
                for update""", id)) {
            ObjectNode v = rows.object();
            v.put("status", "WITHDRAWN");
            v.put("status_reason", "Application withdrawn");
            events.publish("lending.loan_offer.withdrawn", "loan_offer", rows.update("loan_offer", uuid(offer, "id"), v));
            signatures.voidOpenEnvelopes(uuid(offer, "id"), "Application withdrawn");
        }
        rows.execute("""
                update credit_decision_request set status = 'CANCELLED', updated_by = ?
                where loan_application_id = ? and status = 'PENDING_APPROVAL'""", TenantContext.principal(), id);
        ObjectNode extra = rows.object();
        if (reason != null) {
            extra.put("withdrawal_reason", reason);
        }
        ObjectNode updated = support.transition(app, "WITHDRAWN", extra);
        events.publish("lending.loan_application.withdrawn", Support.APP, updated);
        return updated;
    }

    // ------------------------------------------------------------------ applicants, financial items, documents

    /** Applicants and declared finances can change until the application is decided. */
    @Transactional(readOnly = true)
    public ObjectNode requireEditable(UUID appId) {
        ObjectNode app = support.app(appId);
        if (!Lifecycles.APPLICATION_EDITABLE.contains(str(app, "status"))) {
            throw ApiException.conflict("application_not_editable",
                    "Application " + appId + " is " + str(app, "status") + "; applicants and finances are frozen after the decision");
        }
        return app;
    }

    @Transactional(readOnly = true)
    public void checkApplicant(UUID appId, JsonNode req, UUID applicantId) {
        ObjectNode app = requireEditable(appId);
        if ("PRIMARY".equals(Json.text(req, "role"))) {
            ObjectNode primary = support.primaryApplicant(appId);
            if (primary != null && !uuid(primary, "id").equals(applicantId)) {
                throw ApiException.conflict("primary_applicant_exists", "The application already has a PRIMARY applicant");
            }
            if (req.hasNonNull("customer_id") && !str(app, "customer_id").equals(Json.text(req, "customer_id"))) {
                throw ApiException.unprocessable("primary_applicant_mismatch",
                        "The PRIMARY applicant must be the application's customer");
            }
        }
        BigDecimal share = Json.decimal(req, "share_of_liability");
        if (share != null && (share.signum() < 0 || share.compareTo(BigDecimal.ONE) > 0)) {
            throw ApiException.unprocessable("invalid_share_of_liability", "share_of_liability must be between 0 and 1");
        }
        BigDecimal income = Json.decimal(req, "monthly_net_income");
        if (income != null && income.signum() < 0) {
            throw ApiException.unprocessable("invalid_amount", "monthly_net_income must not be negative");
        }
        if (req.hasNonNull("date_of_birth") && LocalDate.parse(Json.text(req, "date_of_birth")).isAfter(support.dbToday())) {
            throw ApiException.unprocessable("invalid_date_of_birth", "date_of_birth is in the future");
        }
    }

    @Transactional(readOnly = true)
    public void checkApplicantDelete(UUID appId, UUID applicantId) {
        ObjectNode app = requireEditable(appId);
        ObjectNode a = rows.queryOne("select row_to_json(a)::text from loan_applicant a where id = ? and loan_application_id = ?",
                applicantId, appId).orElseThrow(() -> ApiException.notFound("loan applicant", applicantId));
        if ("PRIMARY".equals(str(a, "role")) && !"DRAFT".equals(str(app, "status"))) {
            throw ApiException.conflict("primary_applicant_required", "The PRIMARY applicant of a submitted application cannot be removed");
        }
        int dependents = rows.scalar("""
                select (select count(*) from loan_financial_item where loan_applicant_id = ?)
                     + (select count(*) from loan_application_document where loan_applicant_id = ?)
                     + (select count(*) from bureau_report where loan_applicant_id = ?)
                     + (select count(*) from credit_score where loan_applicant_id = ?)""", Integer.class,
                applicantId, applicantId, applicantId, applicantId).orElse(0);
        if (dependents > 0) {
            throw ApiException.conflict("applicant_in_use",
                    "The applicant has financial items, documents, bureau reports or scores; remove those first");
        }
    }

    @Transactional(readOnly = true)
    public void checkFinancialItem(UUID appId, JsonNode req) {
        ObjectNode app = requireEditable(appId);
        if (req.hasNonNull("loan_applicant_id")) {
            applicantOf(appId, UUID.fromString(Json.text(req, "loan_applicant_id")));
        }
        BigDecimal amount = Json.decimal(req, "amount");
        if (amount != null && amount.signum() <= 0) {
            throw ApiException.unprocessable("invalid_amount", "amount must be positive");
        }
        if (req.hasNonNull("currency") && !str(app, "currency").equals(Json.text(req, "currency"))) {
            throw ApiException.unprocessable("currency_mismatch", "Financial items must be in the application currency "
                    + str(app, "currency"));
        }
    }

    @Transactional
    public ObjectNode createApplicant(UUID appId, Object body) {
        support.lockApp(appId);
        ObjectNode v = rows.columns("loan_applicant", body);
        samePath(v, "loan_application_id", appId);
        checkApplicant(appId, v, null);
        v.put("loan_application_id", appId.toString());
        return rows.insert("loan_applicant", v);
    }

    @Transactional
    public ObjectNode createFinancialItem(UUID appId, Object body) {
        support.lockApp(appId);
        ObjectNode v = rows.columns("loan_financial_item", body);
        checkFinancialItem(appId, v);
        return rows.insert("loan_financial_item", v);
    }

    /**
     * Financial items of one application (the table hangs off the applicant, so the application in the path is
     * resolved through loan_applicant): keyset paging newest first, like the generic lists.
     */
    @Transactional(readOnly = true)
    public ObjectNode listFinancialItems(UUID appId, Integer limit, String cursor, UUID applicantId, String itemType,
                                         Boolean verified) {
        support.app(appId);
        int n = io.meridian.common.page.Cursor.limit(limit);
        StringBuilder sql = new StringBuilder("""
                select row_to_json(i)::text from loan_financial_item i join loan_applicant a on a.id = i.loan_applicant_id
                where a.loan_application_id = ?""");
        List<Object> args = new java.util.ArrayList<>(List.of(appId));
        if (applicantId != null) {
            sql.append(" and i.loan_applicant_id = ?");
            args.add(applicantId);
        }
        if (itemType != null) {
            sql.append(" and i.item_type = ?");
            args.add(itemType);
        }
        if (verified != null) {
            sql.append(" and i.is_verified = ?");
            args.add(verified);
        }
        if (cursor != null && !cursor.isBlank()) {
            sql.append(" and i.id < ?");
            args.add(io.meridian.common.page.Cursor.decode(cursor));
        }
        sql.append(" order by i.id desc limit ?");
        args.add(n + 1);
        List<ObjectNode> found = rows.query(sql.toString(), args.toArray());
        boolean more = found.size() > n;
        List<ObjectNode> data = more ? found.subList(0, n) : found;
        ObjectNode page = rows.object();
        tools.jackson.databind.node.ArrayNode arr = page.putArray("data");
        data.forEach(arr::add);
        page.put("has_more", more);
        if (more) {
            page.put("next_cursor", io.meridian.common.page.Cursor.encode(uuid(data.getLast(), "id")));
        } else {
            page.putNull("next_cursor");
        }
        return page;
    }

    /** A financial item addressed under an application must belong to one of its applicants. */
    @Transactional(readOnly = true)
    public void requireFinancialItem(UUID appId, UUID itemId) {
        if (rows.scalar("""
                select count(*) from loan_financial_item i join loan_applicant a on a.id = i.loan_applicant_id
                where i.id = ? and a.loan_application_id = ?""", Integer.class, itemId, appId).orElse(0) == 0) {
            throw ApiException.notFound("loan financial item", itemId);
        }
    }

    @Transactional
    public ObjectNode createDocument(UUID appId, Object body) {
        ObjectNode app = support.lockApp(appId);
        if (Lifecycles.APPLICATION_TERMINAL.contains(str(app, "status")) || "BOOKED".equals(str(app, "status"))) {
            throw ApiException.conflict("application_closed", "Application " + appId + " is " + str(app, "status"));
        }
        ObjectNode v = rows.columns("loan_application_document", body);
        samePath(v, "loan_application_id", appId);
        v.put("loan_application_id", appId.toString());
        if (v.hasNonNull("loan_applicant_id")) {
            applicantOf(appId, uuid(v, "loan_applicant_id"));
        }
        v.put("status", v.hasNonNull("document_id") ? "UPLOADED" : "REQUESTED");
        return rows.insert("loan_application_document", v);
    }

    /** After a PATCH: a newly attached document of a requested or rejected item becomes UPLOADED. */
    @Transactional
    public ObjectNode afterDocumentUpdate(UUID appId, UUID docId) {
        ObjectNode doc = document(appId, docId, true);
        if (doc.hasNonNull("document_id") && Set.of("REQUESTED", "REJECTED", "EXPIRED").contains(str(doc, "status"))) {
            ObjectNode v = rows.object();
            v.put("status", "UPLOADED");
            v.putNull("rejection_reason");
            return rows.update("loan_application_document", docId, v);
        }
        return doc;
    }

    @Transactional(readOnly = true)
    public void checkDocumentUpdate(UUID appId, UUID docId, JsonNode req) {
        ObjectNode doc = document(appId, docId, false);
        if ("VERIFIED".equals(str(doc, "status")) && req.hasNonNull("document_id")) {
            throw ApiException.conflict("document_verified", "A verified document cannot be replaced");
        }
        if (req.hasNonNull("loan_applicant_id")) {
            applicantOf(appId, UUID.fromString(Json.text(req, "loan_applicant_id")));
        }
    }

    @Transactional
    public ObjectNode verifyDocument(UUID appId, UUID docId) {
        ObjectNode doc = document(appId, docId, true);
        Support.require(Lifecycles.DOCUMENT, "Document", str(doc, "status"), "VERIFIED");
        if (!doc.hasNonNull("document_id")) {
            throw ApiException.unprocessable("document_missing", "No document has been uploaded");
        }
        Support.fourEyes(str(doc, "created_by"), "Document verification");
        ObjectNode v = rows.object();
        v.put("status", "VERIFIED");
        v.put("verified_by", TenantContext.principal());
        v.put("verified_at", support.dbNow().toString());
        ObjectNode updated = rows.update("loan_application_document", docId, v);
        events.publish("lending.loan_application_document.verified", "loan_application_document", updated);
        return updated;
    }

    @Transactional
    public ObjectNode rejectDocument(UUID appId, UUID docId, String reason) {
        ObjectNode doc = document(appId, docId, true);
        Support.require(Lifecycles.DOCUMENT, "Document", str(doc, "status"), "REJECTED");
        ObjectNode v = rows.object();
        v.put("status", "REJECTED");
        v.put("rejection_reason", reason);
        ObjectNode updated = rows.update("loan_application_document", docId, v);
        events.publish("lending.loan_application_document.rejected", "loan_application_document", updated);
        return updated;
    }

    // ------------------------------------------------------------------ helpers

    /** The parent id repeated in a nested create body must be the one in the path. */
    static void samePath(JsonNode body, String field, UUID pathId) {
        if (body.hasNonNull(field) && !pathId.toString().equals(body.get(field).asString())) {
            throw ApiException.unprocessable("path_mismatch", field + " in the body does not match the path");
        }
    }

    private ObjectNode document(UUID appId, UUID docId, boolean lock) {
        return rows.queryOne("select row_to_json(d)::text from loan_application_document d where id = ? and loan_application_id = ?"
                        + (lock ? " for update" : ""), docId, appId)
                .orElseThrow(() -> ApiException.notFound("loan application document", docId));
    }

    private void applicantOf(UUID appId, UUID applicantId) {
        if (rows.scalar("select count(*) from loan_applicant where id = ? and loan_application_id = ?", Integer.class,
                applicantId, appId).orElse(0) == 0) {
            throw ApiException.unprocessable("applicant_not_found",
                    "loan_applicant_id " + applicantId + " is not an applicant of application " + appId);
        }
    }

    static void validateRequest(ProductTerms terms, BigDecimal amount, Integer term, String currency) {
        if (amount == null || amount.signum() <= 0) {
            throw ApiException.unprocessable("invalid_amount", "requested_amount must be positive");
        }
        if (!Currencies.fitsMinorUnit(amount, currency)) {
            throw ApiException.unprocessable("invalid_amount", "requested_amount has more decimals than " + currency + " allows");
        }
        if (terms.minAmount() != null && amount.compareTo(terms.minAmount()) < 0
                || terms.maxAmount() != null && amount.compareTo(terms.maxAmount()) > 0) {
            throw ApiException.unprocessable("amount_out_of_range", "requested_amount must be between "
                    + plain(terms.minAmount()) + " and " + plain(terms.maxAmount()) + " for this product");
        }
        if (term != null) {
            if (term <= 0) {
                throw ApiException.unprocessable("term_out_of_range", "requested_term_months must be positive");
            }
            if (terms.minTermMonths() != null && term < terms.minTermMonths()
                    || terms.maxTermMonths() != null && term > terms.maxTermMonths()) {
                throw ApiException.unprocessable("term_out_of_range", "requested_term_months must be between "
                        + terms.minTermMonths() + " and " + terms.maxTermMonths() + " for this product");
            }
        }
    }

    private static String plain(BigDecimal v) {
        return v == null ? "-" : v.toPlainString();
    }

    /** Server-generated human-readable number: PREFIX-YYYY-XXXXXXXX (Crockford base32), unique in the tenant. */
    String number(String prefix, String column) {
        String year = String.valueOf(support.dbToday().getYear());
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder sb = new StringBuilder(prefix).append('-').append(year).append('-');
            for (int i = 0; i < 8; i++) {
                sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
            }
            String candidate = sb.toString();
            if (rows.scalar("select count(*) from loan_application where " + column + " = ?", Integer.class, candidate)
                    .orElse(0) == 0) {
                return candidate;
            }
        }
        throw new IllegalStateException("could not allocate a unique application number");
    }
}
