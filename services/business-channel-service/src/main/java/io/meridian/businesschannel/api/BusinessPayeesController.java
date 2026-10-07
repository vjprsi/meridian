package io.meridian.businesschannel.api;

import io.meridian.businesschannel.api.generated.BusinessPayeesApi;
import io.meridian.businesschannel.api.generated.model.BusinessPayeesCreateRequest;
import io.meridian.businesschannel.api.generated.model.Payee;
import io.meridian.businesschannel.api.generated.model.PayeePage;
import io.meridian.businesschannel.application.PayeeFxService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Beneficiaries of the business. */
@RestController
public class BusinessPayeesController implements BusinessPayeesApi {
    private final PayeeFxService payees;
    private final Http http;

    public BusinessPayeesController(PayeeFxService payees, Http http) {
        this.payees = payees;
        this.http = http;
    }

    @Override
    public ResponseEntity<PayeePage> businessPayeesList(Integer limit, String cursor) {
        return http.ok(payees.payees(limit, cursor), PayeePage.class);
    }

    @Override
    public ResponseEntity<Payee> businessPayeesCreate(String idempotencyKey, BusinessPayeesCreateRequest body) {
        return http.ok(payees.addPayee(http.body(body), idempotencyKey), Payee.class);
    }

    @Override
    public ResponseEntity<Void> businessPayeesDelete(UUID payeeId, String idempotencyKey) {
        payees.removePayee(payeeId);
        return ResponseEntity.noContent().build();
    }
}
