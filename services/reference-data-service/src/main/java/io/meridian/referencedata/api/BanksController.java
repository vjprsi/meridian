package io.meridian.referencedata.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.referencedata.api.generated.BanksApi;
import io.meridian.referencedata.api.generated.model.BanksValidateAccountRequest;
import io.meridian.referencedata.application.BankDirectoryService;
import io.meridian.web.idempotency.Idempotency;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Bank directory (read-only, loaded from SWIFTRef-like sources) and account validation. */
@RestController
public class BanksController implements BanksApi {
    private final BankDirectoryService banks;
    private final Idempotency idempotency;

    public BanksController(BankDirectoryService banks, Idempotency idempotency) {
        this.banks = banks;
        this.idempotency = idempotency;
    }

    @Override
    public ResponseEntity<Void> banksValidateAccount(String idempotencyKey, BanksValidateAccountRequest body) {
        TenantContext.require();
        return Responses.body(idempotency.execute(idempotencyKey, body, JsonNode.class, () -> ResponseEntity.ok(
                banks.validateAccount(body.getAccountNumber(), body.getCountry(), body.getClearingCode()))));
    }
}
