package io.meridian.merchantchannel.application;

import static io.meridian.merchantchannel.application.CurrentUser.merchant;
import static io.meridian.merchantchannel.application.CurrentUser.userId;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.FourEyes;
import io.meridian.common.time.Clocks;
import io.meridian.events.OutboxWriter;
import io.meridian.merchantchannel.application.Downstream.Query;
import io.meridian.merchantchannel.config.MerchantChannelProperties;
import io.meridian.web.security.MerchantContext.MerchantPrincipal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The merchant's payout (settlement) bank account. Changing it is the most sensitive thing a merchant user can do
 * (it redirects the merchant's money), so: only an OWNER may request a change, with a recent authentication; the new
 * account must be an ACTIVE deposit account of the merchant's own business customer ({@code merchant.customer_id}) in
 * the merchant's currency (anything else answers 404 like a missing account); one change may be pending at a time; a
 * different user with a payout approver role ({@code meridian.merchant.payout-approver-roles}) approves it, again with
 * step-up; eligibility is checked again at approval, and the change is applied to merchant-service (PATCH with the
 * merchant's row version) as the approver.
 */
@Service
public class PayoutService {
    static final String TABLE = "merchant_change_request";
    static final String MERCHANTS = Access.MERCHANTS;
    static final String DEPOSITS = "deposit-service";

    private final Access access;
    private final Rows rows;
    private final Downstream down;
    private final StepUp stepUp;
    private final ActionAudit audit;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final MerchantChannelProperties props;

    public PayoutService(Access access, Rows rows, Downstream down, StepUp stepUp, ActionAudit audit, OutboxWriter outbox,
                         TransactionTemplate tx, MerchantChannelProperties props) {
        this.access = access;
        this.rows = rows;
        this.down = down;
        this.stepUp = stepUp;
        this.audit = audit;
        this.outbox = outbox;
        this.tx = tx;
        this.props = props;
    }

    // ------------------------------------------------------------------ reads

    /** {settlement_account, settlement_frequency, pending_change}. */
    public JsonNode account() {
        access.requireRole("merchantAppPayoutAccountGet", "ACCOUNT", null, "OWNER", "MANAGER");
        JsonNode m = access.merchantRecord();
        ObjectNode out = rows.object();
        UUID accountId = Req.rowUuid(m, "settlement_account_id");
        if (accountId == null) {
            out.putNull("settlement_account");
        } else {
            ObjectNode acc = out.putObject("settlement_account").put("id", accountId.toString());
            try {
                JsonNode a = down.get(DEPOSITS, "/v1/deposit-accounts/" + accountId);
                acc.put("account_number_masked", mask(a.path("account_number").asString(null)));
                acc.put("currency", a.path("currency").asString(null));
                acc.put("status", a.path("status").asString(null));
            } catch (ApiException e) {
                if (e.status() != 404) {
                    throw e;
                }
            }
        }
        out.put("settlement_frequency", m.path("settlement_frequency").asString(null));
        rows.one("select row_to_json(t)::text from merchant_change_request t where t.merchant_id = ? "
                + "and t.status = 'PENDING_APPROVAL' order by t.id desc limit 1", merchant())
                .ifPresentOrElse(c -> out.set("pending_change", c), () -> out.putNull("pending_change"));
        return out;
    }

    public JsonNode eligibleAccounts() {
        access.requireOwner("merchantAppPayoutAccountEligibleAccounts", "ACCOUNT", null);
        JsonNode m = access.merchantRecord();
        String customer = m.path("customer_id").asString(null);
        List<JsonNode> out = new ArrayList<>();
        if (customer != null) {
            for (JsonNode a : down.all(DEPOSITS, "/v1/deposit-accounts", Query.of("customer_id", customer), 1000)) {
                if (eligible(a, m)) {
                    out.add(a);
                }
            }
        }
        return down.page(out);
    }

    public JsonNode list(String status, Integer limit, String cursor) {
        access.requireRole("merchantAppPayoutChangesList", "PAYOUT_CHANGE", null, "OWNER", "MANAGER");
        int n = Rows.limit(limit);
        UUID after = Rows.cursor(cursor);
        return rows.page(rows.list("select row_to_json(t)::text from merchant_change_request t where t.merchant_id = ? "
                        + "and (?::text is null or t.status = ?::text) and (?::uuid is null or t.id < ?::uuid) "
                        + "order by t.id desc limit ?", merchant(), status, status, after, after, n + 1), n);
    }

    public JsonNode get(UUID id) {
        String action = "merchantAppPayoutChangesGet";
        ObjectNode c = change(id, action);
        access.requireRole(action, "PAYOUT_CHANGE", id, "OWNER", "MANAGER");
        return c;
    }

    // ------------------------------------------------------------------ changes

    public JsonNode request(JsonNode req) {
        String action = "merchantAppPayoutChangesCreate";
        access.requireOwner(action, "PAYOUT_CHANGE", null);
        UUID accountId = Req.uuid(req, "settlement_account_id", true);
        JsonNode m = access.merchantRecord();
        JsonNode account = eligibleAccount(accountId, m, action);
        if (accountId.toString().equals(m.path("settlement_account_id").asString(null))) {
            throw ApiException.unprocessable("no_change", "This is already the payout account");
        }
        String reason = Req.text(req, "reason");
        stepUp.require(action, "PAYOUT_CHANGE", null);
        ObjectNode saved = tx.execute(s -> {
            rows.lockKey("merchant-payout:" + merchant());
            long open = rows.scalar("select count(*) from merchant_change_request where merchant_id = ? "
                    + "and status = 'PENDING_APPROVAL'", Long.class, merchant()).orElse(0L);
            if (open > 0) {
                throw ApiException.conflict("change_pending", "A payout account change is already waiting for approval");
            }
            ObjectNode v = rows.object()
                    .put("merchant_id", merchant().toString())
                    .put("change_type", "PAYOUT_ACCOUNT")
                    .put("settlement_account_id", accountId.toString())
                    .put("account_number_masked", mask(account.path("account_number").asString(null)))
                    .put("previous_settlement_account_id", m.path("settlement_account_id").asString(null))
                    .put("reason", reason == null ? null : reason.length() > 255 ? reason.substring(0, 255) : reason)
                    .put("status", "PENDING_APPROVAL")
                    .put("requested_by", userId().toString());
            ObjectNode r = rows.insert(TABLE, v);
            outbox.publish("channels.merchant_change_request.requested", TABLE, UUID.fromString(r.path("id").asString()), r);
            return r;
        });
        audit.succeeded(action, "PAYOUT_CHANGE", null, saved.path("id").asString());
        return saved;
    }

    public JsonNode approve(UUID id, JsonNode req) {
        String action = "merchantAppPayoutChangesApprove";
        ObjectNode c = change(id, action);
        approverRole(action, id);
        pending(c);
        notRequester(c, action, id);
        stepUp.require(action, "PAYOUT_CHANGE", id);
        String comment = Req.text(req, "comment");
        UUID approver = userId();
        JsonNode m = access.merchantRecord();
        UUID accountId = Req.rowUuid(c, "settlement_account_id");
        try {
            eligibleAccount(accountId, m, action);
        } catch (ApiException e) {
            return failed(id, approver, comment, e, action);
        }
        tx.executeWithoutResult(s -> {
            ObjectNode locked = rows.lock(TABLE, merchant(), id).orElseThrow(() -> Access.notFound("PAYOUT_CHANGE", id));
            pending(locked);
        });
        try {
            ObjectNode patch = rows.object().put("settlement_account_id", accountId.toString());
            Downstream.as(approver, () -> down.patch(MERCHANTS, "/v1/merchants/" + merchant(), patch,
                    m.path("row_version").asInt(1)));
        } catch (ApiException e) {
            if (Downstream.rejected(e)) {
                return failed(id, approver, comment, e, action);
            }
            throw e;
        }
        ObjectNode after = tx.execute(s -> {
            rows.lock(TABLE, merchant(), id);
            ObjectNode a = rows.update(TABLE, id, rows.object().put("status", "APPLIED")
                    .put("previous_settlement_account_id", m.path("settlement_account_id").asString(null))
                    .put("decided_by", approver.toString()).put("decided_at", Clocks.now().toString())
                    .put("decision_comment", comment));
            outbox.publish("channels.merchant_change_request.applied", TABLE, id, a);
            return a;
        });
        audit.succeeded(action, "PAYOUT_CHANGE", id, accountId);
        return after;
    }

    public JsonNode reject(UUID id, JsonNode req) {
        String action = "merchantAppPayoutChangesReject";
        ObjectNode c = change(id, action);
        approverRole(action, id);
        pending(c);
        notRequester(c, action, id);
        String reason = Req.required(req, "reason");
        ObjectNode after = tx.execute(s -> {
            ObjectNode locked = rows.lock(TABLE, merchant(), id).orElseThrow(() -> Access.notFound("PAYOUT_CHANGE", id));
            pending(locked);
            ObjectNode a = rows.update(TABLE, id, rows.object().put("status", "REJECTED")
                    .put("decided_by", userId().toString()).put("decided_at", Clocks.now().toString())
                    .put("decision_comment", reason.length() > 500 ? reason.substring(0, 500) : reason));
            outbox.publish("channels.merchant_change_request.rejected", TABLE, id, a);
            return a;
        });
        audit.succeeded(action, "PAYOUT_CHANGE", id, null);
        return after;
    }

    public JsonNode cancel(UUID id) {
        String action = "merchantAppPayoutChangesCancel";
        ObjectNode c = change(id, action);
        if (!userId().toString().equals(c.path("requested_by").asString(null))) {
            audit.denied(action, "PAYOUT_CHANGE", id, "not_requester");
            throw new ApiException(403, "not_requester", "Only the requester can withdraw a payout account change");
        }
        pending(c);
        ObjectNode after = tx.execute(s -> {
            ObjectNode locked = rows.lock(TABLE, merchant(), id).orElseThrow(() -> Access.notFound("PAYOUT_CHANGE", id));
            pending(locked);
            return rows.update(TABLE, id, rows.object().put("status", "CANCELLED")
                    .put("decided_by", userId().toString()).put("decided_at", Clocks.now().toString()));
        });
        audit.succeeded(action, "PAYOUT_CHANGE", id, null);
        return after;
    }

    // ------------------------------------------------------------------ helpers

    ObjectNode change(UUID id, String action) {
        return rows.find(TABLE, merchant(), id).orElseThrow(() -> access.deny(action, "PAYOUT_CHANGE", id));
    }

    private void approverRole(String action, UUID id) {
        access.requireRole(action, "PAYOUT_CHANGE", id, props.payoutApproverRoles().toArray(String[]::new));
    }

    private static void pending(ObjectNode c) {
        if (!"PENDING_APPROVAL".equals(c.path("status").asString())) {
            throw ApiException.conflict("invalid_transition", "The payout account change is " + c.path("status").asString());
        }
    }

    private void notRequester(ObjectNode c, String action, UUID id) {
        String requester = c.path("requested_by").asString(null);
        if (userId().toString().equals(requester)) {
            audit.denied(action, "PAYOUT_CHANGE", id, "four_eyes_violation");
        }
        FourEyes.require(requester == null ? null : MerchantPrincipal.principalName(UUID.fromString(requester)),
                MerchantPrincipal.principalName(userId()), "A payout account change must be decided by another user");
    }

    /** An account the merchant may be paid to; any other (or missing) account answers 404 (audited). */
    private JsonNode eligibleAccount(UUID accountId, JsonNode m, String action) {
        String customer = m.path("customer_id").asString(null);
        JsonNode a = access.fetch(DEPOSITS, "/v1/deposit-accounts/" + accountId, "ACCOUNT", accountId);
        if (customer == null || !customer.equals(a.path("customer_id").asString(null))) {
            throw access.deny(action, "ACCOUNT", accountId);
        }
        if (!"ACTIVE".equals(a.path("status").asString(""))) {
            throw ApiException.unprocessable("account_not_active", "The account is " + a.path("status").asString(""));
        }
        if (!m.path("currency").asString("").equals(a.path("currency").asString(""))) {
            throw ApiException.unprocessable("currency_mismatch", "Payouts are in " + m.path("currency").asString(""));
        }
        return a;
    }

    private static boolean eligible(JsonNode a, JsonNode m) {
        return m.path("customer_id").asString("-").equals(a.path("customer_id").asString(null))
                && "ACTIVE".equals(a.path("status").asString(""))
                && m.path("currency").asString("").equals(a.path("currency").asString(""));
    }

    static String mask(String number) {
        if (number == null || number.isBlank()) {
            return null;
        }
        String n = number.trim();
        return n.length() <= 4 ? "****" : "****" + n.substring(n.length() - 4);
    }

    private ObjectNode failed(UUID id, UUID approver, String comment, ApiException e, String action) {
        String msg = e.code() + ": " + e.getMessage();
        ObjectNode after = tx.execute(s -> {
            rows.lock(TABLE, merchant(), id);
            return rows.update(TABLE, id, rows.object().put("status", "FAILED")
                    .put("decided_by", approver.toString()).put("decided_at", Clocks.now().toString())
                    .put("decision_comment", comment)
                    .put("failure_reason", msg.length() > 255 ? msg.substring(0, 255) : msg));
        });
        audit.failed(action, "PAYOUT_CHANGE", id, e.code());
        return after;
    }
}
