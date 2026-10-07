package io.meridian.merchant.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.merchant.api.generated.MerchantSettlementsApi;
import io.meridian.merchant.api.generated.model.MerchantSettlement;
import io.meridian.merchant.api.generated.model.MerchantSettlementCreate;
import io.meridian.merchant.api.generated.model.MerchantSettlementsApproveRequest;
import io.meridian.merchant.api.generated.model.MerchantSettlementsRejectRequest;
import io.meridian.merchant.application.SettlementService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Merchant settlements: calculate, approve (checker), pay, report. */
@RestController
public class MerchantSettlementsController implements MerchantSettlementsApi {
    private final SettlementService settlements;
    private final Actions actions;

    public MerchantSettlementsController(SettlementService settlements, Actions actions) {
        this.settlements = settlements;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<MerchantSettlement> merchantSettlementsCreate(String idempotencyKey, MerchantSettlementCreate body) {
        return actions.create(idempotencyKey, body, MerchantSettlement.class, () -> settlements.create(actions.tree(body)));
    }

    @Override
    public ResponseEntity<MerchantSettlement> merchantSettlementsApprove(UUID id, String idempotencyKey,
                                                                        MerchantSettlementsApproveRequest body) {
        return actions.action(idempotencyKey, id, "approve", body, MerchantSettlement.class,
                () -> settlements.approve(id, Http.text(actions.tree(body), "comment")));
    }

    @Override
    public ResponseEntity<MerchantSettlement> merchantSettlementsReject(UUID id, String idempotencyKey,
                                                                       MerchantSettlementsRejectRequest body) {
        return actions.action(idempotencyKey, id, "reject", body, MerchantSettlement.class,
                () -> settlements.reject(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<MerchantSettlement> merchantSettlementsPay(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "pay", null, MerchantSettlement.class, () -> settlements.pay(id));
    }

    @Override
    public ResponseEntity<String> merchantSettlementsReport(UUID id) {
        TenantContext.require();
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"settlement-" + id + ".csv\"")
                .body(settlements.report(id));
    }
}
