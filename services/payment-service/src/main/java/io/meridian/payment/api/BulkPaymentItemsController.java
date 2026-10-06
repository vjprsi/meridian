package io.meridian.payment.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.payment.api.generated.BulkPaymentItemsApi;
import io.meridian.payment.api.generated.model.BulkPaymentItem;
import io.meridian.payment.api.generated.model.BulkPaymentItemUpdate;
import io.meridian.payment.application.BulkService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Bulk items: correction of lines before submission (the batch returns to UPLOADED for re-validation). */
@RestController
public class BulkPaymentItemsController implements BulkPaymentItemsApi {
    private final Actions actions;
    private final BulkService bulk;
    private final CrudEngine crud;

    public BulkPaymentItemsController(Actions actions, BulkService bulk, CrudEngine crud) {
        this.actions = actions;
        this.bulk = bulk;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<BulkPaymentItem> bulkPaymentItemsUpdate(UUID id, String ifMatch, BulkPaymentItemUpdate body) {
        TenantContext.require();
        int version = Actions.version(ifMatch);
        BulkPaymentItem current = crud.get("bulk_payment_item", BulkPaymentItem.class, Crud.params().with("id", id)).getBody();
        if (current == null || current.getRowVersion() == null || current.getRowVersion() != version) {
            throw io.meridian.common.error.ApiException.conflict("version_conflict",
                    "If-Match " + version + " does not match the current version; reload and retry");
        }
        var row = bulk.updateItem(id, actions.tree(body));
        return ResponseEntity.ok().eTag("\"" + row.path("row_version").asInt() + "\"").body(actions.model(row, BulkPaymentItem.class));
    }
}
