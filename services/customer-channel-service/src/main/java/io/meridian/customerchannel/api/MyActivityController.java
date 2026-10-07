package io.meridian.customerchannel.api;

import io.meridian.customerchannel.api.generated.MyActivityApi;
import io.meridian.customerchannel.api.generated.model.CustomerActionPage;
import io.meridian.customerchannel.application.ActionAudit;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.security.CustomerContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** The customer's own channel activity (always filtered by the customer of the token). */
@RestController
public class MyActivityController implements MyActivityApi {
    private final CrudEngine crud;

    public MyActivityController(CrudEngine crud) {
        this.crud = crud;
    }

    @Override
    public ResponseEntity<CustomerActionPage> myActivityList(Integer limit, String cursor) {
        return crud.list(ActionAudit.TABLE, CustomerActionPage.class, Crud.params()
                .with("customer_id", CustomerContext.customerId()).with("limit", limit).with("cursor", cursor));
    }
}
