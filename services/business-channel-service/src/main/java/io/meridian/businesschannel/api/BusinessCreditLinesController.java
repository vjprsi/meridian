package io.meridian.businesschannel.api;

import io.meridian.businesschannel.api.generated.BusinessCreditLinesApi;
import io.meridian.businesschannel.api.generated.model.BusinessCreditLinesDrawRequest;
import io.meridian.businesschannel.api.generated.model.BusinessCreditLinesRepayRequest;
import io.meridian.businesschannel.api.generated.model.CreditLine;
import io.meridian.businesschannel.api.generated.model.CreditLineDrawdown;
import io.meridian.businesschannel.api.generated.model.CreditLineDrawdownPage;
import io.meridian.businesschannel.api.generated.model.CreditLinePage;
import io.meridian.businesschannel.api.generated.model.LoanRepayment;
import io.meridian.businesschannel.application.LendingService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Credit lines of the business. */
@RestController
public class BusinessCreditLinesController implements BusinessCreditLinesApi {
    private final LendingService lending;
    private final Http http;

    public BusinessCreditLinesController(LendingService lending, Http http) {
        this.lending = lending;
        this.http = http;
    }

    @Override
    public ResponseEntity<CreditLinePage> businessCreditLinesList(Integer limit, String cursor) {
        return http.ok(lending.creditLines(limit, cursor), CreditLinePage.class);
    }

    @Override
    public ResponseEntity<CreditLine> businessCreditLinesGet(UUID creditLineId) {
        return http.ok(lending.creditLine(creditLineId), CreditLine.class);
    }

    @Override
    public ResponseEntity<CreditLineDrawdownPage> businessCreditLinesDrawdowns(UUID creditLineId, Integer limit,
                                                                              String cursor) {
        return http.ok(lending.drawdowns(creditLineId, limit, cursor), CreditLineDrawdownPage.class);
    }

    @Override
    public ResponseEntity<CreditLineDrawdown> businessCreditLinesDraw(UUID creditLineId, String idempotencyKey,
                                                                      BusinessCreditLinesDrawRequest body) {
        return http.ok(lending.draw(creditLineId, http.body(body), idempotencyKey), CreditLineDrawdown.class);
    }

    @Override
    public ResponseEntity<LoanRepayment> businessCreditLinesRepay(UUID creditLineId, String idempotencyKey,
                                                                  BusinessCreditLinesRepayRequest body) {
        return http.ok(lending.repayLine(creditLineId, http.body(body), idempotencyKey), LoanRepayment.class);
    }
}
