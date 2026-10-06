package io.meridian.rewards.api;

import io.meridian.rewards.api.generated.RewardsEodRunsApi;
import io.meridian.rewards.application.EndOfDayJobs;
import io.meridian.rewards.application.EndOfDayJobs.Job;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** End-of-day jobs called by business-day-service; idempotent per business date. */
@RestController
public class RewardsEodRunsController implements RewardsEodRunsApi {
    private final EndOfDayJobs jobs;
    private final Http http;

    public RewardsEodRunsController(EndOfDayJobs jobs, Http http) {
        this.jobs = jobs;
        this.http = http;
    }

    private ResponseEntity<Map<String, Object>> run(Job job, LocalDate date, String key) {
        return http.document(key, "eod." + job, date, null, () -> jobs.run(job, date));
    }

    @Override
    public ResponseEntity<Map<String, Object>> rewardsEodRunsPointsConfirmation(LocalDate businessDate, String idempotencyKey) {
        return run(Job.POINTS_CONFIRMATION, businessDate, idempotencyKey);
    }

    @Override
    public ResponseEntity<Map<String, Object>> rewardsEodRunsPointsExpiry(LocalDate businessDate, String idempotencyKey) {
        return run(Job.POINTS_EXPIRY, businessDate, idempotencyKey);
    }

    @Override
    public ResponseEntity<Map<String, Object>> rewardsEodRunsCashbackPayout(LocalDate businessDate, String idempotencyKey) {
        return run(Job.CASHBACK_PAYOUT, businessDate, idempotencyKey);
    }

    @Override
    public ResponseEntity<Map<String, Object>> rewardsEodRunsTierRequalification(LocalDate businessDate, String idempotencyKey) {
        return run(Job.TIER_REQUALIFICATION, businessDate, idempotencyKey);
    }

    @Override
    public ResponseEntity<Map<String, Object>> rewardsEodRunsReferralExpiry(LocalDate businessDate, String idempotencyKey) {
        return run(Job.REFERRAL_EXPIRY, businessDate, idempotencyKey);
    }
}
