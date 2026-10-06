package io.meridian.insights.api;

import io.meridian.insights.api.generated.InsightsJobRunsApi;
import io.meridian.insights.application.JobRuns;
import io.meridian.insights.application.JobRuns.Job;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Batch jobs per business date (idempotent per tenant, job and date). */
@RestController
public class InsightsJobRunsController implements InsightsJobRunsApi {
    private final JobRuns jobs;
    private final Http http;

    public InsightsJobRunsController(JobRuns jobs, Http http) {
        this.jobs = jobs;
        this.http = http;
    }

    private ResponseEntity<Map<String, Object>> run(String key, Job job, LocalDate date) {
        return http.document(key, "job." + job, date, null, () -> jobs.run(job, date));
    }

    @Override
    public ResponseEntity<Map<String, Object>> insightsJobRunsRecurringDetection(LocalDate businessDate, String idempotencyKey) {
        return run(idempotencyKey, Job.RECURRING_DETECTION, businessDate);
    }

    @Override
    public ResponseEntity<Map<String, Object>> insightsJobRunsInsightGeneration(LocalDate businessDate, String idempotencyKey) {
        return run(idempotencyKey, Job.INSIGHT_GENERATION, businessDate);
    }

    @Override
    public ResponseEntity<Map<String, Object>> insightsJobRunsBudgetRollover(LocalDate businessDate, String idempotencyKey) {
        return run(idempotencyKey, Job.BUDGET_ROLLOVER, businessDate);
    }

    @Override
    public ResponseEntity<Map<String, Object>> insightsJobRunsInsightExpiry(LocalDate businessDate, String idempotencyKey) {
        return run(idempotencyKey, Job.INSIGHT_EXPIRY, businessDate);
    }
}
