package io.meridian.referencedata.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.referencedata.api.generated.CurrenciesApi;
import io.meridian.referencedata.api.generated.model.Currency;
import io.meridian.referencedata.api.generated.model.CurrencyCreate;
import io.meridian.referencedata.api.generated.model.CurrencyUpdate;
import io.meridian.referencedata.application.CurrencyCountryService;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Currencies (ISO 4217): validated create/update publishing platform.currency.changed. */
@RestController
public class CurrenciesController implements CurrenciesApi {
    private final CurrencyCountryService service;
    private final CrudEngine crud;
    private final Idempotency idempotency;

    public CurrenciesController(CurrencyCountryService service, CrudEngine crud, Idempotency idempotency) {
        this.service = service;
        this.crud = crud;
        this.idempotency = idempotency;
    }

    @Override
    public ResponseEntity<Currency> currenciesCreate(String idempotencyKey, CurrencyCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, Currency.class, () -> ResponseEntity.status(HttpStatus.CREATED)
                .body(crud.read(service.createCurrency(body), Currency.class)));
    }

    @Override
    public ResponseEntity<Currency> currenciesUpdate(UUID id, String ifMatch, CurrencyUpdate body) {
        TenantContext.require();
        return service.updateCurrency(id, ifMatch, body, Currency.class);
    }
}
