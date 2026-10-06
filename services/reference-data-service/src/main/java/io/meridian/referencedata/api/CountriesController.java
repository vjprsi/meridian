package io.meridian.referencedata.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.referencedata.api.generated.CountriesApi;
import io.meridian.referencedata.api.generated.model.Country;
import io.meridian.referencedata.api.generated.model.CountryUpdate;
import io.meridian.referencedata.application.CurrencyCountryService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Countries (ISO 3166): validated update publishing platform.country.risk_changed on sanctions/FATF changes. */
@RestController
public class CountriesController implements CountriesApi {
    private final CurrencyCountryService service;

    public CountriesController(CurrencyCountryService service) {
        this.service = service;
    }

    @Override
    public ResponseEntity<Country> countriesUpdate(UUID id, String ifMatch, CountryUpdate body) {
        TenantContext.require();
        return service.updateCountry(id, ifMatch, body, Country.class);
    }
}
