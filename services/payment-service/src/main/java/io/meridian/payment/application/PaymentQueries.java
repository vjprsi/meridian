package io.meridian.payment.application;

import static io.meridian.payment.infrastructure.Rows.dec;
import static io.meridian.payment.infrastructure.Rows.decOr0;
import static io.meridian.payment.infrastructure.Rows.str;
import static io.meridian.payment.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.payment.config.PaymentProperties;
import io.meridian.payment.domain.Iban;
import io.meridian.payment.domain.PaymentLifecycle;
import io.meridian.payment.domain.ReceiptPdf;
import io.meridian.payment.infrastructure.RiskClient;
import io.meridian.payment.infrastructure.RoutingClient;
import io.meridian.payment.infrastructure.Rows;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Read-side payment operations: pre-validation and pricing, end-to-end tracker, PDF receipt, due execution. */
@Service
public class PaymentQueries {
    private final Rows rows;
    private final PaymentProcessor processor;
    private final PayeeService payees;
    private final RoutingClient routing;
    private final RiskClient risk;
    private final ApprovalService approvals;
    private final PaymentProperties props;
    private final org.springframework.beans.factory.ObjectProvider<FraudRescoreJob> fraudRescore;

    public PaymentQueries(Rows rows, PaymentProcessor processor, PayeeService payees, RoutingClient routing,
                          RiskClient risk, ApprovalService approvals, PaymentProperties props,
                          org.springframework.beans.factory.ObjectProvider<FraudRescoreJob> fraudRescore) {
        this.fraudRescore = fraudRescore;
        this.rows = rows;
        this.processor = processor;
        this.payees = payees;
        this.routing = routing;
        this.risk = risk;
        this.approvals = approvals;
        this.props = props;
    }

    /**
     * Dry run of a payment: validation errors are reported in the result (not thrown), plus route, cut-off /
     * execution date, fees, FX, Confirmation of Payee and approval requirement. Nothing is stored.
     */
    public ObjectNode validate(JsonNode payment) {
        ObjectNode out = rows.object();
        ArrayNode errors = out.putArray("errors");
        ArrayNode warnings = out.putArray("warnings");
        if (payment == null || !payment.isObject()) {
            throw ApiException.badRequest("invalid_payment", "payment must be an object");
        }
        ObjectNode v;
        try {
            v = processor.prepare(((ObjectNode) payment).deepCopy(), PaymentProcessor.Options.DEFAULT);
        } catch (ApiException e) {
            if (e.status() >= 500) {
                throw e;
            }
            errors.addObject().put("code", e.code()).put("message", e.getMessage());
            out.put("valid", false);
            return out;
        }
        BigDecimal amount = dec(v, "amount");
        BigDecimal fee = decOr0(v, "fee_amount");
        out.putObject("fee").put("amount", fee.toPlainString()).put("currency", str(v, "currency"));
        out.put("total_debit", amount.add(fee).toPlainString());
        out.put("currency", str(v, "currency"));
        if ("OUTBOUND".equals(str(v, "direction"))) {
            ObjectNode r = rows.object();
            for (String f : List.of("payment_type", "direction", "amount", "currency", "creditor_country", "debtor_country",
                    "priority", "requested_execution_date", "creditor_agent_bic", "customer_id")) {
                if (v.hasNonNull(f)) {
                    r.set(f, v.get(f));
                }
            }
            JsonNode route = routing.route(r, "pay-validate:" + UUID.randomUUID());
            out.set("route", route);
            if (!route.path("routable").asBoolean(false)) {
                errors.addObject().put("code", route.path("reason_code").asString("NO_ROUTE"))
                        .put("message", route.path("reason").asString("No rail can carry this payment"));
            } else {
                out.put("execution_date", route.path("execution_date").asString(null));
                if (route.path("after_cutoff").asBoolean(false)) {
                    warnings.addObject().put("code", "after_cutoff")
                            .put("message", "Cut-off has passed; the payment executes on " + route.path("execution_date").asString());
                }
            }
            if (str(v, "creditor_name") != null && str(v, "creditor_account_number") != null) {
                String type = str(v, "creditor_account_id_type");
                String country = "IBAN".equals(type) ? Iban.country(str(v, "creditor_account_number")) : str(v, "creditor_country");
                PayeeService.CopResult cop = payees.check(str(v, "creditor_name"), type, str(v, "creditor_account_number"), country);
                ObjectNode c = out.putObject("payee_verification");
                c.put("result", cop.result());
                c.put("provider", cop.provider());
                if (cop.score() != null) {
                    c.put("match_score", cop.score().toPlainString());
                }
                if (cop.matchedName() != null) {
                    c.put("matched_name", cop.matchedName());
                }
                if (Set.of("NO_MATCH", "CLOSE_MATCH", "ACCOUNT_NOT_FOUND", "ACCOUNT_CLOSED").contains(cop.result())) {
                    warnings.addObject().put("code", "payee_" + cop.result().toLowerCase())
                            .put("message", "Confirmation of Payee returned " + cop.result());
                }
            }
        }
        if (v.hasNonNull("fx_quote_id")) {
            JsonNode q = risk.fxQuote(uuid(v, "fx_quote_id"));
            out.set("fx_quote", q);
            if (!"ACTIVE".equals(q.path("status").asString())) {
                errors.addObject().put("code", "fx_quote_not_active").put("message", "The FX quote is " + q.path("status").asString());
            }
        }
        out.put("approval_levels", processor.approvalSteps(amount, 0).size());
        out.put("valid", errors.isEmpty());
        return out;
    }

    public ObjectNode payment(UUID id) {
        return rows.get("payment", id);
    }

    /** End-to-end tracker (SWIFT gpi style): status timeline, rail, charges, fraud decision, approvals, returns, recalls. */
    public ObjectNode tracker(UUID id) {
        ObjectNode p = rows.get("payment", id);
        ObjectNode out = rows.object();
        for (String f : List.of("id", "status", "payment_type", "direction", "uetr", "end_to_end_id", "transaction_id",
                "clearing_system_ref", "rail_code", "amount", "currency", "instructed_amount", "instructed_currency",
                "fx_rate", "fee_amount", "fee_currency", "charge_bearer", "value_date", "requested_execution_date",
                "rejection_code", "rejection_reason", "submitted_at", "accepted_at", "settled_at", "created_at")) {
            out.set(f, p.get(f));
        }
        String status = str(p, "status");
        out.put("final", PaymentLifecycle.isFinal(status));
        out.put("stage", switch (status) {
            case "INITIATED", "SCREENING", "IN_REPAIR" -> "PROCESSING";
            case "PENDING_APPROVAL" -> "AWAITING_APPROVAL";
            case "ON_HOLD" -> "UNDER_REVIEW";
            case "APPROVED" -> "SCHEDULED";
            case "SUBMITTED" -> "IN_TRANSIT";
            case "ACCEPTED" -> "CREDITOR_AGENT";
            case "SETTLED" -> "COMPLETED";
            default -> status;
        });
        out.put("gpi_status", switch (status) {
            case "SETTLED" -> "ACCC";
            case "ACCEPTED" -> "ACSP";
            case "SUBMITTED" -> "ACSP";
            case "REJECTED", "FAILED", "CANCELLED" -> "RJCT";
            case "RETURNED" -> "RJCT";
            default -> "PDNG";
        });
        ArrayNode timeline = out.putArray("timeline");
        rows.query("select row_to_json(t)::text from payment_status_history t where payment_id = ? order by created_at, id", id)
                .forEach(h -> timeline.addObject()
                        .put("status", str(h, "to_status"))
                        .put("from_status", str(h, "from_status"))
                        .put("at", str(h, "created_at"))
                        .put("reason_code", str(h, "reason_code"))
                        .put("reason", str(h, "reason"))
                        .put("actor", str(h, "actor")));
        ObjectNode fraud = out.putObject("fraud");
        for (String f : List.of("fraud_decision", "fraud_score", "fraud_score_id", "fraud_alert_id", "fraud_degraded",
                "hold_reason")) {
            fraud.set(f.startsWith("fraud_") ? f.substring(6) : f, p.get(f));
        }
        out.set("approvals", approvals.levels(id));
        ArrayNode returns = out.putArray("returns");
        rows.query("select row_to_json(t)::text from payment_return t where payment_id = ? order by created_at", id).forEach(returns::add);
        ArrayNode recalls = out.putArray("recalls");
        rows.query("select row_to_json(t)::text from payment_recall t where payment_id = ? order by created_at", id).forEach(recalls::add);
        if (p.hasNonNull("submitted_at") && p.hasNonNull("settled_at")) {
            out.put("processing_seconds", java.time.Duration.between(java.time.OffsetDateTime.parse(str(p, "submitted_at")),
                    java.time.OffsetDateTime.parse(str(p, "settled_at"))).toSeconds());
        }
        return out;
    }

    /** PDF payment advice; available once the payment has left the bank (submitted or later). */
    public byte[] receipt(UUID id) {
        ObjectNode p = rows.get("payment", id);
        String status = str(p, "status");
        if (!Set.of("SUBMITTED", "ACCEPTED", "SETTLED", "RETURNED").contains(status)) {
            throw ApiException.conflict("receipt_not_available", "A receipt is available once the payment is submitted (status "
                    + status + ")");
        }
        List<ReceiptPdf.Row> lines = new ArrayList<>();
        lines.add(new ReceiptPdf.Row("Payment id", str(p, "id")));
        lines.add(new ReceiptPdf.Row("Status", status));
        lines.add(new ReceiptPdf.Row("Type", str(p, "payment_type") + " / " + str(p, "direction")));
        lines.add(new ReceiptPdf.Row("Amount", dec(p, "amount").toPlainString() + " " + str(p, "currency")));
        if (decOr0(p, "fee_amount").signum() > 0) {
            lines.add(new ReceiptPdf.Row("Fee", dec(p, "fee_amount").toPlainString() + " " + str(p, "fee_currency")));
        }
        if (p.hasNonNull("instructed_amount")) {
            lines.add(new ReceiptPdf.Row("Instructed amount", str(p, "instructed_amount") + " " + str(p, "instructed_currency")
                    + (p.hasNonNull("fx_rate") ? " @ " + str(p, "fx_rate") : "")));
        }
        lines.add(new ReceiptPdf.Row("Payer", nz(str(p, "debtor_name")) + " " + mask(str(p, "debtor_account_number"))));
        lines.add(new ReceiptPdf.Row("Payee", nz(str(p, "creditor_name")) + " " + mask(str(p, "creditor_account_number"))));
        if (p.hasNonNull("creditor_agent_bic")) {
            lines.add(new ReceiptPdf.Row("Payee bank", str(p, "creditor_agent_bic")));
        }
        lines.add(new ReceiptPdf.Row("Reference", nz(firstNonNull(str(p, "reference"), str(p, "remittance_unstructured")))));
        lines.add(new ReceiptPdf.Row("End-to-end id", str(p, "end_to_end_id")));
        lines.add(new ReceiptPdf.Row("UETR", str(p, "uetr")));
        if (p.hasNonNull("transaction_id")) {
            lines.add(new ReceiptPdf.Row("Transaction id", str(p, "transaction_id")));
        }
        if (p.hasNonNull("rail_code")) {
            lines.add(new ReceiptPdf.Row("Rail", str(p, "rail_code")));
        }
        lines.add(new ReceiptPdf.Row("Value date", nz(str(p, "value_date"))));
        lines.add(new ReceiptPdf.Row("Submitted", nz(str(p, "submitted_at"))));
        lines.add(new ReceiptPdf.Row("Settled", nz(str(p, "settled_at"))));
        return ReceiptPdf.render(props.bankName() + " - Payment advice", "Issued " + rows.now() + " for payment "
                + str(p, "end_to_end_id"), lines, "This advice is generated electronically and is valid without signature. BIC "
                + props.bankBic());
    }

    /** Executes APPROVED payments whose execution date has arrived (business-day scheduler entry point). */
    public ObjectNode executeDue() {
        List<ObjectNode> due = rows.query("""
                select row_to_json(t)::text from payment t where status = 'APPROVED'
                and coalesce(greatest(requested_execution_date, value_date), current_date) <= current_date
                order by created_at limit 500""");
        ObjectNode out = rows.object();
        int submitted = 0;
        int settled = 0;
        int rejected = 0;
        ArrayNode failures = out.putArray("failures");
        for (ObjectNode p : due) {
            UUID id = UUID.fromString(str(p, "id"));
            try {
                ObjectNode r = processor.execute(id, PaymentProcessor.Options.DEFAULT);
                switch (str(r, "status")) {
                    case "SUBMITTED" -> submitted++;
                    case "SETTLED" -> settled++;
                    case "REJECTED", "FAILED" -> rejected++;
                    default -> { }
                }
            } catch (ApiException e) {
                failures.addObject().put("payment_id", id.toString()).put("code", e.code()).put("message", e.getMessage());
            }
        }
        out.put("candidates", due.size());
        out.put("submitted", submitted);
        out.put("settled", settled);
        out.put("rejected", rejected);
        out.put("as_of", rows.today().toString());
        // degraded fraud holds whose backoff is due are re-scored too (same idempotency key; FraudRescoreJob)
        out.set("fraud_rescore", fraudRescore.getObject().rescoreDue());
        return out;
    }

    private static String mask(String account) {
        if (account == null) {
            return "";
        }
        return account.length() <= 4 ? account : "****" + account.substring(account.length() - 4);
    }

    private static String nz(String s) {
        return s == null ? "-" : s;
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }
}
