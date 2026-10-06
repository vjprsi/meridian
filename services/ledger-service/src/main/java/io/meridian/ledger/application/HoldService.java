package io.meridian.ledger.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.ledger.domain.AccountState;
import io.meridian.ledger.domain.PostingLine;
import io.meridian.ledger.infrastructure.LedgerStore;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Holds reserve funds (card authorisations, pending payments, legal blocks): they reduce the available balance
 * without posting. Capture converts all or part of a hold into a posting; release frees what is left.
 */
@Service
public class HoldService {
    private final LedgerStore store;
    private final CrudEngine crud;
    private final PostingService posting;
    private final OutboxWriter outbox;
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public HoldService(LedgerStore store, CrudEngine crud, PostingService posting, OutboxWriter outbox,
                       JdbcClient jdbc, JsonMapper json) {
        this.store = store;
        this.crud = crud;
        this.posting = posting;
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.json = json;
    }

    private record HoldRow(UUID id, UUID accountId, BigDecimal amount, BigDecimal captured, String status, String currency) {
        BigDecimal remaining() {
            return amount.subtract(captured);
        }
    }

    @Transactional
    public String place(Object body, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw ApiException.badRequest("idempotency_key_required", "Idempotency-Key header is required");
        }
        var replay = jdbc.sql("select row_to_json(h)::text from hold h where idempotency_key = ?")
                .param(idempotencyKey).query(String.class).optional();
        if (replay.isPresent()) {
            return replay.get();
        }
        ObjectNode values = crud.toColumns("hold", body);
        UUID accountId = UUID.fromString(values.get("ledger_account_id").asString());
        BigDecimal amount = new BigDecimal(values.get("amount").asString());
        if (amount.signum() <= 0) {
            throw ApiException.unprocessable("invalid_amount", "Hold amount must be positive");
        }
        AccountState account = store.lockAccounts(List.of(accountId)).get(accountId);
        if (!account.currency.equals(values.get("currency").asString())) {
            throw ApiException.unprocessable("currency_mismatch", "Hold currency must equal the account currency " + account.currency);
        }
        account.reserve(amount);
        store.saveBalance(account);
        values.put("id", Ids.newId().toString());
        values.put("idempotency_key", idempotencyKey);
        values.put("status", "ACTIVE");
        String row = crud.insert("hold", values);
        UUID holdId = UUID.fromString(values.get("id").asString());
        event(holdId, "PLACED", amount, null, null);
        outbox.publish("ledger.hold.placed", "hold", holdId, json.readTree(row));
        return row;
    }

    @Transactional
    public String increment(UUID holdId, BigDecimal amount, String reason) {
        HoldRow h = lock(holdId);
        requireOpen(h);
        if (amount == null || amount.signum() <= 0) {
            throw ApiException.unprocessable("invalid_amount", "Increment must be positive");
        }
        AccountState account = store.lockAccounts(List.of(h.accountId())).get(h.accountId());
        account.reserve(amount);
        store.saveBalance(account);
        jdbc.sql("update hold set amount = amount + ? where id = ?").params(amount, holdId).update();
        event(holdId, "INCREMENTED", amount, null, reason);
        return row(holdId);
    }

    @Transactional
    public String capture(UUID holdId, BigDecimal amount, UUID counterAccountId, String description,
                          boolean releaseRemainder, String idempotencyKey) {
        HoldRow h = lock(holdId);
        AccountState account = store.lockAccounts(List.of(h.accountId())).get(h.accountId());
        String postingKey = ("hc:" + holdId + ":" + idempotencyKey);
        postingKey = postingKey.substring(0, Math.min(128, postingKey.length()));
        if (store.journalEntryByKey(account.ledgerId, postingKey).isPresent()) {
            return row(holdId); // replay of a capture that already succeeded
        }
        requireOpen(h);
        if (amount == null || amount.signum() <= 0 || amount.compareTo(h.remaining()) > 0) {
            throw ApiException.unprocessable("invalid_capture_amount",
                    "Capture amount must be positive and at most the remaining " + h.remaining().toPlainString());
        }
        BigDecimal remainingAfter = h.remaining().subtract(amount);
        account.unreserve(releaseRemainder ? h.remaining() : amount);
        store.saveBalance(account);

        PostingLine.Direction out = account.outgoingDirection();
        PostingLine.Direction in = out == PostingLine.Direction.DEBIT ? PostingLine.Direction.CREDIT : PostingLine.Direction.DEBIT;
        PostingService.Posted posted = posting.post(new PostingService.Posting(
                account.ledgerId, postingKey, "STANDARD",
                description == null ? "Capture of hold " + holdId : description, null, null,
                TenantContext.principal(), "HOLD", holdId, null, null, null, null,
                List.of(new PostingLine(h.accountId(), out, amount, h.currency(), null, description),
                        new PostingLine(counterAccountId, in, amount, h.currency(), null, description))));
        UUID journalEntryId = UUID.fromString(json.readTree(posted.json()).get("id").asString());
        if (posted.replayed()) {
            return row(holdId);
        }
        boolean done = releaseRemainder || remainingAfter.signum() == 0;
        jdbc.sql("""
                update hold set captured_amount = captured_amount + ?, status = ?,
                       released_at = case when ? then now() else released_at end
                where id = ?""")
                .params(amount, done ? "CAPTURED" : "PARTIALLY_CAPTURED", done && remainingAfter.signum() > 0, holdId)
                .update();
        event(holdId, done ? "CAPTURED" : "PARTIALLY_CAPTURED", amount, journalEntryId, description);
        String row = row(holdId);
        outbox.publish("ledger.hold.captured", "hold", holdId, json.readTree(row));
        return row;
    }

    @Transactional
    public String release(UUID holdId, String reason) {
        return close(holdId, "RELEASED", reason);
    }

    /** Releases a hold whose expires_at has passed (end-of-day processing). */
    @Transactional
    public String expire(UUID holdId, String reason) {
        return close(holdId, "EXPIRED", reason);
    }

    private String close(UUID holdId, String finalStatus, String reason) {
        HoldRow h = lock(holdId);
        requireOpen(h);
        AccountState account = store.lockAccounts(List.of(h.accountId())).get(h.accountId());
        account.unreserve(h.remaining());
        store.saveBalance(account);
        jdbc.sql("update hold set status = ?, released_at = now() where id = ?").params(finalStatus, holdId).update();
        event(holdId, finalStatus, h.remaining(), null, reason);
        String row = row(holdId);
        outbox.publish("ledger.hold.released", "hold", holdId, json.readTree(row));
        return row;
    }

    private HoldRow lock(UUID id) {
        return jdbc.sql("""
                select id, ledger_account_id, amount, captured_amount, status, currency from hold where id = ? for update""")
                .param(id)
                .query((rs, i) -> new HoldRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getString(5), rs.getString(6).trim()))
                .optional()
                .orElseThrow(() -> ApiException.notFound("hold", id));
    }

    private static void requireOpen(HoldRow h) {
        if (!"ACTIVE".equals(h.status()) && !"PARTIALLY_CAPTURED".equals(h.status())) {
            throw ApiException.conflict("hold_closed", "Hold " + h.id() + " is " + h.status());
        }
    }

    private String row(UUID holdId) {
        return jdbc.sql("select row_to_json(h)::text from hold h where id = ?").param(holdId).query(String.class).single();
    }

    private void event(UUID holdId, String type, BigDecimal amount, UUID journalEntryId, String reason) {
        ObjectNode e = json.createObjectNode();
        e.put("hold_id", holdId.toString());
        e.put("event_type", type);
        e.put("amount", amount.toPlainString());
        if (journalEntryId != null) {
            e.put("journal_entry_id", journalEntryId.toString());
        }
        if (reason != null) {
            e.put("reason", reason);
        }
        crud.insert("hold_event", e);
    }

    JsonNode tree(String s) {
        return json.readTree(s);
    }
}
