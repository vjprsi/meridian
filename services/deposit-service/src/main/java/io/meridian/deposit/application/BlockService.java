package io.meridian.deposit.application;

import static io.meridian.deposit.infrastructure.Rows.dec;
import static io.meridian.deposit.infrastructure.Rows.str;
import static io.meridian.deposit.infrastructure.Rows.ts;
import static io.meridian.deposit.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.deposit.domain.Currencies;
import io.meridian.deposit.domain.Lifecycle;
import io.meridian.deposit.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

/**
 * Account blocks (legal orders, garnishments, AML, liens). AMOUNT blocks reserve funds with a ledger hold;
 * DEBIT/CREDIT/BOTH blocks restrict the account (BLOCKED_DEBIT / BLOCKED_CREDIT / BLOCKED), which the ledger
 * enforces through the ledger account's freeze status.
 */
@Service
public class BlockService {
    private static final Set<String> LEGAL = Set.of("LEGAL", "GARNISHMENT", "COURT_ORDER", "TAX_LIEN");

    private final Rows rows;
    private final AccountSupport accounts;
    private final LedgerPort ledger;
    private final Events events;

    public BlockService(Rows rows, AccountSupport accounts, LedgerPort ledger, Events events) {
        this.rows = rows;
        this.accounts = accounts;
        this.ledger = ledger;
        this.events = events;
    }

    @Transactional
    public ObjectNode place(Object body, String clientKey) {
        ObjectNode v = rows.columns("account_block", body);
        UUID accountId = uuid(v, "deposit_account_id");
        accounts.reference(accountId, "deposit_account_id");
        ObjectNode acc = accounts.lock(accountId);
        accounts.requireNotClosed(acc);
        String type = str(v, "block_type");
        String scope = str(v, "scope");
        if (LEGAL.contains(type) && str(v, "reference") == null) {
            throw ApiException.unprocessable("reference_required", type + " blocks need the court/authority reference");
        }
        OffsetDateTime starts = ts(v, "starts_at");
        OffsetDateTime expires = ts(v, "expires_at");
        if (expires != null && !expires.isAfter(starts == null ? accounts.now() : starts)) {
            throw ApiException.unprocessable("invalid_expiry", "expires_at must be after starts_at");
        }
        v.remove("hold_id");
        v.put("status", "ACTIVE");
        UUID blockId = io.meridian.common.id.Ids.newId();
        v.put("id", blockId.toString());
        if ("AMOUNT".equals(scope)) {
            BigDecimal amount = dec(v, "amount");
            String ccy = str(v, "currency") == null ? str(acc, "currency") : str(v, "currency");
            if (amount == null || amount.signum() <= 0) {
                throw ApiException.unprocessable("amount_required", "AMOUNT blocks need a positive amount");
            }
            accounts.requireCurrency(acc, ccy);
            if (!Currencies.fitsMinorUnit(amount, ccy)) {
                throw ApiException.unprocessable("invalid_amount", "amount has more decimals than " + ccy + " allows");
            }
            UUID hold = ledger.placeHold(LedgerPort.key("block-hold", accountId, clientKey), accounts.requireLedger(acc),
                    amount, ccy, "LEGAL", type + " " + (str(v, "reference") == null ? "" : str(v, "reference")),
                    "ACCOUNT_BLOCK", blockId);
            v.put("currency", ccy);
            v.put("hold_id", hold.toString());
        } else if (v.hasNonNull("amount")) {
            throw ApiException.unprocessable("amount_not_allowed", "amount applies only to AMOUNT blocks");
        }
        ObjectNode block = rows.insert("account_block", v);
        events.publish("accounts.account_block.placed", "account_block", block);
        if (!"AMOUNT".equals(scope)) {
            accounts.recomputeBlocks(acc, "BLOCK_PLACED", type + ": " + str(v, "reason"));
        }
        return block;
    }

    @Transactional
    public ObjectNode lift(UUID blockId, String reason, String clientKey) {
        ObjectNode block = rows.lock("account_block", blockId);
        Lifecycle.ACCOUNT_BLOCK.require(str(block, "status"), "LIFTED");
        ObjectNode acc = accounts.lock(uuid(block, "deposit_account_id"));
        if (uuid(block, "hold_id") != null) {
            ledger.releaseHold(uuid(block, "hold_id"), reason, LedgerPort.key("block-release", blockId, clientKey));
        }
        ObjectNode v = rows.object();
        v.put("status", "LIFTED");
        v.put("lifted_at", accounts.now().toString());
        v.put("lifted_reason", reason);
        ObjectNode lifted = rows.update("account_block", blockId, v);
        events.publish("accounts.account_block.lifted", "account_block", lifted);
        if (!"AMOUNT".equals(str(block, "scope"))) {
            accounts.recomputeBlocks(acc, "BLOCK_LIFTED", reason);
        }
        return lifted;
    }
}
