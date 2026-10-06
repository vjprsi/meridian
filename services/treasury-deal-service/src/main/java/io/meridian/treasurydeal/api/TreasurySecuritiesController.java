package io.meridian.treasurydeal.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.treasurydeal.api.generated.TreasurySecuritiesApi;
import io.meridian.treasurydeal.api.generated.model.TreasurySecuritiesPricesRequest;
import io.meridian.treasurydeal.api.generated.model.TreasurySecurity;
import io.meridian.treasurydeal.api.generated.model.TreasurySecurityCreate;
import io.meridian.treasurydeal.api.generated.model.TreasurySecurityUpdate;
import io.meridian.treasurydeal.application.SecuritiesService;
import io.meridian.treasurydeal.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Security master with ISIN validation, prices and bond analytics. */
@RestController
public class TreasurySecuritiesController implements TreasurySecuritiesApi {
    private final SecuritiesService service;
    private final Actions actions;

    public TreasurySecuritiesController(SecuritiesService service, Actions actions) {
        this.service = service;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<TreasurySecurity> treasurySecuritiesCreate(String idempotencyKey, TreasurySecurityCreate body) {
        return actions.create(idempotencyKey, body, TreasurySecurity.class, () -> service.createSecurity(actions.tree(body)));
    }

    @Override
    public ResponseEntity<TreasurySecurity> treasurySecuritiesUpdate(UUID id, String ifMatch, TreasurySecurityUpdate body) {
        TenantContext.require();
        return actions.withEtag(service.updateSecurity(id, Actions.version(ifMatch), actions.tree(body)), TreasurySecurity.class);
    }

    @Override
    public ResponseEntity<TreasurySecurity> treasurySecuritiesPrices(UUID id, String idempotencyKey,
                                                                     TreasurySecuritiesPricesRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "prices", body, TreasurySecurity.class,
                () -> service.recordPrice(id, Http.decimal(req, "price"), Rows.date(req, "price_date")));
    }

    @Override
    public ResponseEntity<Map<String, Object>> treasurySecuritiesAnalytics(UUID id, BigDecimal cleanPrice,
                                                                           LocalDate settlementDate, BigDecimal nominal) {
        TenantContext.require();
        return actions.object(service.analytics(id, cleanPrice, settlementDate, nominal));
    }
}
