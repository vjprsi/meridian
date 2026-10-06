package io.meridian.disbursement.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.disbursement.api.generated.DisbursementBeneficiariesApi;
import io.meridian.disbursement.api.generated.model.DisbursementBeneficiary;
import io.meridian.disbursement.api.generated.model.DisbursementBeneficiaryCreate;
import io.meridian.disbursement.api.generated.model.DisbursementBeneficiaryUpdate;
import io.meridian.disbursement.application.BeneficiaryService;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Manual beneficiary lines (only while the batch is a DRAFT). */
@RestController
public class DisbursementBeneficiariesController implements DisbursementBeneficiariesApi {
    private final BeneficiaryService beneficiaries;
    private final Actions actions;

    public DisbursementBeneficiariesController(BeneficiaryService beneficiaries, Actions actions) {
        this.beneficiaries = beneficiaries;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<DisbursementBeneficiary> disbursementBeneficiariesCreate(String idempotencyKey,
                                                                                   DisbursementBeneficiaryCreate body) {
        return actions.create(idempotencyKey, body, DisbursementBeneficiary.class,
                () -> beneficiaries.create(actions.tree(body)));
    }

    @Override
    public ResponseEntity<DisbursementBeneficiary> disbursementBeneficiariesUpdate(UUID id, String ifMatch,
                                                                                   DisbursementBeneficiaryUpdate body) {
        TenantContext.require();
        JsonNode row = beneficiaries.update(id, ifMatch, actions.tree(body));
        return ResponseEntity.ok().header(HttpHeaders.ETAG, "\"" + row.get("row_version").asInt() + "\"")
                .body(actions.model(row, DisbursementBeneficiary.class));
    }
}
