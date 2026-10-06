package io.meridian.deposit.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.deposit.api.generated.TermDepositsApi;
import io.meridian.deposit.api.generated.model.TermDeposit;
import io.meridian.deposit.api.generated.model.TermDepositCreate;
import io.meridian.deposit.api.generated.model.TermDepositEventPage;
import io.meridian.deposit.api.generated.model.TermDepositUpdate;
import io.meridian.deposit.api.generated.model.TermDepositsBreakRequest;
import io.meridian.deposit.api.generated.model.TermDepositsChangeRolloverRequest;
import io.meridian.deposit.api.generated.model.TermDepositsMarkLienRequest;
import io.meridian.deposit.api.generated.model.TermDepositsReleaseLienRequest;
import io.meridian.deposit.api.generated.model.TermDepositsTopUpRequest;
import io.meridian.deposit.api.generated.model.TermDepositsWithdrawRequest;
import io.meridian.deposit.application.TermDepositService;
import io.meridian.deposit.infrastructure.Rows;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Term deposits: placement, funding, top-up, partial withdrawal, break (with quote), rollover, liens. */
@RestController
public class TermDepositsController implements TermDepositsApi {
    private final TermDepositService deposits;
    private final Actions actions;
    private final CrudEngine crud;
    private final Rows rows;

    public TermDepositsController(TermDepositService deposits, Actions actions, CrudEngine crud, Rows rows) {
        this.deposits = deposits;
        this.actions = actions;
        this.crud = crud;
        this.rows = rows;
    }

    @Override
    public ResponseEntity<TermDeposit> termDepositsCreate(String idempotencyKey, TermDepositCreate body) {
        return actions.create(idempotencyKey, body, TermDeposit.class, () -> deposits.create(body));
    }

    @Override
    public ResponseEntity<TermDeposit> termDepositsUpdate(UUID id, String ifMatch, TermDepositUpdate body) {
        return actions.guardedUpdate(crud, "term_deposit", TermDeposit.class, id, ifMatch, body,
                Set.of("currency", "principal_amount", "interest_rate", "day_count", "term_days", "start_date",
                        "maturity_date", "funding_account_id", "payout_account_id",
                        "rollover_instruction", "product_id", "product_version_id", "previous_deposit_id",
                        "premature_penalty_rate", "allow_partial_withdrawal", "interest_payout_mode", "metadata"),
                "use the term deposit actions");
    }

    @Override
    public ResponseEntity<TermDeposit> termDepositsFund(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "fund", null, TermDeposit.class, () -> deposits.fund(id));
    }

    @Override
    public ResponseEntity<TermDeposit> termDepositsTopUp(UUID id, String idempotencyKey, TermDepositsTopUpRequest body) {
        return actions.action(idempotencyKey, id, "top-up", body, TermDeposit.class,
                () -> deposits.topUp(id, Http.decimal(actions.tree(body), "amount"), idempotencyKey));
    }

    @Override
    public ResponseEntity<TermDeposit> termDepositsWithdraw(UUID id, String idempotencyKey, TermDepositsWithdrawRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "withdraw", body, TermDeposit.class,
                () -> deposits.withdraw(id, Http.decimal(req, "amount"), Http.text(req, "reason"), idempotencyKey));
    }

    @Override
    public ResponseEntity<Map<String, Object>> termDepositsBreakQuote(UUID id) {
        TenantContext.require();
        return actions.object(deposits.breakQuote(id));
    }

    @Override
    public ResponseEntity<TermDeposit> termDepositsBreak(UUID id, String idempotencyKey, TermDepositsBreakRequest body) {
        return actions.action(idempotencyKey, id, "break", body, TermDeposit.class,
                () -> deposits.breakDeposit(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<TermDeposit> termDepositsChangeRollover(UUID id, String idempotencyKey,
                                                                 TermDepositsChangeRolloverRequest body) {
        JsonNode req = actions.tree(body);
        String payout = Http.text(req, "payout_account_id");
        return actions.action(idempotencyKey, id, "change-rollover", body, TermDeposit.class,
                () -> deposits.changeRollover(id, Http.text(req, "rollover_instruction"),
                        payout == null ? null : UUID.fromString(payout)));
    }

    @Override
    public ResponseEntity<TermDeposit> termDepositsMarkLien(UUID id, String idempotencyKey, TermDepositsMarkLienRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "mark-lien", body, TermDeposit.class,
                () -> deposits.markLien(id, Http.text(req, "reference"), Http.decimal(req, "amount"), idempotencyKey));
    }

    @Override
    public ResponseEntity<TermDeposit> termDepositsReleaseLien(UUID id, String idempotencyKey,
                                                              TermDepositsReleaseLienRequest body) {
        return actions.action(idempotencyKey, id, "release-lien", body, TermDeposit.class,
                () -> deposits.releaseLien(id, Http.text(actions.tree(body), "reference"), idempotencyKey));
    }

    @Override
    public ResponseEntity<TermDepositEventPage> termDepositsEvents(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        rows.get("term_deposit", id);
        return crud.list("term_deposit_event", TermDepositEventPage.class,
                Crud.params().with("term_deposit_id", id).with("limit", limit).with("cursor", cursor));
    }

    @Override
    public ResponseEntity<String> termDepositsCertificate(UUID id) {
        TenantContext.require();
        return Actions.text(deposits.certificate(id), "term-deposit-certificate-" + id + ".txt");
    }
}
