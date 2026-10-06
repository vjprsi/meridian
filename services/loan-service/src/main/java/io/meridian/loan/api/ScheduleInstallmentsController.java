package io.meridian.loan.api;

import io.meridian.loan.api.generated.ScheduleInstallmentsApi;
import io.meridian.loan.api.generated.model.ScheduleInstallment;
import io.meridian.loan.api.generated.model.ScheduleInstallmentsWaiveRequest;
import io.meridian.loan.application.RepaymentService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Instalments (list/get via generic CRUD) and waivers of their unpaid remainder. */
@RestController
public class ScheduleInstallmentsController implements ScheduleInstallmentsApi {
    private final RepaymentService repayments;
    private final Actions actions;

    public ScheduleInstallmentsController(RepaymentService repayments, Actions actions) {
        this.repayments = repayments;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<ScheduleInstallment> scheduleInstallmentsWaive(UUID id, String idempotencyKey,
                                                                         ScheduleInstallmentsWaiveRequest body) {
        JsonNode req = actions.tree(body);
        List<String> components = new ArrayList<>();
        req.path("components").forEach(c -> components.add(c.asString()));
        return actions.action(idempotencyKey, id, "waive", body, ScheduleInstallment.class,
                () -> repayments.waiveInstallment(id, Http.text(req, "reason"), components));
    }
}
