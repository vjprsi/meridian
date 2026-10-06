package io.meridian.deposit.api;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.deposit.api.generated.AccountRateOverridesApi;
import io.meridian.deposit.api.generated.model.AccountRateOverride;
import io.meridian.deposit.api.generated.model.AccountRateOverrideCreate;
import io.meridian.deposit.application.AccountSupport;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Account rate overrides: validated before generic CRUD (exactly one of absolute rate or adjustment). */
@RestController
public class AccountRateOverridesController implements AccountRateOverridesApi {
    private static final Set<String> KINDS = Set.of("CREDIT_INTEREST", "DEBIT_INTEREST", "OVERDRAFT_INTEREST",
            "UNARRANGED_OVERDRAFT_INTEREST", "PENALTY_INTEREST", "BONUS_INTEREST", "CREDIT", "DEBIT", "OVERDRAFT",
            "UNARRANGED_OVERDRAFT", "PENALTY", "BONUS");

    private final CrudEngine crud;
    private final AccountSupport accounts;
    private final Actions actions;

    public AccountRateOverridesController(CrudEngine crud, AccountSupport accounts, Actions actions) {
        this.crud = crud;
        this.accounts = accounts;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<AccountRateOverride> accountRateOverridesCreate(String idempotencyKey,
                                                                         AccountRateOverrideCreate body) {
        TenantContext.require();
        JsonNode req = actions.tree(body);
        accounts.reference(UUID.fromString(Http.text(req, "deposit_account_id")), "deposit_account_id");
        if (!KINDS.contains(Http.text(req, "rate_kind"))) {
            throw ApiException.unprocessable("invalid_rate_kind", "rate_kind must be an InterestRateKind value");
        }
        BigDecimal rate = Http.decimal(req, "override_rate");
        BigDecimal adjustment = Http.decimal(req, "rate_adjustment");
        if ((rate == null) == (adjustment == null)) {
            throw ApiException.unprocessable("rate_required", "Give exactly one of override_rate or rate_adjustment");
        }
        if (rate != null && (rate.compareTo(new BigDecimal("-0.1")) < 0 || rate.compareTo(BigDecimal.ONE) > 0)) {
            throw ApiException.unprocessable("invalid_rate", "override_rate is an annual fraction between -0.1 and 1");
        }
        String to = Http.text(req, "valid_to");
        if (to != null && LocalDate.parse(to).isBefore(LocalDate.parse(Http.text(req, "valid_from")))) {
            throw ApiException.unprocessable("invalid_validity", "valid_to must not be before valid_from");
        }
        return crud.create("account_rate_override", AccountRateOverride.class, body, idempotencyKey, Crud.params());
    }
}
