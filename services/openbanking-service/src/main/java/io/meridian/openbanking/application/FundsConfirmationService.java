package io.meridian.openbanking.application;

import static io.meridian.openbanking.infrastructure.Rows.dec;
import static io.meridian.openbanking.infrastructure.Rows.str;
import static io.meridian.openbanking.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.openbanking.infrastructure.CoreBanking;
import io.meridian.openbanking.infrastructure.Rows;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * CBPII confirmation of funds: a yes/no answer from the ledger available balance of the account the customer
 * consented to, recorded append-only. The balance itself is never disclosed to the TPP.
 */
@Service
public class FundsConfirmationService {
    static final String TABLE = "ob_funds_confirmation";

    private final Rows rows;
    private final Events events;
    private final CoreBanking core;
    private final ConsentService consents;

    public FundsConfirmationService(Rows rows, Events events, CoreBanking core, ConsentService consents) {
        this.rows = rows;
        this.events = events;
        this.core = core;
        this.consents = consents;
    }

    @Transactional
    public ObjectNode confirm(Object body) {
        ObjectNode v = rows.columns(TABLE, body);
        UUID consentId = uuid(v, "ob_consent_id");
        consents.usableConsent(consentId, uuid(v, "tpp_id"), Set.of("CBPII"));
        UUID accountId = uuid(v, "account_id");
        boolean covered = rows.scalar("select count(*) from ob_consent_account where ob_consent_id = ? and account_id = ?",
                Long.class, consentId, accountId).orElse(0L) > 0;
        if (!covered) {
            throw new ApiException(403, "account_not_in_consent", "The account is not covered by the consent");
        }
        BigDecimal amount = dec(v, "amount");
        if (amount == null || amount.signum() <= 0) {
            throw ApiException.unprocessable("invalid_amount", "amount must be positive");
        }
        JsonNode account = core.depositAccount(accountId);
        if (!str(v, "currency").equals(account.path("currency").asString(null))) {
            throw ApiException.unprocessable("currency_mismatch", "The account is held in " + account.path("currency").asString(""));
        }
        boolean available = "ACTIVE".equals(account.path("status").asString(null))
                && core.availableBalance(account).compareTo(amount) >= 0;
        v.put("funds_available", available);
        ObjectNode row = rows.insert(TABLE, v);
        events.publish("openbanking.ob_funds_confirmation.checked", TABLE, row);
        return row;
    }
}
