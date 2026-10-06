package io.meridian.cardclearing.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * One validated clearing record as read from a scheme file. Never carries a PAN: cards are identified by
 * {@code card_id}. {@code raw} keeps only the known, non-sensitive fields as received.
 */
public record ParsedRecord(String recordType, UUID cardId, String networkTransactionId, String arn, String rrn,
                           String authCode, String merchantId, String merchantName, String mcc,
                           String merchantCountry, LocalDate transactionDate, BigDecimal transactionAmount,
                           String transactionCurrency, BigDecimal billingAmount, String billingCurrency,
                           BigDecimal settlementAmount, String settlementCurrency, BigDecimal interchangeAmount,
                           String interchangeRateCode, BigDecimal schemeFeeAmount, Map<String, String> raw) {

    public boolean isCrossCurrency() {
        return !transactionCurrency.equals(billingCurrency);
    }
}
