package io.meridian.businesschannel.application;

import io.meridian.common.time.Clocks;
import io.meridian.web.time.BusinessDates;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * "Today" for a business account: the current business date of the account's booking legal entity
 * ({@code deposit_account.legal_entity_id}, business-day-service), else the UTC calendar date. Daily limits count per
 * business date and execution dates are checked against it, so a business booked far from UTC, or on an entity still
 * running yesterday's end of day, is not limited on the wrong day around midnight.
 */
@Component
public class BusinessDay {
    private final BusinessDates dates;

    public BusinessDay(BusinessDates dates) {
        this.dates = dates;
    }

    public LocalDate today(JsonNode account) {
        UUID entity = Req.rowUuid(account, "legal_entity_id");
        return entity != null ? dates.today(entity, BusinessDay::utc) : dates.today(BusinessDay::utc);
    }

    private static LocalDate utc() {
        return Clocks.now().withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
    }
}
