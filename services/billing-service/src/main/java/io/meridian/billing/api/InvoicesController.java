package io.meridian.billing.api;

import io.meridian.billing.api.generated.InvoicesApi;
import io.meridian.billing.api.generated.model.InvoicesGenerateRequest;
import io.meridian.billing.api.generated.model.InvoicesRecordPaymentRequest;
import io.meridian.billing.api.generated.model.InvoicesVoidRequest;
import io.meridian.billing.api.generated.model.TenantInvoice;
import io.meridian.billing.api.generated.model.TenantInvoiceLinePage;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Tenant invoices: generate drafts, issue, void, record payments, list lines. */
@RestController
public class InvoicesController implements InvoicesApi {
    private final InvoiceService invoices;
    private final CrudEngine crud;
    private final Idempotency idempotency;

    public InvoicesController(InvoiceService invoices, CrudEngine crud, Idempotency idempotency) {
        this.invoices = invoices;
        this.crud = crud;
        this.idempotency = idempotency;
    }

    /** Returns 201 with {"data":[invoices],"skipped_subscription_ids":[...]} (contract says 204; spec defect). */
    @Override
    public ResponseEntity<Void> invoicesGenerate(String idempotencyKey, InvoicesGenerateRequest body) {
        TenantContext.require();
        return Responses.untyped(idempotency.execute(idempotencyKey, Responses.fingerprint("op", "generate", "body", body),
                JsonNode.class, () -> ResponseEntity.status(HttpStatus.CREATED)
                        .body((JsonNode) invoices.generate(body.getPeriodStart(), body.getPeriodEnd()))));
    }

    @Override
    public ResponseEntity<TenantInvoice> invoicesIssue(UUID id, String idempotencyKey) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, Responses.fingerprint("op", "issue", "id", id.toString()), TenantInvoice.class,
                () -> ResponseEntity.ok(crud.read(invoices.issue(id, LocalDate.now(ZoneOffset.UTC)), TenantInvoice.class)));
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
                        invoices.recordPayment(id, body.getAmount(), body.getPaidAt()), TenantInvoice.class)));
    }

    @Override
    public ResponseEntity<TenantInvoiceLinePage> invoicesLines(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        crud.findOne("tenant_invoice", Map.of("id", id)).orElseThrow(() -> ApiException.notFound("invoice", id));
        return crud.list("tenant_invoice_line", TenantInvoiceLinePage.class,
                Crud.params().with("tenant_invoice_id", id).with("limit", limit).with("cursor", cursor));
    }
}
