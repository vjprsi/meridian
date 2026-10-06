package io.meridian.deposit.api;

import io.meridian.deposit.api.generated.DepositEodRunsApi;
import org.springframework.web.bind.annotation.RestController;

/**
 * End-of-day run ledger (read-only): one row per job and business date, written by
 * {@link io.meridian.deposit.application.EndOfDayJobs}. List and get are served by the generic CRUD defaults.
 */
@RestController
public class DepositEodRunsController implements DepositEodRunsApi {
}
