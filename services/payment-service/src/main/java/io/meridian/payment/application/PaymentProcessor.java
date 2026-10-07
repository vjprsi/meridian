package io.meridian.payment.application;

import static io.meridian.payment.infrastructure.Rows.bool;
import static io.meridian.payment.infrastructure.Rows.date;
import static io.meridian.payment.infrastructure.Rows.dec;
import static io.meridian.payment.infrastructure.Rows.decOr0;
import static io.meridian.payment.infrastructure.Rows.str;
import static io.meridian.payment.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.payment.config.PaymentProperties;
import io.meridian.payment.domain.ApprovalMatrix;
import io.meridian.payment.domain.Bic;
import io.meridian.payment.domain.Iban;
import io.meridian.payment.domain.PaymentLifecycle;
import io.meridian.payment.domain.PaymentLifecycle.Reason;
import io.meridian.payment.infrastructure.CoreBankingClient;
import io.meridian.payment.infrastructure.LedgerClient;
import io.meridian.payment.infrastructure.RiskClient;
import io.meridian.payment.infrastructure.RoutingClient;
import io.meridian.payment.infrastructure.Rows;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Payment orchestration (a synchronous saga with persisted steps): validate and enrich, route (cut-off and execution
 * date), sanctions screening, limits reservation, business approvals, funds reservation as a ledger hold, FX deal,
 * submission to the rail (pacs.008) or immediate book transfer, settlement by capturing the hold into a journal
 * entry. Each step commits its own state change with status history and events; remote calls carry deterministic
 * idempotency keys so any step can be retried. Repairable enrichment failures open a repair item.
 */
@Service
public class PaymentProcessor {
    private static final Pattern ISO_LATIN = Pattern.compile("[A-Za-z0-9/\\-?:().,'+ ]*");
    private static final Set<String> REJECTABLE_FUNDS_CODES = Set.of("insufficient_funds", "account_frozen",
            "account_closed", "invalid_amount", "currency_mismatch");

    /** Options for payments created by other flows (bulk, direct debit, request-to-pay, links, QR, refunds). */
    public record Options(boolean skipApproval, UUID sharedHoldId, String endToEndId, boolean skipScreening) {
        public static final Options DEFAULT = new Options(false, null, null, false);
    }

    private final Rows rows;
    private final PaymentRecords records;
    private final Events events;
    private final LedgerClient ledger;
    private final CoreBankingClient core;
    private final RoutingClient routing;
    private final RiskClient risk;
    private final PaymentProperties props;
    private final PaymentHooks hooks;
    private final TransactionTemplate tx;
    private final ApprovalMatrix matrix;
    private final PaymentApprovalRequests central;

    public PaymentProcessor(Rows rows, PaymentRecords records, Events events, LedgerClient ledger,
                            CoreBankingClient core, RoutingClient routing, RiskClient risk, PaymentProperties props,
                            PaymentHooks hooks, TransactionTemplate tx, PaymentApprovalRequests central) {
        this.central = central;
        this.rows = rows;
        this.records = records;
        this.events = events;
        this.ledger = ledger;
        this.core = core;
        this.routing = routing;
        this.risk = risk;
        this.props = props;
        this.hooks = hooks;
        this.tx = tx;
        this.matrix = new ApprovalMatrix(props.approval().levels().stream()
                .map(l -> new ApprovalMatrix.Level(l.minAmount(), l.approvals() == null ? 1 : l.approvals())).toList());
    }

    // ================================================================== initiation

    /**
     * Validates, persists (INITIATED) and processes a payment as far as it can go. A replay with the same
     * idempotency key resumes and returns the existing payment.
     */
    public ObjectNode initiate(ObjectNode request, String idempotencyKey, Options options) {
        return initiate(request, idempotencyKey, options, null);
    }

    /**
     * Like {@link #initiate(ObjectNode, String, Options)}; {@code beforeInsert} runs in the transaction that stores the
     * payment, right before the insert (e.g. to lock the original payment and re-check a cumulative amount, so the
     * check and the new row commit together).
     */
    public ObjectNode initiate(ObjectNode request, String idempotencyKey, Options options, Runnable beforeInsert) {
        TenantContext.require();
        Optional<ObjectNode> existing = rows.queryOne(
                "select row_to_json(t)::text from payment t where idempotency_key = ?", idempotencyKey);
        if (existing.isPresent()) {
            return resume(UUID.fromString(str(existing.get(), "id")), options);
        }
        ObjectNode v = prepare(request, options);
        v.put("idempotency_key", idempotencyKey);
        ObjectNode created = inTx(() -> {
            if (beforeInsert != null) {
                beforeInsert.run();
            }
            ObjectNode p = rows.insert(PaymentRecords.TABLE, v);
            UUID id = UUID.fromString(str(p, "id"));
            records.history(id, null, "INITIATED", null, "Payment initiated via " + str(p, "channel"), null);
            events.publish("payments.payment.initiated", PaymentRecords.TABLE, p);
            return p;
        });
        return process(UUID.fromString(str(created, "id")), options);
    }

    /**
     * SEC-04: an FX quote funds only the payment it was priced for. The quote must be ACTIVE and unexpired, belong to
     * the debtor's customer (a quote priced for another customer is refused; an unbound quote only for staff, never
     * when a customer acts), sell the payment currency and buy the instructed currency, and its sell amount must equal
     * the payment amount (the amount debited and used for approval thresholds); an instructed_amount, when given,
     * must equal the quote's buy amount. fx-service re-checks the same binding atomically when the quote is accepted.
     */
    static void requireFxQuoteFits(JsonNode quote, ObjectNode v, UUID customer) {
        String status = str(quote, "status");
        if (!"ACTIVE".equals(status)) {
            throw ApiException.unprocessable("fx_quote_not_active", "The FX quote is " + status);
        }
        if (quote.hasNonNull("expires_at")
                && !java.time.OffsetDateTime.parse(quote.get("expires_at").asString()).isAfter(io.meridian.common.time.Clocks.now())) {
            throw ApiException.unprocessable("fx_quote_expired", "The FX quote has expired; request a new quote");
        }
        UUID quoteCustomer = quote.hasNonNull("customer_id") ? UUID.fromString(quote.get("customer_id").asString()) : null;
        if (quoteCustomer != null ? !quoteCustomer.equals(customer)
                : io.meridian.web.security.CustomerContext.current().isPresent()) {
            throw ApiException.unprocessable("fx_quote_customer_mismatch", "The FX quote was not priced for this customer");
        }
        if (!str(v, "currency").equals(quote.path("sell_currency").asString(null))
                || !str(v, "instructed_currency").equals(quote.path("buy_currency").asString(null))) {
            throw ApiException.unprocessable("fx_quote_mismatch", "The FX quote sells " + quote.path("sell_currency").asString(null)
                    + " for " + quote.path("buy_currency").asString(null) + "; the payment needs " + str(v, "currency")
                    + " to " + str(v, "instructed_currency"));
        }
        BigDecimal sell = quote.hasNonNull("sell_amount") ? new BigDecimal(quote.get("sell_amount").asString()) : null;
        if (sell == null || sell.compareTo(dec(v, "amount")) != 0) {
            throw ApiException.unprocessable("fx_quote_amount_mismatch", "The FX quote sells "
                    + (sell == null ? "?" : sell.toPlainString()) + " " + str(v, "currency") + "; the payment amount must be the same");
        }
        BigDecimal buy = quote.hasNonNull("buy_amount") ? new BigDecimal(quote.get("buy_amount").asString()) : null;
        if (v.hasNonNull("instructed_amount") && (buy == null || buy.compareTo(dec(v, "instructed_amount")) != 0)) {
            throw ApiException.unprocessable("fx_quote_amount_mismatch", "instructed_amount must equal the quote's buy amount "
                    + (buy == null ? "?" : buy.toPlainString()));
        }
    }

    /** Continues a payment from wherever it stopped (used for replays and the business-day scheduler). */
    public ObjectNode resume(UUID id, Options options) {
        String status = str(records.get(id), "status");
        return switch (status) {
            case "INITIATED" -> process(id, options);
            case "APPROVED" -> executeIfDue(id, options);
            default -> records.get(id);
        };
    }

    /**
     * Builds the column values of a new payment from a request: account checks, payee resolution, identifier
     * validation and server-generated references. Throws 422 for non-repairable errors; nothing is stored.
     */
    public ObjectNode prepare(ObjectNode request, Options options) {
        ObjectNode v = rows.columns(PaymentRecords.TABLE, request);
        for (String ro : List.of("status", "debtor_ledger_account_id", "end_to_end_id", "uetr", "instruction_id",
                "transaction_id", "clearing_system_ref", "settlement_amount", "settlement_currency", "fx_rate",
                "fee_amount", "fee_currency", "value_date", "rail_id", "rail_code", "hold_id", "journal_entry_id",
                "screening_result_id", "limit_check_id", "fraud_score", "rejection_code", "rejection_reason",
                "initiated_by", "submitted_at", "accepted_at", "settled_at", "idempotency_key")) {
            v.remove(ro);
        }
        String type = required(v, "payment_type");
        String direction = required(v, "direction");
        required(v, "channel");
        String currency = required(v, "currency");
        BigDecimal amount = dec(v, "amount");
        validateAmount(amount, currency, "amount");
        if (v.hasNonNull("instructed_amount")) {
            validateAmount(dec(v, "instructed_amount"), str(v, "instructed_currency") == null ? currency
                    : str(v, "instructed_currency"), "instructed_amount");
        }
        if (v.hasNonNull("fx_quote_id") && (str(v, "instructed_currency") == null
                || currency.equals(str(v, "instructed_currency")))) {
            throw ApiException.unprocessable("instructed_currency_required",
                    "A payment with fx_quote_id needs an instructed_currency different from currency");
        }
        LocalDate today = rows.today();
        LocalDate requested = date(v, "requested_execution_date");
        if (requested != null && requested.isBefore(today)) {
            throw ApiException.unprocessable("execution_date_in_past", "requested_execution_date is in the past");
        }
        if ("INBOUND".equals(direction)) {
            throw ApiException.unprocessable("inbound_not_allowed",
                    "Inbound payments are booked by the rail through POST /v1/payments/inbound");
        }
        if (v.hasNonNull("debtor_wallet_id") && !v.hasNonNull("debtor_account_id")) {
            throw ApiException.unprocessable("wallet_not_supported",
                    "Wallet-funded payments are initiated through wallet-service");
        }
        UUID debtorAccountId = uuid(v, "debtor_account_id");
        if (debtorAccountId == null) {
            throw ApiException.unprocessable("debtor_account_required", "debtor_account_id is required");
        }
        JsonNode debtor = core.depositAccount(debtorAccountId, "debtor");
        requireUsable(debtor, "debtor", true);
        if (!currency.equals(str(debtor, "currency"))) {
            throw ApiException.unprocessable("currency_mismatch", "The debtor account is in " + str(debtor, "currency")
                    + "; the payment currency must match (use fx_quote_id with instructed_currency for FX)");
        }
        UUID customer = uuid(debtor, "customer_id");
        if (v.hasNonNull("customer_id") && !uuid(v, "customer_id").equals(customer)) {
            throw ApiException.unprocessable("customer_account_mismatch", "The debtor account belongs to another customer");
        }
        v.put("customer_id", customer.toString());
        if (str(debtor, "ledger_account_id") == null) {
            throw ApiException.unprocessable("debtor_account_not_ready", "The debtor account has no ledger account yet");
        }
        v.put("debtor_ledger_account_id", str(debtor, "ledger_account_id"));
        String debtorIban = str(debtor, "iban");
        v.put("debtor_account_id_type", debtorIban != null ? "IBAN" : "ACCOUNT_NUMBER");
        v.put("debtor_account_number", debtorIban != null ? debtorIban : str(debtor, "account_number"));
        v.put("debtor_agent_bic", props.bankBic());
        if (debtorIban != null && !v.hasNonNull("debtor_country")) {
            v.put("debtor_country", Iban.country(debtorIban));
        }
        if (!v.hasNonNull("debtor_name")) {
            core.customerName(customer).ifPresent(n -> v.put("debtor_name", truncate(n, 140)));
        }
        if (v.hasNonNull("fx_quote_id")) {
            requireFxQuoteFits(risk.fxQuote(uuid(v, "fx_quote_id")), v, customer);
        }

        UUID payeeId = uuid(v, "payee_id");
        if (payeeId != null) {
            ObjectNode payee = rows.find("payee", payeeId)
                    .orElseThrow(() -> ApiException.unprocessable("payee_not_found", "Payee " + payeeId + " does not exist"));
            if (Set.of("BLOCKED", "DELETED").contains(str(payee, "status"))) {
                throw ApiException.unprocessable("payee_blocked", "Payments to this payee are blocked");
            }
            if (!customer.equals(uuid(payee, "customer_id"))) {
                throw ApiException.unprocessable("payee_not_owned", "The payee belongs to another customer's payee book");
            }
            fillFromPayee(v, payee);
        }

        if ("INTERNAL".equals(direction)) {
            UUID creditorAccountId = uuid(v, "creditor_account_id");
            if (creditorAccountId == null) {
                throw ApiException.unprocessable("creditor_account_required",
                        "Internal transfers need creditor_account_id");
            }
            if (creditorAccountId.equals(debtorAccountId)) {
                throw ApiException.unprocessable("same_account", "Debtor and creditor accounts are the same");
            }
            JsonNode creditor = core.depositAccount(creditorAccountId, "creditor");
            requireUsable(creditor, "creditor", false);
            if (!currency.equals(str(creditor, "currency"))) {
                throw ApiException.unprocessable("currency_mismatch", "The creditor account is in " + str(creditor, "currency"));
            }
            String iban = str(creditor, "iban");
            v.put("creditor_account_id_type", iban != null ? "IBAN" : "ACCOUNT_NUMBER");
            v.put("creditor_account_number", iban != null ? iban : str(creditor, "account_number"));
            v.put("creditor_agent_bic", props.bankBic());
            if (!v.hasNonNull("creditor_name")) {
                v.put("creditor_name", core.customerName(uuid(creditor, "customer_id")).map(n -> truncate(n, 140))
                        .orElse("Account holder"));
            }
        } else {
            if (!v.hasNonNull("creditor_name") || !v.hasNonNull("creditor_account_id_type")
                    || !v.hasNonNull("creditor_account_number")) {
                throw ApiException.unprocessable("missing_creditor_details",
                        "Outbound payments need creditor_name, creditor_account_id_type and creditor_account_number (or payee_id)");
            }
            if ("IBAN".equals(str(v, "creditor_account_id_type"))) {
                String iban = Iban.normalise(str(v, "creditor_account_number"));
                Iban.validate(iban).ifPresent(code -> {
                    throw ApiException.unprocessable(code, "creditor_account_number is not a valid IBAN");
                });
                v.put("creditor_account_number", iban);
                if (!v.hasNonNull("creditor_country")) {
                    v.put("creditor_country", Iban.country(iban));
                }
            }
            if (v.hasNonNull("creditor_agent_bic")) {
                v.put("creditor_agent_bic", Bic.normalise(str(v, "creditor_agent_bic")));
            }
        }
        if (!v.hasNonNull("charge_bearer")) {
            v.put("charge_bearer", "SHAR");
        }
        BigDecimal fee = "CRED".equals(str(v, "charge_bearer")) ? BigDecimal.ZERO
                : props.fees().getOrDefault(type, BigDecimal.ZERO);
        v.put("fee_amount", fee.toPlainString());
        v.put("fee_currency", currency);
        v.put("status", "INITIATED");
        v.put("end_to_end_id", options.endToEndId() != null ? truncate(options.endToEndId(), 35) : reference("E2E"));
        v.put("instruction_id", reference("INS"));
        v.put("uetr", UUID.randomUUID().toString());
        v.put("initiated_by", truncate(TenantContext.principal(), 64));
        return v;
    }

    private void fillFromPayee(ObjectNode v, ObjectNode payee) {
        Map<String, String> map = Map.of("creditor_name", "name", "creditor_account_id_type", "account_id_type",
                "creditor_account_number", "account_number", "creditor_agent_bic", "agent_bic",
                "creditor_agent_clearing_id", "agent_clearing_id", "creditor_country", "country");
        for (Map.Entry<String, String> e : map.entrySet()) {
            String payeeValue = str(payee, e.getValue());
            if (payeeValue == null) {
                continue;
            }
            if (!v.hasNonNull(e.getKey())) {
                v.put(e.getKey(), payeeValue);
            } else if (e.getKey().equals("creditor_account_number")
                    && !payeeValue.equalsIgnoreCase(str(v, e.getKey()).replace(" ", ""))) {
                throw ApiException.unprocessable("payee_mismatch", "creditor_account_number differs from the payee's account");
            }
        }
        if (payee.hasNonNull("address") && !v.hasNonNull("creditor_address")) {
            v.set("creditor_address", payee.get("address"));
        }
    }

    // ================================================================== pipeline

    /** INITIATED → enrichment/routing → screening → limits/approval → execution when due. */
    public ObjectNode process(UUID id, Options options) {
        ObjectNode p = records.get(id);
        if (!"INITIATED".equals(str(p, "status"))) {
            return p;
        }
        if ("OUTBOUND".equals(str(p, "direction"))) {
            Optional<ObjectNode> repaired = enrichAndRoute(p);
            if (repaired.isPresent()) {
                return repaired.get();
            }
        }
        boolean screen = props.screeningEnabled() && !options.skipScreening() && !"INTERNAL".equals(str(p, "direction"));
        if (screen) {
            ObjectNode screened = screen(id);
            if (!"CLEAR".equals(str(screened, "_screening"))) {
                screened.remove("_screening");
                return screened;
            }
        }
        return afterScreening(id, options);
    }

    /** Enrichment (agent BIC, character set) and routing; opens a repair item for repairable problems. */
    private Optional<ObjectNode> enrichAndRoute(ObjectNode p) {
        UUID id = UUID.fromString(str(p, "id"));
        ObjectNode fix = rows.object();
        String name = str(p, "creditor_name");
        if (name != null && !ISO_LATIN.matcher(name).matches()) {
            fix.put("creditor_name", transliterate(name));
            return Optional.of(openRepair(id, "NAME_CHARSET",
                    "Creditor name contains characters outside the ISO 20022 Latin character set", fix));
        }
        String bic = str(p, "creditor_agent_bic");
        String iban = "IBAN".equals(str(p, "creditor_account_id_type")) ? str(p, "creditor_account_number") : null;
        ObjectNode enrich = rows.object();
        if (bic != null && !Bic.isValid(bic)) {
            directoryBic(iban, id).ifPresent(b -> fix.put("creditor_agent_bic", b));
            return Optional.of(openRepair(id, "INVALID_CREDITOR_AGENT", "Creditor agent BIC " + bic + " is malformed", fix));
        }
        if (bic == null && str(p, "creditor_agent_clearing_id") == null && "CROSS_BORDER".equals(str(p, "payment_type"))) {
            Optional<String> found = directoryBic(iban, id);
            if (found.isEmpty()) {
                return Optional.of(openRepair(id, "MISSING_CREDITOR_AGENT",
                        "Cross-border payments need the creditor agent (BIC or clearing id); none could be derived", fix));
            }
            enrich.put("creditor_agent_bic", found.get());
            bic = found.get();
        }
        ObjectNode route;
        try {
            route = (ObjectNode) routing.route(routePayload(p, bic), "pay-route:" + id + ":" + str(p, "row_version"));
        } catch (ApiException e) {
            if (e.status() >= 500) {
                throw e;
            }
            return Optional.of(openRepair(id, "NO_ROUTE", "Routing rejected the payment: " + e.getMessage(), fix));
        }
        if (!route.path("routable").asBoolean(false)) {
            return Optional.of(openRepair(id, route.path("reason_code").asString("NO_ROUTE"),
                    route.path("reason").asString("No rail can carry this payment"), fix));
        }
        enrich.put("rail_id", route.path("payment_rail_id").asString());
        enrich.put("rail_code", route.path("rail_code").asString());
        if (route.hasNonNull("execution_date")) {
            enrich.put("value_date", route.get("execution_date").asString());
        }
        inTx(() -> records.update(id, enrich));
        return Optional.empty();
    }

    private Optional<String> directoryBic(String iban, UUID id) {
        if (iban == null) {
            return Optional.empty();
        }
        try {
            JsonNode r = routing.lookupParticipant(iban, null, "pay-bic:" + id);
            String bic = r.path("bic").asString(null);
            return r.path("found").asBoolean(bic != null) && bic != null ? Optional.of(bic) : Optional.empty();
        } catch (ApiException e) {
            if (e.status() >= 500) {
                throw e;
            }
            return Optional.empty();
        }
    }

    private ObjectNode routePayload(ObjectNode p, String bic) {
        ObjectNode r = rows.object();
        for (String f : List.of("payment_type", "direction", "amount", "currency", "creditor_country", "debtor_country",
                "priority", "requested_execution_date", "customer_id")) {
            if (p.hasNonNull(f)) {
                r.set(f, p.get(f));
            }
        }
        if (bic != null) {
            r.put("creditor_agent_bic", bic);
        }
        return r;
    }

    /** Opens a repair queue item and moves the payment to IN_REPAIR. */
    public ObjectNode openRepair(UUID paymentId, String code, String detail, ObjectNode suggestedFix) {
        return inTx(() -> {
            ObjectNode p = records.lock(paymentId);
            ObjectNode updated = records.transition(p, "IN_REPAIR", null, truncate(detail, 255), null, null);
            ObjectNode repair = rows.object();
            repair.put("payment_id", paymentId.toString());
            repair.put("status", "OPEN");
            repair.put("error_code", truncate(code, 32));
            repair.put("error_detail", detail);
            repair.set("suggested_fix", suggestedFix == null ? rows.object() : suggestedFix);
            ObjectNode r = rows.insert("payment_repair", repair);
            events.publish("payments.payment_repair.opened", "payment_repair", r);
            return updated;
        });
    }

    /** Sanctions screening; the returned row carries a transient {@code _screening} = CLEAR | HOLD | REJECTED. */
    private ObjectNode screen(UUID id) {
        ObjectNode p = inTx(() -> records.transition(records.lock(id), "SCREENING", null, "Sanctions screening", null, null));
        ObjectNode payload = rows.object();
        for (String f : List.of("debtor_name", "debtor_country", "debtor_agent_bic", "creditor_name", "creditor_country",
                "creditor_agent_bic", "creditor_account_number", "remittance_unstructured", "amount", "currency",
                "end_to_end_id", "uetr")) {
            if (p.hasNonNull(f)) {
                payload.set(f, p.get(f));
            }
        }
        JsonNode result;
        try {
            result = risk.screenPayment(id, payload, "pay-screen:" + id);
        } catch (ApiException e) {
            if (e.status() < 500) {
                throw e;
            }
            ObjectNode held = inTx(() -> {
                records.history(id, "SCREENING", "SCREENING", null, "Screening unavailable; held for compliance", null);
                return records.get(id);
            });
            held.put("_screening", "HOLD");
            return held;
        }
        String status = result.path("status").asString("ERROR");
        String resultId = result.path("id").asString(null);
        return inTx(() -> {
            ObjectNode locked = records.lock(id);
            ObjectNode changes = rows.object();
            if (resultId != null) {
                changes.put("screening_result_id", resultId);
            }
            ObjectNode out;
            String outcome;
            switch (status) {
                case "CLEAR", "FALSE_POSITIVE" -> {
                    out = records.update(id, changes);
                    outcome = "CLEAR";
                }
                case "CONFIRMED_MATCH" -> {
                    out = rejectLocked(locked, Reason.REGULATORY, "Sanctions screening confirmed a match", changes);
                    outcome = "REJECTED";
                }
                default -> {
                    out = records.update(id, changes);
                    records.history(id, "SCREENING", "SCREENING", null,
                            "Potential sanctions match; held for compliance review", null);
                    outcome = "HOLD";
                }
            }
            out.put("_screening", outcome);
            return out;
        });
    }

    /** Compliance decision on a payment held in SCREENING. */
    public ObjectNode screeningDecision(UUID id, String status, UUID screeningResultId, String reason) {
        ObjectNode p = records.get(id);
        if (!"SCREENING".equals(str(p, "status"))) {
            throw ApiException.conflict("invalid_transition", "Payment is not held in screening");
        }
        switch (status) {
            case "CLEAR", "FALSE_POSITIVE" -> {
                inTx(() -> {
                    ObjectNode changes = rows.object();
                    if (screeningResultId != null) {
                        changes.put("screening_result_id", screeningResultId.toString());
                    }
                    records.update(id, changes);
                    records.history(id, "SCREENING", "SCREENING", null,
                            "Compliance released the payment: " + (reason == null ? status : reason), null);
                    return null;
                });
                return afterScreening(id, Options.DEFAULT);
            }
            case "CONFIRMED_MATCH" -> {
                return inTx(() -> rejectLocked(records.lock(id), Reason.REGULATORY,
                        reason == null ? "Sanctions match confirmed by compliance" : reason, null));
            }
            default -> throw ApiException.unprocessable("invalid_screening_status",
                    "status must be CLEAR, FALSE_POSITIVE or CONFIRMED_MATCH");
        }
    }

    /** Limits reservation and approval decision (or the inbound credit). */
    ObjectNode afterScreening(UUID id, Options options) {
        ObjectNode p = records.get(id);
        if ("INBOUND".equals(str(p, "direction"))) {
            return creditInbound(id);
        }
        int minimumLevels = 0;
        if (props.limitsEnabled() && p.hasNonNull("customer_id") && !p.hasNonNull("limit_check_id")) {
            ObjectNode check = rows.object();
            check.put("subject_type", "CUSTOMER");
            check.put("subject_id", str(p, "customer_id"));
            check.put("customer_id", str(p, "customer_id"));
            check.put("transaction_type", str(p, "payment_type"));
            check.put("channel", riskChannel(str(p, "channel")));
            check.put("amount", dec(p, "amount").toPlainString());
            check.put("currency", str(p, "currency"));
            check.put("correlation_id", id.toString());
            JsonNode res = risk.checkLimits(check, "pay-limit:" + id);
            String checkId = res.path("id").asString(null);
            boolean allowed = res.path("allowed").asBoolean(true);
            String action = res.path("action").asString("");
            if (checkId != null) {
                inTx(() -> records.update(id, rows.object().put("limit_check_id", checkId)));
            }
            if (!allowed) {
                switch (action) {
                    case "REVIEW", "STEP_UP" -> minimumLevels = 1;
                    case "ALLOW_AND_ALERT" -> { }
                    default -> {
                        return inTx(() -> rejectLocked(records.lock(id), Reason.NOT_ALLOWED_AMOUNT,
                                "Payment exceeds the applicable limits", null));
                    }
                }
            }
        }
        List<ApprovalMatrix.Step> steps = options.skipApproval() ? List.of()
                : matrix.stepsFor(dec(p, "amount"), minimumLevels);
        if (!steps.isEmpty()) {
            return inTx(() -> {
                ObjectNode locked = records.lock(id);
                createApprovalSteps(id, null, steps);
                ObjectNode pending = records.transition(locked, "PENDING_APPROVAL", null,
                        steps.size() + " approval level(s) required", null, null);
                // Central maker-checker: checkers decide in the workflow inbox; PaymentApprovalHandler applies it.
                central.requestPayment(pending);
                return pending;
            });
        }
        inTx(() -> records.transition(records.lock(id), "APPROVED", null, "Approval not required", null, null));
        return executeIfDue(id, options);
    }

    /** Creates the approval levels of a payment or a bulk payment. */
    public void createApprovalSteps(UUID paymentId, UUID bulkId, List<ApprovalMatrix.Step> steps) {
        String expires = rows.now().plusHours(props.approval().expiryHours()).toString();
        for (ApprovalMatrix.Step s : steps) {
            ObjectNode a = rows.object();
            if (paymentId != null) {
                a.put("payment_id", paymentId.toString());
            }
            if (bulkId != null) {
                a.put("bulk_payment_id", bulkId.toString());
            }
            a.put("approver_level", s.level());
            a.put("required_approvals", s.requiredApprovals());
            a.put("approvals_received", 0);
            a.putArray("approved_by");
            a.put("status", "PENDING");
            a.put("expires_at", expires);
            rows.insert("payment_approval", a);
        }
    }

    public List<ApprovalMatrix.Step> approvalSteps(BigDecimal amount, int minimumLevels) {
        return matrix.stepsFor(amount, minimumLevels);
    }

    /** All approval levels done: APPROVED, then executed when due. */
    public ObjectNode approvalsComplete(UUID id) {
        inTx(() -> records.transition(records.lockIn(id, Set.of("PENDING_APPROVAL"), "approve"), "APPROVED", null,
                "All approval levels approved", null, null));
        return executeIfDue(id, Options.DEFAULT);
    }

    // ================================================================== execution

    /** Executes an APPROVED payment when its execution date has arrived. */
    public ObjectNode executeIfDue(UUID id, Options options) {
        ObjectNode p = records.get(id);
        if (!"APPROVED".equals(str(p, "status"))) {
            return p;
        }
        LocalDate due = later(date(p, "requested_execution_date"), date(p, "value_date"));
        if (due != null && due.isAfter(rows.today())) {
            return p;
        }
        return execute(id, options);
    }

    /** Reserves funds (FX deal first), then settles a book transfer or submits to the rail. */
    public ObjectNode execute(UUID id, Options options) {
        ObjectNode p = records.get(id);
        if (!"APPROVED".equals(str(p, "status"))) {
            throw ApiException.conflict("invalid_transition", "Only APPROVED payments can be executed; status is "
                    + str(p, "status"));
        }
        if (p.hasNonNull("fx_quote_id") && !p.hasNonNull("fx_rate")) {
            JsonNode deal;
            try {
                deal = risk.acceptFxQuote(uuid(p, "fx_quote_id"), id, "pay-fx:" + id, p);
            } catch (ApiException e) {
                if (e.status() >= 500) {
                    throw e;
                }
                return inTx(() -> rejectLocked(records.lock(id), "FF01", "FX quote could not be used: " + e.getMessage(), null));
            }
            // SEC-04 defence in depth: the booked deal must sell exactly what this payment debits
            if (!str(p, "currency").equals(deal.path("sell_currency").asString(str(p, "currency")))
                    || (deal.hasNonNull("sell_amount") && new BigDecimal(deal.get("sell_amount").asString()).compareTo(dec(p, "amount")) != 0)) {
                return inTx(() -> rejectLocked(records.lock(id), "FF01", "FX deal does not match the payment amount", null));
            }
            ObjectNode fx = rows.object();
            fx.put("fx_rate", deal.path("rate").asString());
            fx.put("instructed_amount", deal.path("buy_amount").asString());
            fx.put("instructed_currency", deal.path("buy_currency").asString(str(p, "instructed_currency")));
            p = inTx(() -> records.update(id, fx));
        }
        if (!p.hasNonNull("hold_id")) {
            UUID holdId = options.sharedHoldId() != null ? options.sharedHoldId() : sharedBulkHold(p);
            if (holdId == null) {
                BigDecimal total = dec(p, "amount").add(decOr0(p, "fee_amount"));
                try {
                    JsonNode hold = ledger.placeHold("pay-hold:" + id, uuid(p, "debtor_ledger_account_id"), total,
                            str(p, "currency"), "Payment " + str(p, "end_to_end_id") + " to " + str(p, "creditor_name"), id);
                    holdId = UUID.fromString(hold.path("id").asString());
                } catch (ApiException e) {
                    if (e.status() == 422 && REJECTABLE_FUNDS_CODES.contains(e.code())) {
                        String code = "insufficient_funds".equals(e.code()) ? Reason.INSUFFICIENT_FUNDS
                                : "account_frozen".equals(e.code()) ? Reason.BLOCKED_ACCOUNT : Reason.CLOSED_ACCOUNT;
                        return inTx(() -> rejectLocked(records.lock(id), code, "Funds reservation failed: " + e.getMessage(), null));
                    }
                    throw e;
                }
            }
            UUID h = holdId;
            p = inTx(() -> records.update(id, rows.object().put("hold_id", h.toString())));
        }
        if ("INTERNAL".equals(str(p, "direction"))) {
            return settleInternal(id);
        }
        return submit(id);
    }

    private UUID sharedBulkHold(ObjectNode p) {
        UUID bulkId = uuid(p, "bulk_payment_id");
        if (bulkId == null) {
            return null;
        }
        return rows.find("bulk_payment", bulkId).filter(b -> bool(b, "batch_booking")).map(b -> uuid(b, "hold_id"))
                .orElse(null);
    }

    /** Builds and sends the pacs.008 through routing-service; SUBMITTED on success. */
    private ObjectNode submit(UUID id) {
        ObjectNode p = records.get(id);
        if (!p.hasNonNull("transaction_id")) {
            String txId = reference("TX");
            p = inTx(() -> records.update(id, rows.object().put("transaction_id", txId)));
        }
        if (!p.hasNonNull("rail_id")) {
            Optional<ObjectNode> repaired = enrichAndRoute(p);
            if (repaired.isPresent()) {
                return repaired.get();
            }
            p = records.get(id);
        }
        ObjectNode msgPayment = rows.object();
        for (String f : List.of("id", "end_to_end_id", "uetr", "instruction_id", "transaction_id", "amount", "currency",
                "value_date", "requested_execution_date", "charge_bearer", "debtor_name", "debtor_account_number",
                "debtor_account_id_type", "debtor_agent_bic", "debtor_country", "creditor_name",
                "creditor_account_number", "creditor_account_id_type", "creditor_agent_bic",
                "creditor_agent_clearing_id", "creditor_country", "purpose_code", "category_purpose",
                "remittance_unstructured", "instructed_amount", "instructed_currency", "fx_rate")) {
            if (p.hasNonNull(f)) {
                msgPayment.set(f, p.get(f));
            }
        }
        JsonNode message;
        try {
            message = routing.sendPacs008(uuid(p, "rail_id"), msgPayment, "pay-submit:" + id);
        } catch (ApiException e) {
            if (e.status() >= 500) {
                inTx(() -> {
                    records.history(id, "APPROVED", "APPROVED", null, "Submission deferred: rail unavailable", null);
                    return null;
                });
                return records.get(id);
            }
            return inTx(() -> failLocked(records.lock(id), "NARR", "Rail submission refused: " + e.getMessage()));
        }
        UUID messageId = message.hasNonNull("id") ? UUID.fromString(message.get("id").asString()) : null;
        LocalDate settlementDate = later(date(p, "value_date"), rows.today());
        return inTx(() -> {
            ObjectNode changes = rows.object();
            changes.put("submitted_at", rows.now().toString());
            changes.put("value_date", settlementDate.toString());
            changes.put("settlement_amount", dec(records.get(id), "amount").toPlainString());
            changes.put("settlement_currency", str(records.get(id), "currency"));
            return records.transition(records.lock(id), "SUBMITTED", null,
                    "Submitted to " + str(records.get(id), "rail_code"), changes, messageId);
        });
    }

    /** Book transfer: capture the hold to the creditor's ledger account (fee to fee income); SETTLED. */
    private ObjectNode settleInternal(UUID id) {
        ObjectNode p = records.get(id);
        JsonNode creditor = core.depositAccount(uuid(p, "creditor_account_id"), "creditor");
        UUID creditorLedger = uuid(creditor, "ledger_account_id");
        UUID je;
        try {
            je = captureFunds(p, creditorLedger, "Transfer " + str(p, "end_to_end_id"));
        } catch (ApiException e) {
            if (e.status() == 422) {
                return inTx(() -> rejectLocked(records.lock(id), Reason.BLOCKED_ACCOUNT,
                        "Settlement refused by the ledger: " + e.getMessage(), null));
            }
            throw e;
        }
        return inTx(() -> {
            ObjectNode locked = records.lock(id);
            String now = rows.now().toString();
            ObjectNode changes = rows.object();
            changes.put("submitted_at", now);
            changes.put("accepted_at", now);
            changes.put("settled_at", now);
            changes.put("value_date", rows.today().toString());
            if (je != null) {
                changes.put("journal_entry_id", je.toString());
            }
            ObjectNode settled = records.transition(locked, "SETTLED", null, "Book transfer settled", changes, null);
            commitLimits(settled);
            hooks.onSettled(settled);
            return settled;
        });
    }

    /** Captures the payment amount (and fee) from the payment's hold; returns the principal journal entry. */
    private UUID captureFunds(ObjectNode p, UUID counterAccount, String description) {
        UUID id = UUID.fromString(str(p, "id"));
        UUID holdId = uuid(p, "hold_id");
        BigDecimal fee = decOr0(p, "fee_amount");
        boolean shared = sharedBulkHold(p) != null && sharedBulkHold(p).equals(holdId);
        ledger.capture(holdId, dec(p, "amount"), counterAccount, description, !shared && fee.signum() == 0,
                "pay-capture:" + id);
        UUID principal = ledger.captureJournalEntry(holdId);
        if (fee.signum() > 0 && !shared) {
            ledger.capture(holdId, fee, props.gl().feeIncome(), "Fee " + str(p, "end_to_end_id"), true, "pay-fee:" + id);
        }
        return principal;
    }

    // ================================================================== rail status

    /** pacs.002 status report for a submitted payment. */
    public ObjectNode statusReport(UUID id, String status, String reasonCode, String reason, String clearingRef,
                                   UUID railMessageId, LocalDate settlementDate) {
        ObjectNode p = records.get(id);
        String current = str(p, "status");
        switch (status) {
            case "ACCP", "ACSP" -> {
                if ("ACCEPTED".equals(current)) {
                    return p;
                }
                return inTx(() -> {
                    ObjectNode locked = records.lockIn(id, Set.of("SUBMITTED"), "accept");
                    ObjectNode changes = rows.object();
                    changes.put("accepted_at", rows.now().toString());
                    if (clearingRef != null) {
                        changes.put("clearing_system_ref", clearingRef);
                    }
                    return records.transition(locked, "ACCEPTED", status, reason == null ? "Accepted by the rail" : reason,
                            changes, railMessageId);
                });
            }
            case "ACSC" -> {
                if ("SETTLED".equals(current)) {
                    return p;
                }
                if (!Set.of("SUBMITTED", "ACCEPTED").contains(current)) {
                    throw ApiException.conflict("invalid_transition", "Cannot settle a payment in status " + current);
                }
                UUID settlementAccount = routing.rail(uuid(p, "rail_id"))
                        .map(r -> uuid(r, "settlement_account_id")).orElse(null);
                UUID counter = settlementAccount != null ? settlementAccount : props.gl().clearing();
                UUID je = captureFunds(p, counter, "Settlement " + str(p, "end_to_end_id") + " via " + str(p, "rail_code"));
                return inTx(() -> {
                    ObjectNode locked = records.lock(id);
                    ObjectNode changes = rows.object();
                    String now = rows.now().toString();
                    if (!locked.hasNonNull("accepted_at")) {
                        changes.put("accepted_at", now);
                    }
                    changes.put("settled_at", now);
                    if (settlementDate != null) {
                        changes.put("value_date", settlementDate.toString());
                    }
                    if (clearingRef != null) {
                        changes.put("clearing_system_ref", clearingRef);
                    }
                    if (je != null) {
                        changes.put("journal_entry_id", je.toString());
                    }
                    ObjectNode settled = records.transition(locked, "SETTLED", status,
                            reason == null ? "Settled on " + str(locked, "rail_code") : reason, changes, railMessageId);
                    commitLimits(settled);
                    hooks.onSettled(settled);
                    return settled;
                });
            }
            case "RJCT" -> {
                if ("REJECTED".equals(current)) {
                    return p;
                }
                return inTx(() -> {
                    ObjectNode locked = records.lockIn(id, Set.of("SUBMITTED", "ACCEPTED"), "reject");
                    ObjectNode changes = rows.object();
                    if (clearingRef != null) {
                        changes.put("clearing_system_ref", clearingRef);
                    }
                    return rejectLocked(locked, reasonCode == null ? Reason.NARRATIVE : reasonCode,
                            reason == null ? "Rejected by the rail" : reason, changes, railMessageId);
                });
            }
            default -> throw ApiException.unprocessable("invalid_status_report",
                    "status must be ACCP, ACSP, ACSC or RJCT (ISO 20022 ExternalPaymentTransactionStatus1Code)");
        }
    }

    // ================================================================== inbound

    /** Books an inbound credit transfer (pacs.008) received on a rail. */
    public ObjectNode receive(JsonNode req, String idempotencyKey) {
        String e2e = str(req, "end_to_end_id");
        String railCode = str(req, "rail_code");
        Optional<ObjectNode> dup = rows.queryOne("""
                select row_to_json(t)::text from payment t
                where direction = 'INBOUND' and end_to_end_id = ? and rail_code = ? and amount = ?::numeric""",
                e2e, railCode, str(req, "amount"));
        if (dup.isPresent()) {
            return dup.get();
        }
        Optional<ObjectNode> replay = rows.queryOne("select row_to_json(t)::text from payment t where idempotency_key = ?",
                idempotencyKey);
        if (replay.isPresent()) {
            return replay.get();
        }
        String currency = str(req, "currency");
        BigDecimal amount = dec(req, "amount");
        validateAmount(amount, currency, "amount");
        String creditorNumber = str(req, "creditor_account_number").replace(" ", "").toUpperCase(Locale.ROOT);
        boolean isIban = Iban.isValid(creditorNumber);
        Optional<JsonNode> account = core.findDepositAccount(isIban ? "IBAN" : "ACCOUNT_NUMBER", creditorNumber);
        Optional<JsonNode> rail = routing.railByCode(railCode);
        ObjectNode v = rows.object();
        v.put("payment_type", rail.map(r -> r.path("is_instant").asBoolean(false)).orElse(false) ? "INSTANT" : "DOMESTIC");
        v.put("direction", "INBOUND");
        v.put("channel", "INBOUND_RAIL");
        v.put("status", "INITIATED");
        v.put("idempotency_key", idempotencyKey);
        v.put("amount", amount.toPlainString());
        v.put("currency", currency);
        v.put("settlement_amount", amount.toPlainString());
        v.put("settlement_currency", currency);
        v.put("end_to_end_id", truncate(e2e, 35));
        v.put("uetr", req.hasNonNull("uetr") ? str(req, "uetr") : UUID.randomUUID().toString());
        v.put("instruction_id", reference("INS"));
        if (req.hasNonNull("transaction_id")) {
            v.put("transaction_id", str(req, "transaction_id"));
        }
        v.put("debtor_name", truncate(str(req, "debtor_name"), 140));
        if (req.hasNonNull("debtor_account_number")) {
            String d = str(req, "debtor_account_number");
            v.put("debtor_account_number", d);
            v.put("debtor_account_id_type", Iban.isValid(d) ? "IBAN" : "ACCOUNT_NUMBER");
            if (Iban.isValid(d)) {
                v.put("debtor_country", Iban.country(d));
            }
        }
        if (req.hasNonNull("debtor_agent_bic")) {
            v.put("debtor_agent_bic", str(req, "debtor_agent_bic"));
        }
        v.put("creditor_account_number", creditorNumber);
        v.put("creditor_account_id_type", isIban ? "IBAN" : "ACCOUNT_NUMBER");
        v.put("creditor_agent_bic", props.bankBic());
        if (req.hasNonNull("remittance_unstructured")) {
            v.put("remittance_unstructured", str(req, "remittance_unstructured"));
        }
        if (req.hasNonNull("settlement_date")) {
            v.put("value_date", str(req, "settlement_date"));
        }
        rail.ifPresent(r -> {
            v.put("rail_id", str(r, "id"));
        });
        v.put("rail_code", railCode);
        v.put("charge_bearer", "SLEV");
        v.put("initiated_by", truncate(TenantContext.principal(), 64));
        String rejectCode = null;
        String rejectReason = null;
        if (account.isEmpty()) {
            rejectCode = Reason.INVALID_ACCOUNT;
            rejectReason = "Creditor account not found";
        } else {
            JsonNode a = account.get();
            v.put("creditor_account_id", str(a, "id"));
            v.put("customer_id", str(a, "customer_id"));
            v.put("creditor_name", req.hasNonNull("creditor_name") ? truncate(str(req, "creditor_name"), 140)
                    : core.customerName(uuid(a, "customer_id")).orElse("Account holder"));
            if ("CLOSED".equals(str(a, "status"))) {
                rejectCode = Reason.CLOSED_ACCOUNT;
                rejectReason = "Creditor account is closed";
            } else if (Set.of("BLOCKED", "BLOCKED_CREDIT", "FROZEN").contains(str(a, "status"))) {
                rejectCode = Reason.BLOCKED_ACCOUNT;
                rejectReason = "Creditor account does not accept credits";
            } else if (!currency.equals(str(a, "currency"))) {
                rejectCode = "AM03";
                rejectReason = "Currency " + currency + " is not the account currency";
            }
        }
        if (!v.hasNonNull("creditor_name")) {
            v.put("creditor_name", truncate(str(req, "creditor_name"), 140));
        }
        UUID railMessageId = uuid(req, "rail_message_id");
        String code = rejectCode;
        String why = rejectReason;
        ObjectNode created = inTx(() -> {
            ObjectNode p = rows.insert(PaymentRecords.TABLE, v);
            UUID id = UUID.fromString(str(p, "id"));
            records.history(id, null, "INITIATED", null, "Received on " + railCode, railMessageId);
            events.publish("payments.payment.initiated", PaymentRecords.TABLE, p);
            if (code != null) {
                return rejectLocked(records.lock(id), code, why, null, railMessageId);
            }
            return p;
        });
        if (code != null) {
            return created;
        }
        return process(UUID.fromString(str(created, "id")), Options.DEFAULT);
    }

    /** Credits the creditor account of a screened inbound payment: debit rail settlement, credit customer. */
    private ObjectNode creditInbound(UUID id) {
        ObjectNode p = records.get(id);
        JsonNode account = core.depositAccount(uuid(p, "creditor_account_id"), "creditor");
        UUID settlement = routing.rail(uuid(p, "rail_id")).map(r -> uuid(r, "settlement_account_id"))
                .orElse(props.gl().clearing());
        UUID je;
        try {
            je = ledger.post("pay-credit:" + id, "TRANSFER", "Incoming payment " + str(p, "end_to_end_id"),
                    str(p, "currency"), "PAYMENT", id, List.of(
                            LedgerClient.Leg.debit(settlement, dec(p, "amount"), "Inbound " + str(p, "rail_code")),
                            LedgerClient.Leg.credit(uuid(account, "ledger_account_id"), dec(p, "amount"),
                                    "From " + str(p, "debtor_name"))));
        } catch (ApiException e) {
            if (e.status() == 422) {
                return inTx(() -> rejectLocked(records.lock(id), Reason.BLOCKED_ACCOUNT,
                        "Credit refused by the ledger: " + e.getMessage(), null));
            }
            throw e;
        }
        return inTx(() -> {
            ObjectNode locked = records.lock(id);
            String now = rows.now().toString();
            ObjectNode changes = rows.object();
            changes.put("accepted_at", now);
            changes.put("settled_at", now);
            changes.put("journal_entry_id", je.toString());
            if (!locked.hasNonNull("value_date")) {
                changes.put("value_date", rows.today().toString());
            }
            return records.transition(locked, "SETTLED", "ACSC", "Credited to the customer account", changes, null);
        });
    }

    // ================================================================== outcomes

    /** Rejects a locked payment: releases its hold and limits reservation. */
    public ObjectNode rejectLocked(ObjectNode p, String code, String reason, ObjectNode changes) {
        return rejectLocked(p, code, reason, changes, null);
    }

    public ObjectNode rejectLocked(ObjectNode p, String code, String reason, ObjectNode changes, UUID railMessageId) {
        releaseFunds(p, reason);
        ObjectNode c = changes == null ? rows.object() : changes;
        c.put("rejection_code", truncate(code, 8));
        c.put("rejection_reason", truncate(reason, 255));
        ObjectNode out = records.transition(p, "REJECTED", code, reason, c, railMessageId);
        hooks.onFailed(out);
        return out;
    }

    public ObjectNode failLocked(ObjectNode p, String code, String reason) {
        releaseFunds(p, reason);
        ObjectNode c = rows.object();
        c.put("rejection_code", truncate(code, 8));
        c.put("rejection_reason", truncate(reason, 255));
        ObjectNode out = records.transition(p, "FAILED", code, reason, c, null);
        hooks.onFailed(out);
        return out;
    }

    /** Cancels a payment not yet submitted; releases funds and pending approvals. */
    public ObjectNode cancel(UUID id, String reason) {
        return inTx(() -> {
            ObjectNode p = records.lockIn(id, Set.of("INITIATED", "SCREENING", "PENDING_APPROVAL", "APPROVED", "IN_REPAIR"),
                    "cancel");
            releaseFunds(p, reason);
            rows.execute("update payment_approval set status = 'REJECTED', comment = 'Payment cancelled', decided_at = now() "
                    + "where payment_id = ? and status = 'PENDING'", id);
            if ("PENDING_APPROVAL".equals(str(p, "status"))) {
                central.closed(PaymentApprovalRequests.PAYMENT, id, "payment cancelled");
            }
            ObjectNode c = rows.object();
            c.put("rejection_code", Reason.REQUESTED_BY_CUSTOMER);
            c.put("rejection_reason", truncate(reason == null ? "Cancelled" : reason, 255));
            ObjectNode out = records.transition(p, "CANCELLED", Reason.REQUESTED_BY_CUSTOMER, reason, c, null);
            hooks.onFailed(out);
            return out;
        });
    }

    private void releaseFunds(ObjectNode p, String reason) {
        UUID id = UUID.fromString(str(p, "id"));
        UUID holdId = uuid(p, "hold_id");
        if (holdId != null && !holdId.equals(sharedBulkHold(p))) {
            ledger.releaseHold(holdId, reason, "pay-release:" + id);
        }
        UUID check = uuid(p, "limit_check_id");
        if (check != null) {
            risk.releaseLimits(check, reason, "pay-limit-release:" + id);
        }
    }

    private void commitLimits(ObjectNode p) {
        UUID check = uuid(p, "limit_check_id");
        if (check != null) {
            risk.commitLimits(check, dec(p, "amount"), "pay-limit-commit:" + str(p, "id"));
        }
    }

    // ================================================================== helpers

    private <T> T inTx(Supplier<T> work) {
        return tx.execute(s -> work.get());
    }

    private static String required(ObjectNode v, String f) {
        String s = str(v, f);
        if (s == null || s.isBlank()) {
            throw ApiException.unprocessable("missing_" + f, f + " is required");
        }
        return s;
    }

    static void validateAmount(BigDecimal amount, String currency, String field) {
        if (amount == null || amount.signum() <= 0) {
            throw ApiException.unprocessable("invalid_amount", field + " must be positive");
        }
        int digits;
        try {
            digits = Currency.getInstance(currency).getDefaultFractionDigits();
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable("invalid_currency", currency + " is not an ISO 4217 currency");
        }
        if (digits >= 0 && amount.stripTrailingZeros().scale() > digits) {
            throw ApiException.unprocessable("invalid_amount_precision",
                    field + " has more decimals than " + currency + " allows (" + digits + ")");
        }
    }

    private static void requireUsable(JsonNode account, String role, boolean debit) {
        String status = str(account, "status");
        Set<String> blocked = debit ? Set.of("CLOSED", "BLOCKED", "BLOCKED_DEBIT", "FROZEN", "PENDING_APPROVAL", "DORMANT")
                : Set.of("CLOSED", "BLOCKED", "BLOCKED_CREDIT", "FROZEN", "PENDING_APPROVAL");
        if (status != null && blocked.contains(status)) {
            throw ApiException.unprocessable(role + "_account_not_active", "The " + role + " account is " + status);
        }
    }

    static String riskChannel(String channel) {
        return switch (channel == null ? "API" : channel) {
            case "MOBILE" -> "MOBILE_APP";
            case "WEB" -> "WEB";
            case "BRANCH" -> "BRANCH";
            case "USSD" -> "USSD";
            case "AGENT" -> "AGENT";
            case "OPEN_BANKING" -> "OPEN_BANKING";
            default -> "API";
        };
    }

    /** Unique ISO 20022 reference: prefix + UUIDv7 hex (max 35 characters). */
    public static String reference(String prefix) {
        String hex = Ids.newId().toString().replace("-", "").toUpperCase(Locale.ROOT);
        String r = prefix + hex;
        return r.length() > 35 ? r.substring(0, 35) : r;
    }

    public static String truncate(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }

    static String transliterate(String s) {
        String a = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return a.replaceAll("[^A-Za-z0-9/\\-?:().,'+ ]", " ").replaceAll("\\s+", " ").trim();
    }

    private static LocalDate later(LocalDate a, LocalDate b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isAfter(b) ? a : b;
    }
}
