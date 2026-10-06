package io.meridian.referencedata.infrastructure;

import io.meridian.referencedata.domain.BusinessCalendar.HolidayDay;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reference-data SQL beyond generic CRUD. Table names are constants; all values are bind parameters. */
@Repository
public class RefDataStore {
    private static final Set<String> TABLES = Set.of("currency", "country", "holiday_calendar", "holiday",
            "fx_rate_source", "fx_rate", "bank_directory");

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public RefDataStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Optional<JsonNode> byId(String table, UUID id) {
        if (!TABLES.contains(table)) {
            throw new IllegalArgumentException("unknown table " + table);
        }
        return jdbc.sql("select row_to_json(t)::text from " + table + " t where id = ?")
                .param(id).query(String.class).optional().map(json::readTree);
    }

    public Optional<JsonNode> currency(String code) {
        return jdbc.sql("select row_to_json(t)::text from currency t where code = ?")
                .param(code).query(String.class).optional().map(json::readTree);
    }

    public Optional<JsonNode> country(String code) {
        return jdbc.sql("select row_to_json(t)::text from country t where code = ?")
                .param(code).query(String.class).optional().map(json::readTree);
    }

    /** Holidays of a calendar between two dates (inclusive). */
    public List<HolidayDay> holidays(UUID calendarId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                select holiday_date, name, holiday_type, is_half_day from holiday
                where holiday_calendar_id = ? and holiday_date between ? and ?""")
                .params(calendarId, from, to)
                .query((rs, i) -> new HolidayDay(rs.getObject(1, LocalDate.class), rs.getString(2), rs.getString(3),
                        rs.getBoolean(4)))
                .list();
    }

    public Set<LocalDate> holidayDates(UUID calendarId, int year) {
        return new HashSet<>(jdbc.sql("""
                select holiday_date from holiday where holiday_calendar_id = ?
                and holiday_date between ? and ?""")
                .params(calendarId, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31))
                .query((rs, i) -> rs.getObject(1, LocalDate.class)).list());
    }

    /** Latest observation for a pair and type valid at {@code at} (tenant-scoped by row-level security). */
    public Optional<JsonNode> latestRate(String base, String quote, String type, OffsetDateTime at) {
        return jdbc.sql("""
                select row_to_json(t)::text from fx_rate t
                where base_currency = ? and quote_currency = ? and rate_type = ?
                  and valid_from <= ? and (valid_to is null or valid_to > ?)
                order by valid_from desc, id desc limit 1""")
                .params(base, quote, type, at, at)
                .query(String.class).optional().map(json::readTree);
    }

    public List<JsonNode> banksByClearingCode(String country, String code) {
        return jdbc.sql("""
                select row_to_json(t)::text from bank_directory t
                where country = ? and national_clearing_code = ? and is_active order by bic nulls last, id limit 5""")
                .params(country, code).query(String.class).list().stream().map(json::readTree).toList();
    }

    public List<JsonNode> banksByBic(String bic) {
        String b = bic.length() == 8 ? bic + "%" : bic;
        return jdbc.sql("""
                select row_to_json(t)::text from bank_directory t
                where (bic = ? or bic like ?) and is_active order by length(bic), bic limit 5""")
                .params(bic, b).query(String.class).list().stream().map(json::readTree).toList();
    }

    /** Banks whose BIC starts with the 4-letter bank code and country (e.g. NWBK + GB). */
    public List<JsonNode> banksByBicPrefix(String bankCode, String country) {
        return jdbc.sql("""
                select row_to_json(t)::text from bank_directory t
                where bic like ? and is_active order by length(bic), bic limit 5""")
                .param(bankCode + country + "%").query(String.class).list().stream().map(json::readTree).toList();
    }
}
