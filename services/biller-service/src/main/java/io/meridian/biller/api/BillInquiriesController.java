package io.meridian.biller.api;

import io.meridian.biller.api.generated.BillInquiriesApi;
import io.meridian.biller.api.generated.model.BillInquiry;
import io.meridian.biller.api.generated.model.BillInquiryCreate;
import io.meridian.biller.application.CatalogService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Bill lookups at the biller (customer name, amount due, inquiry reference). */
@RestController
public class BillInquiriesController implements BillInquiriesApi {
    private final CatalogService catalog;
    private final Actions actions;

    public BillInquiriesController(CatalogService catalog, Actions actions) {
        this.catalog = catalog;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<BillInquiry> billInquiriesCreate(String idempotencyKey, BillInquiryCreate body) {
        JsonNode req = actions.tree(body);
        return actions.create(idempotencyKey, body, BillInquiry.class,
                () -> catalog.inquire(Http.uuid(req, "biller_id"), Http.text(req, "account_reference"), true));
    }
}
