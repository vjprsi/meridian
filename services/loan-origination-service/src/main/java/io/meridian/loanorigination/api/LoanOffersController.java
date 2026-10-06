package io.meridian.loanorigination.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.loanorigination.api.generated.LoanOffersApi;
import io.meridian.loanorigination.api.generated.model.ESignatureRequest;
import io.meridian.loanorigination.api.generated.model.LoanOffer;
import io.meridian.loanorigination.api.generated.model.LoanOfferCreate;
import io.meridian.loanorigination.api.generated.model.LoanOffersAcceptRequest;
import io.meridian.loanorigination.api.generated.model.LoanOffersDeclineRequest;
import io.meridian.loanorigination.api.generated.model.LoanOffersWithdrawRequest;
import io.meridian.loanorigination.application.OfferService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Priced offers: manual creation, acceptance (starts e-signature), decline, withdrawal and schedule preview. */
@RestController
public class LoanOffersController implements LoanOffersApi {
    private final OfferService offers;
    private final Actions actions;

    public LoanOffersController(OfferService offers, Actions actions) {
        this.offers = offers;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<LoanOffer> loanOffersCreate(String idempotencyKey, LoanOfferCreate body) {
        return actions.create(idempotencyKey, body, LoanOffer.class, () -> offers.create(body));
    }

    @Override
    public ResponseEntity<ESignatureRequest> loanOffersAccept(UUID id, String idempotencyKey, LoanOffersAcceptRequest body) {
        String provider = Http.text(actions.tree(body), "provider");
        return actions.action(idempotencyKey, id, "accept", body, ESignatureRequest.class, () -> offers.accept(id, provider));
    }

    @Override
    public ResponseEntity<LoanOffer> loanOffersDecline(UUID id, String idempotencyKey, LoanOffersDeclineRequest body) {
        String reason = Http.text(actions.tree(body), "reason");
        return actions.action(idempotencyKey, id, "decline", body, LoanOffer.class, () -> offers.decline(id, reason));
    }

    @Override
    public ResponseEntity<LoanOffer> loanOffersWithdraw(UUID id, String idempotencyKey, LoanOffersWithdrawRequest body) {
        String reason = Http.text(actions.tree(body), "reason");
        return actions.action(idempotencyKey, id, "withdraw", body, LoanOffer.class, () -> offers.withdraw(id, reason));
    }

    @Override
    public ResponseEntity<Map<String, Object>> loanOffersSchedulePreview(UUID id) {
        TenantContext.require();
        return actions.object(offers.preview(id));
    }
}
