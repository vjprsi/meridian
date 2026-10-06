package io.meridian.openbanking.application;

import static io.meridian.openbanking.application.TppService.trim;
import static io.meridian.openbanking.infrastructure.Rows.date;
import static io.meridian.openbanking.infrastructure.Rows.dec;
import static io.meridian.openbanking.infrastructure.Rows.str;
import static io.meridian.openbanking.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.openbanking.domain.ConsentRules;
import io.meridian.openbanking.infrastructure.CoreBanking;
import io.meridian.openbanking.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * PISP payment initiation: checks the payment against the authorised consent, creates the core payment in
 * payment-service (channel OPEN_BANKING, debtor = the account the customer selected), consumes single-use
 * consents and mirrors the core payment status to the TPP.
 */
@Service
public class PaymentInitiationService {
    static final String TABLE = "ob_payment_initiation";
    private static final Set<String> FINAL = Set.of("REJECTED", "EXECUTED", "CANCELLED");

    private final Rows rows;
    private final Events events;
    private final CoreBanking core;
    private final ConsentService consents;

    public PaymentInitiationService(Rows rows, Events events, CoreBanking core, ConsentService consents) {
        this.rows = rows;
        this.events = events;
        this.core = core;
        this.consents = consents;
    }

    @Transactional
    public ObjectNode initiate(Object body) {
        ObjectNode v = rows.columns(TABLE, body);
        UUID consentId = uuid(v, "ob_consent_id");
        UUID tppId = uuid(v, "tpp_id");
        ObjectNode consent = consents.usableConsent(consentId, tppId, Set.of("PIS", "VRP"));
        BigDecimal amount = dec(v, "amount");
        if (amount == null || amount.signum() <= 0) {
            throw ApiException.unprocessable("invalid_amount", "amount must be positive");
        }
        String currency = str(v, "currency");
        String creditorName = str(v, "creditor_name");
        String creditorAccount = ConsentRules.normaliseAccount(str(v, "creditor_account"));
        @SuppressWarnings("unchecked")
        Map<String, Object> initiation = consent.hasNonNull("initiation")
                ? rows.json().treeToValue(consent.get("initiation"), Map.class) : null;
        if ("VRP".equals(str(consent, "consent_type"))) {
            JsonNode limits = consent.path("vrp_control_parameters");
            String max = limits.path("maximum_individual_amount").asString(null);
            if (max != null && amount.compareTo(new BigDecimal(max)) > 0) {
                throw ApiException.unprocessable("vrp_limit_exceeded", "amount exceeds the VRP maximum individual amount " + max);
            }
            String vrpCcy = limits.path("currency").asString(null);
            if (vrpCcy != null && !vrpCcy.equals(currency)) {
                throw ApiException.unprocessable("payment_does_not_match_consent", "currency differs from the VRP consent");
            }
        }
        List<String> mismatches = ConsentRules.initiationMismatches(initiation, amount, currency, creditorName, creditorAccount);
        if (!mismatches.isEmpty()) {
            throw ApiException.unprocessable("payment_does_not_match_consent",
                    "The payment differs from the authorised consent in " + mismatches);
        }
        LocalDate execution = date(v, "requested_execution_date");
        LocalDate today = rows.now().toLocalDate();
        if (execution != null && execution.isBefore(today)) {
            throw ApiException.unprocessable("invalid_execution_date", "requested_execution_date is in the past");
        }
        List<ObjectNode> accounts = rows.query(
                "select row_to_json(t)::text from ob_consent_account t where ob_consent_id = ?", consentId);
        if (accounts.size() != 1) {
            throw ApiException.unprocessable("debtor_account_not_selected", "The consent has no single debtor account");
        }
        UUID debtorAccount = uuid(accounts.getFirst(), "account_id");
        UUID id = Ids.newId();
        v.put("id", id.toString());
        v.put("creditor_account", creditorAccount);
        v.put("external_payment_id", "pmt-" + consents.token(12));
        v.put("debtor_account_id", debtorAccount.toString());
        v.put("status", "RECEIVED");
        rows.insert(TABLE, v);

        ObjectNode payment = rows.object();
        String product = str(v, "payment_product") == null ? "" : str(v, "payment_product").toLowerCase(Locale.ROOT);
        payment.put("payment_type", product.contains("instant") ? "INSTANT" : "DOMESTIC");
        payment.put("direction", "OUTBOUND");
        payment.put("channel", "OPEN_BANKING");
        payment.put("customer_id", str(consent, "customer_id"));
        payment.put("debtor_account_id", debtorAccount.toString());
        payment.put("creditor_name", creditorName);
        payment.put("creditor_account_id_type", ConsentRules.isIban(creditorAccount) ? "IBAN" : "ACCOUNT_NUMBER");
        payment.put("creditor_account_number", creditorAccount);
        payment.put("amount", amount.toPlainString());
        payment.put("currency", currency);
        if (str(v, "remittance_information") != null) {
            payment.put("remittance_unstructured", str(v, "remittance_information"));
        }
        if (execution != null) {
            payment.put("requested_execution_date", execution.toString());
        }
        payment.put("ob_consent_id", consentId.toString());
        JsonNode created = core.createPayment(payment, "obi:" + id);

        ObjectNode upd = rows.object();
        upd.put("payment_id", str(created, "id"));
        upd.put("status", ConsentRules.initiationStatus(str(created, "status")));
        String reason = str(created, "rejection_reason");
        if (reason != null) {
            upd.put("status_reason", trim(reason, 255));
        }
        ObjectNode row = rows.update(TABLE, id, upd);
        if ("PIS".equals(str(consent, "consent_type")) || !consent.path("recurring").asBoolean(true)) {
            ObjectNode cv = rows.object();
            cv.put("status", "CONSUMED");
            events.publish("openbanking.ob_consent.consumed", ConsentService.CONSENT, rows.update(ConsentService.CONSENT, consentId, cv));
        }
        events.publish("openbanking.ob_payment_initiation.received", TABLE, row);
        events.publish("openbanking.ob_payment_initiation.status_changed", TABLE, row);
        return row;
    }

    @Transactional
    public ObjectNode refresh(UUID id) {
        ObjectNode row = rows.lock(TABLE, id);
        UUID paymentId = uuid(row, "payment_id");
        if (paymentId == null || FINAL.contains(str(row, "status"))) {
            return row;
        }
        JsonNode payment = core.payment(paymentId);
        return applyStatus(row, ConsentRules.initiationStatus(str(payment, "status")), str(payment, "rejection_reason"));
    }

    @Transactional
    public ObjectNode cancel(UUID id, String reason) {
        ObjectNode row = rows.lock(TABLE, id);
        String status = str(row, "status");
        if (!"RECEIVED".equals(status) && !"PENDING".equals(status)) {
            throw ApiException.conflict("invalid_transition", "A " + status + " payment initiation cannot be cancelled");
        }
        LocalDate execution = date(row, "requested_execution_date");
        if (execution == null || !execution.isAfter(rows.now().toLocalDate())) {
            throw ApiException.conflict("not_cancellable", "Only future-dated payment initiations can be cancelled");
        }
        UUID paymentId = uuid(row, "payment_id");
        String why = reason == null ? "Cancelled by the PISP" : trim(reason, 255);
        if (paymentId != null) {
            ObjectNode body = rows.object();
            body.put("reason", why);
            core.cancelPayment(paymentId, body, "obi-cancel:" + id);
        }
        return applyStatus(row, "CANCELLED", why);
    }

    private ObjectNode applyStatus(ObjectNode row, String status, String reason) {
        if (status.equals(str(row, "status"))) {
            return row;
        }
        ObjectNode v = rows.object();
        v.put("status", status);
        if (reason != null) {
            v.put("status_reason", trim(reason, 255));
        }
        ObjectNode updated = rows.update(TABLE, uuid(row, "id"), v);
        events.publish("openbanking.ob_payment_initiation.status_changed", TABLE, updated);
        return updated;
    }
}
