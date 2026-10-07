package io.meridian.web.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.client.ServiceClient;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class BusinessDatesTest {
    final JsonMapper json = JsonMapper.builder().build();
    final ServiceClient client = mock(ServiceClient.class);
    final LocalDate clockDate = LocalDate.of(2030, 1, 1);

    @BeforeEach
    void tenant() {
        TenantContext.set(UUID.randomUUID(), "t");
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void entityDateComesFromBusinessDayServiceAndIsCached() {
        UUID le = UUID.randomUUID();
        when(client.get(eq("business-day-service"), anyString())).thenReturn(json.readTree("{\"business_date\":\"2026-10-06\"}"));
        BusinessDates d = new BusinessDates(client, "payment-service", true, Duration.ofMinutes(1));
        assertThat(d.today(le, () -> clockDate)).isEqualTo(LocalDate.of(2026, 10, 6));
        assertThat(d.today(le, () -> clockDate)).isEqualTo(LocalDate.of(2026, 10, 6));
        verify(client, times(1)).get(eq("business-day-service"), anyString());
    }

    @Test
    void tenantDateRequiresAgreementOtherwiseFallback() {
        BusinessDates d = new BusinessDates(client, "deposit-service", true, Duration.ZERO);
        when(client.get(eq("business-day-service"), anyString())).thenReturn(json.readTree(
                "{\"data\":[{\"business_date\":\"2026-10-06\"},{\"business_date\":\"2026-10-06\"}]}"));
        assertThat(d.today(() -> clockDate)).isEqualTo(LocalDate.of(2026, 10, 6));
        when(client.get(eq("business-day-service"), anyString())).thenReturn(json.readTree(
                "{\"data\":[{\"business_date\":\"2026-10-06\"},{\"business_date\":\"2026-10-07\"}]}"));
        assertThat(d.today(() -> clockDate)).isEqualTo(clockDate);
    }

    @Test
    void fallsBackWhenUnavailableDisabledOrSelf() {
        when(client.get(eq("business-day-service"), anyString())).thenThrow(new IllegalStateException("down"));
        assertThat(new BusinessDates(client, "deposit-service", true, Duration.ZERO).today(() -> clockDate)).isEqualTo(clockDate);
        assertThat(new BusinessDates(client, "deposit-service", false, Duration.ZERO).today(() -> clockDate)).isEqualTo(clockDate);
        assertThat(new BusinessDates(client, "business-day-service", true, Duration.ZERO).today(() -> clockDate)).isEqualTo(clockDate);
    }
}
