package io.meridian.insights.api;

import io.meridian.insights.api.generated.CashFlowForecastsApi;
import io.meridian.insights.api.generated.model.CashFlowForecast;
import io.meridian.insights.api.generated.model.CashFlowForecastCreate;
import io.meridian.insights.application.ForecastService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Cash-flow forecasts: computed server-side on create (append-only snapshots). */
@RestController
public class CashFlowForecastsController implements CashFlowForecastsApi {
    private final ForecastService forecasts;
    private final Http http;

    public CashFlowForecastsController(ForecastService forecasts, Http http) {
        this.forecasts = forecasts;
        this.http = http;
    }

    @Override
    public ResponseEntity<CashFlowForecast> cashFlowForecastsCreate(String idempotencyKey, CashFlowForecastCreate body) {
        return http.created(idempotencyKey, "forecast.create", body, CashFlowForecast.class, () -> forecasts.create(http.tree(body)));
    }
}
