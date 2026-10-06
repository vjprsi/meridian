package io.meridian.referencedata.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.referencedata.api.generated.BanksApi;
import io.meridian.referencedata.api.generated.model.BanksValidateAccountRequest;
import io.meridian.referencedata.application.BankDirectoryService;
import io.meridian.web.idempotency.Idempotency;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Bank directory (read-only, loaded from SWIFTRef-like sources) and account validation. */
@RestController
public class BanksController implements BanksApi {
    private final BankDirectoryService banks;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public BanksController(BankDirectoryService banks, Idempotency idempotency, JsonMapper json) {
        this.banks = banks;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<Map<String, Object>> banksValidateAccount(String idempotencyKey,
                                                                    BanksValidateAccountRequest body) {
        TenantContext.require();
        return Responses.object(json, idempotency.execute(idempotencyKey, body, JsonNode.class, () -> ResponseEntity.ok(
                banks.validateAccount(body.getAccountNumber(), body.getCountry(), body.getClearingCode()))));
    }
}
