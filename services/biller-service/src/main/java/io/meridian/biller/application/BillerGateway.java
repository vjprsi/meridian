package io.meridian.biller.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import tools.jackson.databind.JsonNode;

/** Port to a biller / mobile operator (API, ISO 8583, file or aggregator adapter behind it). */
public interface BillerGateway {

    /** Result of a bill lookup; response code "00" means found. */
    record Inquiry(String responseCode, String message, String customerName, BigDecimal amountDue, LocalDate dueDate,
                   String inquiryReference) {
        public boolean found() {
            return "00".equals(responseCode);
        }
    }

    enum Outcome { SUCCESS, REJECTED, TIMEOUT }

    /** Result of a payment or top-up at the provider. */
    record Result(Outcome outcome, String providerReference, String token, String receiptNumber, String message) {}

    Inquiry inquire(JsonNode biller, String accountReference);

    /** Pays a bill; {@code attempt} starts at 1 and grows with each retry of the same payment. */
    Result pay(JsonNode biller, JsonNode payment, int attempt);

    Result topup(JsonNode biller, JsonNode topup, int attempt);
}
