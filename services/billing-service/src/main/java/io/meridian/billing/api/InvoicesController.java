package io.meridian.billing.api;

import io.meridian.billing.api.generated.InvoicesApi;
import io.meridian.billing.api.generated.model.InvoicesGenerateRequest;
import io.meridian.billing.api.generated.model.InvoicesRecordPaymentRequest;
import io.meridian.billing.api.generated.model.InvoicesVoidRequest;
import io.meridian.billing.api.generated.model.TenantInvoice;
import io.meridian.billing.api.generated.model.TenantInvoiceLinePage;
import io.meridian.billing.api.generated.model.TenantInvoicePaymentPage;
import io.meridian.billing.application.InvoiceService;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Tenant invoices: generate drafts, issue, void, record payments, list lines and payments. */
@RestController
public class InvoicesController implements InvoicesApi {
    private final InvoiceService invoices;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final io.meridian.web.time.BusinessDates businessDates;

    public InvoicesController(InvoiceService invoices, CrudEngine crud, Idempotency idempotency,
                              io.meridian.web.time.BusinessDates businessDates) {
        this.businessDates = businessDates;
        this.invoices = invoices;
        this.crud = crud;
        this.idempotency = idempotency;
    }

    /** The tenant's business date (business-day-service), else the UTC calendar date. */
    private LocalDate today() {
        return businessDates.today(() -> LocalDate.now(ZoneOffset.UTC));
    }

    /** Returns {"data":[draft invoices],"skipped_subscription_ids":[...]}. */
    @Override
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> invoicesGenerate(String idempotencyKey, InvoicesGenerateRequest body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, Responses.fingerprint("op", "generate", "body", body),
                (Class<Map<String, Object>>) (Class<?>) Map.class,
                () -> ResponseEntity.ok(invoices.generate(body.getPeriodStart(), body.getPeriodEnd())));
    }

    @Override
    public ResponseEntity<TenantInvoice> invoicesIssue(UUID id, String idempotencyKey) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, Responses.fingerprint("op", "issue", "id", id.toString()), TenantInvoice.class,
                () -> ResponseEntity.ok(crud.read(invoices.issue(id, today()), TenantInvoice.class)));
    }

    @Override
    public ResponseEntity<TenantInvoice> invoicesVoid(UUID id, String idempotencyKey, InvoicesVoidRequest body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, Responses.fingerprint("op", "void", "id", id.toString(), "body", body),
                TenantInvoice.class,
                () -> ResponseEntity.ok(crud.read(invoices.voidInvoice(id, body.getReason()), TenantInvoice.class)));
    }

    @Override
    public ResponseEntity<TenantInvoice> invoicesRecordPayment(UUID id, String idempotencyKey,
                                                               InvoicesRecordPaymentRequest body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, Responses.fingerprint("op", "record-payment", "id", id.toString(), "body", body),
                TenantInvoice.class, () -> ResponseEntity.ok(crud.read(
                        invoices.recordPayment(id, body.getAmount(), body.getPaidAt(), body.getReference()), TenantInvoice.class)));
    }

    @Override
    public ResponseEntity<TenantInvoicePaymentPage> invoicesPayments(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        crud.findOne("tenant_invoice", Map.of("id", id)).orElseThrow(() -> ApiException.notFound("invoice", id));
        return crud.list("tenant_invoice_payment", TenantInvoicePaymentPage.class,
                Crud.params().with("tenant_invoice_id", id).with("limit", limit).with("cursor", cursor));
    }

    @Override
    public ResponseEntity<TenantInvoiceLinePage> invoicesLines(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        crud.findOne("tenant_invoice", Map.of("id", id)).orElseThrow(() -> ApiException.notFound("invoice", id));
        return crud.list("tenant_invoice_line", TenantInvoiceLinePage.class,
                Crud.params().with("tenant_invoice_id", id).with("limit", limit).with("cursor", cursor));
    }
}
