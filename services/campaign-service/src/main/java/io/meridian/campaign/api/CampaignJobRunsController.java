package io.meridian.campaign.api;

import io.meridian.campaign.api.generated.CampaignJobRunsApi;
import io.meridian.campaign.application.CampaignJobs;
import io.meridian.campaign.application.CampaignJobs.Job;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Batch jobs (business-day-service): lifecycle, metrics rollup and offer expiry, idempotent per business date. */
@RestController
public class CampaignJobRunsController implements CampaignJobRunsApi {
    private final CampaignJobs jobs;
    private final Http http;

    public CampaignJobRunsController(CampaignJobs jobs, Http http) {
        this.jobs = jobs;
        this.http = http;
    }

    @Override
    public ResponseEntity<Map<String, Object>> campaignJobRunsLifecycle(LocalDate businessDate, String idempotencyKey) {
        return run(Job.LIFECYCLE, businessDate, idempotencyKey);
    }

    @Override
    public ResponseEntity<Map<String, Object>> campaignJobRunsMetricsRollup(LocalDate businessDate, String idempotencyKey) {
        return run(Job.METRICS_ROLLUP, businessDate, idempotencyKey);
    }

    @Override
    public ResponseEntity<Map<String, Object>> campaignJobRunsOfferExpiry(LocalDate businessDate, String idempotencyKey) {
        return run(Job.OFFER_EXPIRY, businessDate, idempotencyKey);
    }

    private ResponseEntity<Map<String, Object>> run(Job job, LocalDate date, String key) {
        return http.document(key, "job." + job.name(), date, null, () -> jobs.run(job, date));
    }
}
