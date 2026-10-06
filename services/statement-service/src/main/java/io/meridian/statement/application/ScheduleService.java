package io.meridian.statement.application;

import static io.meridian.statement.infrastructure.Rows.str;
import static io.meridian.statement.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.statement.domain.StatementPeriods;
import io.meridian.statement.infrastructure.BankData;
import io.meridian.statement.infrastructure.Rows;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Statement schedules: validation of delivery preferences and the cycle run that generates and delivers statements. */
@Service
public class ScheduleService {
    static final String TABLE = "statement_schedule";

    private final Rows rows;
    private final BankData bank;
    private final StatementService statements;

    public ScheduleService(Rows rows, BankData bank, StatementService statements) {
        this.rows = rows;
        this.bank = bank;
        this.statements = statements;
    }

    /** Validates a full (merged) schedule; {@code checkAccount} also verifies the account with deposit-service. */
    public void validate(JsonNode s, boolean checkAccount) {
        List<String> formats = formats(s.get("formats"));
        if (formats.isEmpty()) {
            throw ApiException.unprocessable("formats_required", "formats must list at least one StatementFormat");
        }
        for (String f : formats) {
            if (!StatementPeriods.FORMATS.contains(f)) {
                throw ApiException.unprocessable("invalid_format", "Unknown statement format " + f);
            }
        }
        int day = s.path("cycle_day").asInt(1);
        String freq = s.path("frequency").asString("MONTHLY");
        int max = "WEEKLY".equals(freq) || "BIWEEKLY".equals(freq) ? 7 : 31;
        if (day < 1 || day > max) {
            throw ApiException.unprocessable("invalid_cycle_day", "cycle_day must be 1-" + max + " for " + freq);
        }
        String channel = s.path("delivery_channel").asString("IN_APP");
        if ("EMAIL".equals(channel) && !s.hasNonNull("email")) {
            throw ApiException.unprocessable("email_required", "EMAIL delivery needs email");
        }
        if ("POST".equals(channel) && !s.hasNonNull("postal_address_id")) {
            throw ApiException.unprocessable("postal_address_required", "POST delivery needs postal_address_id");
        }
        if ("SFTP".equals(channel) && !s.hasNonNull("sftp_endpoint")) {
            throw ApiException.unprocessable("sftp_endpoint_required", "SFTP delivery needs sftp_endpoint");
        }
        if (checkAccount) {
            bank.account(uuid(s, "deposit_account_id"));
        }
    }

    /** {@code formats}: a list of StatementFormat values, e.g. {@code ["PDF","CSV"]} (duplicates ignored). */
    public static List<String> formats(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isArray()) {
            throw ApiException.unprocessable("invalid_formats", "formats must be a list of StatementFormat values");
        }
        for (JsonNode f : node) {
            if (!f.isString()) {
                throw ApiException.unprocessable("invalid_format", "formats must contain StatementFormat names");
            }
            if (!out.contains(f.asString())) {
                out.add(f.asString());
            }
        }
        return out;
    }

    /** The cycle for {@code date} as a summary (end-of-day endpoint); a re-run for the same date produces nothing new. */
    @Transactional
    public ObjectNode cycleResult(LocalDate date) {
        List<ObjectNode> produced = runCycle(date);
        ObjectNode out = rows.object();
        out.put("business_date", date.toString());
        out.put("produced", produced.size());
        out.put("failed", (int) produced.stream().filter(s -> "FAILED".equals(str(s, "status"))).count());
        var list = out.putArray("statements");
        for (ObjectNode s : produced) {
            ObjectNode item = list.addObject();
            for (String f : List.of("id", "deposit_account_id", "statement_no", "format", "status", "failure_reason")) {
                if (s.hasNonNull(f)) {
                    item.set(f, s.get(f));
                }
            }
        }
        return out;
    }

    /**
     * Cycle run for one business date: every active schedule whose cycle ends on {@code date} gets the next statement
     * number in each of its formats, generated and delivered on its channel. Returns the statements produced.
     */
    @Transactional
    public List<ObjectNode> runCycle(LocalDate date) {
        List<ObjectNode> produced = new ArrayList<>();
        for (ObjectNode s : rows.query("select row_to_json(x)::text from statement_schedule x where is_active order by id")) {
            Optional<StatementPeriods.Period> period = StatementPeriods.cycleEndingOn(str(s, "frequency"),
                    s.path("cycle_day").asInt(1), date);
            if (period.isEmpty()) {
                continue;
            }
            UUID account = uuid(s, "deposit_account_id");
            JsonNode acc = bank.account(account);
            for (String format : formats(s.get("formats"))) {
                if (rows.scalar("""
                        select 1 from statement where deposit_account_id = ? and format = ? and period_start = ? and period_end = ?""",
                        Integer.class, account, format, period.get().start(), period.get().end()).isPresent()) {
                    continue; // already produced (re-run of the same day)
                }
                ObjectNode req = rows.object();
                req.put("deposit_account_id", account.toString());
                req.put("period_start", period.get().start().toString());
                req.put("period_end", period.get().end().toString());
                req.put("format", format);
                req.put("currency", acc.path("currency").asString());
                req.put("is_on_demand", false);
                ObjectNode st = statements.create(req);
                if ("READY".equals(str(st, "status"))) {
                    st = statements.deliver(st, null, null);
                }
                produced.add(st);
            }
        }
        return produced;
    }
}
