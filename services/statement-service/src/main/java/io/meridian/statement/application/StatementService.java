package io.meridian.statement.application;

import static io.meridian.statement.infrastructure.Rows.date;
import static io.meridian.statement.infrastructure.Rows.dec;
import static io.meridian.statement.infrastructure.Rows.str;
import static io.meridian.statement.infrastructure.Rows.ts;
import static io.meridian.statement.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.common.page.Cursor;
import io.meridian.events.OutboxWriter;
import io.meridian.statement.config.StatementConfig.StatementProperties;
import io.meridian.statement.domain.StatementDocument;
import io.meridian.statement.domain.StatementPeriods;
import io.meridian.statement.domain.StatementRenderer;
import io.meridian.statement.infrastructure.BankData;
import io.meridian.statement.infrastructure.Rows;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Statement generation from ledger data: opening balance (booked balance at period start), every entry booked in
 * the period as a frozen statement line with its running balance, totals cross-checked against the ledger's
 * closing balance, then rendered (PDF / CSV / camt.053 / MT940 / OFX / JSON) and fingerprinted (SHA-256).
 * Upstream failures leave the statement FAILED so it can be regenerated.
 */
@Service
public class StatementService {
    static final String TABLE = "statement";

    private final Rows rows;
    private final BankData bank;
    private final OutboxWriter outbox;
    private final Clock clock;
    private final StatementProperties props;

    public StatementService(Rows rows, BankData bank, OutboxWriter outbox, Clock clock, StatementProperties props) {
        this.rows = rows;
        this.bank = bank;
        this.outbox = outbox;
        this.clock = clock;
        this.props = props;
    }

    LocalDate today() {
        return LocalDate.now(clock);
    }

    // ------------------------------------------------------------------ generate

    @Transactional
    public ObjectNode create(Object body) {
        ObjectNode v = rows.columns(TABLE, body);
        LocalDate start = date(v, "period_start");
        LocalDate end = date(v, "period_end");
        StatementPeriods.validatePeriod(start, end, today());
        if (v.path("statement_no").asInt(0) < 1) {
            throw ApiException.unprocessable("invalid_statement_no", "statement_no must be a positive sequence number");
        }
        UUID accountId = uuid(v, "deposit_account_id");
        JsonNode account = bank.account(accountId);
        if (!str(v, "currency").equals(account.path("currency").asString())) {
            throw ApiException.unprocessable("currency_mismatch", "currency must be the account currency "
                    + account.path("currency").asString());
        }
        if (account.path("ledger_account_id").isNull() || !account.has("ledger_account_id")) {
            throw ApiException.unprocessable("account_not_open", "The account has no ledger account yet");
        }
        String format = v.path("format").asString("PDF");
        if (rows.scalar("select 1 from statement where deposit_account_id = ? and statement_no = ? and format = ?",
                Integer.class, accountId, v.get("statement_no").asInt(), format).isPresent()) {
            throw ApiException.conflict("duplicate_statement", "Statement " + v.get("statement_no").asInt() + " in "
                    + format + " already exists for this account");
        }
        v.put("status", "GENERATING");
        ObjectNode statement = rows.insert(TABLE, v);
        return generate(statement, account);
    }

    @Transactional
    public ObjectNode regenerate(UUID id) {
        ObjectNode s = rows.lock(TABLE, id);
        StatementPeriods.requireTransition(str(s, "status"), "GENERATING");
        ObjectNode generating = rows.update(TABLE, id, rows.object().put("status", "GENERATING"));
        return generate(generating, bank.account(uuid(s, "deposit_account_id")));
    }

    private ObjectNode generate(ObjectNode statement, JsonNode account) {
        UUID id = uuid(statement, "id");
        try {
            UUID ledgerAccount = UUID.fromString(account.get("ledger_account_id").asString());
            LocalDate start = date(statement, "period_start");
            LocalDate end = date(statement, "period_end");
            OffsetDateTime from = start.atStartOfDay().atOffset(ZoneOffset.UTC);
            OffsetDateTime to = end.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
            BigDecimal opening = bank.balanceAt(ledgerAccount, from.minusNanos(1000));
            BigDecimal ledgerClosing = bank.balanceAt(ledgerAccount, to.minusNanos(1000));
            List<JsonNode> entries = new ArrayList<>(bank.entries(ledgerAccount, from, to));
            entries.sort(Comparator.comparingLong(e -> e.path("account_version").asLong()));
            Map<String, JsonNode> journals = new HashMap<>();
            List<StatementDocument.Line> lines = new ArrayList<>();
            BigDecimal interest = BigDecimal.ZERO;
            BigDecimal fees = BigDecimal.ZERO;
            int no = 0;
            for (JsonNode e : entries) {
                String jeId = e.path("journal_entry_id").asString(null);
                JsonNode je = jeId == null ? null : journals.computeIfAbsent(jeId, k -> bank.journalEntry(UUID.fromString(k)));
                String type = je == null ? null : je.path("entry_type").asString(null);
                String direction = e.path("direction").asString();
                BigDecimal amount = new BigDecimal(e.path("amount").asString());
                if ("INTEREST".equals(type)) {
                    interest = "CREDIT".equals(direction) ? interest.add(amount) : interest.subtract(amount);
                }
                if ("FEE".equals(type)) {
                    fees = "DEBIT".equals(direction) ? fees.add(amount) : fees.subtract(amount);
                }
                String description = firstNonBlank(e.path("description").asString(null),
                        je == null ? null : je.path("description").asString(null), type, "Ledger entry");
                OffsetDateTime booked = OffsetDateTime.parse(e.path("created_at").asString());
                OffsetDateTime value = e.hasNonNull("effective_at") ? OffsetDateTime.parse(e.get("effective_at").asString()) : booked;
                lines.add(new StatementDocument.Line(++no, booked.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate(),
                        value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate(), truncate(description, 500),
                        je == null ? null : truncate(je.path("correlation_id").asString(null), 140), null, direction, amount,
                        new BigDecimal(e.path("balance_after").asString()), e.path("id").asString(null),
                        StatementPeriods.transactionCode(type, direction)));
            }
            StatementDocument.Totals totals = StatementDocument.totals(opening, lines);
            if (totals.closing().compareTo(ledgerClosing) != 0 || !StatementDocument.chained(opening, lines)) {
                return fail(statement, "Ledger data is inconsistent: opening " + opening.toPlainString() + " + movements = "
                        + totals.closing().toPlainString() + " but the ledger closing balance is " + ledgerClosing.toPlainString());
            }
            for (StatementDocument.Line l : lines) {
                ObjectNode r = rows.object();
                r.put("statement_id", id.toString());
                r.put("line_no", l.lineNo());
                r.put("booking_date", l.bookingDate().toString());
                r.put("value_date", l.valueDate().toString());
                r.put("description", l.description());
                if (l.reference() != null) {
                    r.put("reference", l.reference());
                }
                r.put("direction", l.direction());
                r.put("amount", l.amount().toPlainString());
                r.put("running_balance", l.runningBalance().toPlainString());
                if (l.ledgerEntryId() != null) {
                    r.put("ledger_entry_id", l.ledgerEntryId());
                }
                r.put("transaction_code", l.transactionCode());
                rows.insert("statement_line", r);
            }
            ObjectNode v = rows.object();
            v.put("status", "READY");
            v.put("opening_balance", opening.toPlainString());
            v.put("total_credits", totals.credits().toPlainString());
            v.put("total_debits", totals.debits().toPlainString());
            v.put("closing_balance", totals.closing().toPlainString());
            v.put("interest_earned", interest.toPlainString());
            v.put("fees_charged", fees.toPlainString());
            v.put("line_count", lines.size());
            v.put("generated_at", OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).withNano(0).toString());
            ObjectNode ready = rows.update(TABLE, id, v);
            String checksum = render(ready, account).sha256();
            ready = rows.update(TABLE, id, rows.object().put("checksum", checksum));
            outbox.publish("accounts.statement.generated", TABLE, id, ready);
            return ready;
        } catch (ApiException e) {
            if (e.status() == 422 && "account_not_found".equals(e.code())) {
                throw e;
            }
            return fail(statement, e.code() + ": " + e.getMessage());
        }
    }

    private ObjectNode fail(ObjectNode statement, String reason) {
        UUID id = uuid(statement, "id");
        ObjectNode failed = rows.update(TABLE, id, rows.object().put("status", "FAILED"));
        ObjectNode payload = failed.deepCopy();
        payload.put("failure_reason", reason);
        outbox.publish("accounts.statement.failed", TABLE, id, payload);
        return failed;
    }

    // ------------------------------------------------------------------ rendering and download

    /** Rebuilds the document from the frozen statement and lines. */
    public StatementRenderer.Rendered render(JsonNode s, JsonNode account) {
        List<StatementDocument.Line> lines = new ArrayList<>();
        for (ObjectNode l : rows.query("""
                select row_to_json(l)::text from statement_line l where statement_id = ? order by line_no""", uuid(s, "id"))) {
            lines.add(new StatementDocument.Line(l.get("line_no").asInt(), date(l, "booking_date"), date(l, "value_date"),
                    str(l, "description"), str(l, "reference"), str(l, "counterparty"), str(l, "direction"),
                    dec(l, "amount"), dec(l, "running_balance"), str(l, "ledger_entry_id"), str(l, "transaction_code")));
        }
        StatementDocument doc = new StatementDocument(str(s, "id"), s.get("statement_no").asInt(),
                account.path("account_number").asString(""), account.path("iban").asString(null), str(s, "currency"),
                date(s, "period_start"), date(s, "period_end"), dec(s, "opening_balance"), dec(s, "total_credits"),
                dec(s, "total_debits"), dec(s, "closing_balance"), dec(s, "interest_earned"), dec(s, "fees_charged"),
                ts(s, "generated_at"), str(s, "language"), lines);
        return StatementRenderer.render(doc, str(s, "format"));
    }

    /** The file of a READY/DELIVERED statement after verifying it still matches the stored checksum. */
    public Download download(UUID id) {
        ObjectNode s = rows.get(TABLE, id);
        String status = str(s, "status");
        if (!"READY".equals(status) && !"DELIVERED".equals(status)) {
            throw ApiException.conflict("statement_not_ready", "Statement " + id + " is " + status);
        }
        StatementRenderer.Rendered r = render(s, bank.account(uuid(s, "deposit_account_id")));
        if (!r.sha256().equals(str(s, "checksum"))) {
            throw new ApiException(500, "checksum_mismatch", "The statement content no longer matches its checksum");
        }
        String filename = "statement-" + s.get("statement_no").asInt() + "-" + str(s, "period_end") + "." + r.extension();
        return new Download(r.content(), r.contentType(), filename, str(s, "checksum"));
    }

    public record Download(byte[] content, String contentType, String filename, String checksum) {}

    /** A signed, expiring download URL (HMAC-SHA256 over id and expiry). */
    public ObjectNode signedLink(UUID id, String kind) {
        ObjectNode s = rows.get(kind, id);
        String status = str(s, "status");
        if (!"READY".equals(status) && !"DELIVERED".equals(status)) {
            throw ApiException.conflict("not_ready", kind.replace('_', ' ') + " " + id + " is " + status);
        }
        long expires = Instant.now(clock).getEpochSecond() + props.linkTtlSeconds();
        String base = "statement".equals(kind) ? "/v1/statements/" : "/v1/tax-certificates/";
        ObjectNode out = rows.object();
        out.put("url", base + id + "/download?expires=" + expires + "&signature=" + signature(id, expires));
        out.put("expires_at", Instant.ofEpochSecond(expires).toString());
        if (str(s, "checksum") != null) {
            out.put("checksum", str(s, "checksum"));
        }
        return out;
    }

    public void verifySignature(UUID id, String expires, String signature) {
        long exp;
        try {
            exp = Long.parseLong(expires);
        } catch (NumberFormatException e) {
            throw new ApiException(403, "invalid_signature", "Download link is malformed");
        }
        if (!java.security.MessageDigest.isEqual(signature(id, exp).getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII))) {
            throw new ApiException(403, "invalid_signature", "Download link signature is invalid");
        }
        if (Instant.now(clock).getEpochSecond() > exp) {
            throw new ApiException(403, "link_expired", "Download link has expired");
        }
    }

    private String signature(UUID id, long expires) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(props.downloadSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String message = io.meridian.common.tenancy.TenantContext.require() + "|" + id + "|" + expires;
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ delivery and lines

    @Transactional
    public ObjectNode resend(UUID id, String channel, String email) {
        ObjectNode s = rows.lock(TABLE, id);
        StatementPeriods.requireTransition(str(s, "status"), "DELIVERED");
        return deliver(s, channel, email);
    }

    ObjectNode deliver(ObjectNode s, String channel, String email) {
        UUID id = uuid(s, "id");
        Optional<ObjectNode> schedule = rows.queryOne("""
                select row_to_json(x)::text from statement_schedule x where deposit_account_id = ?""",
                uuid(s, "deposit_account_id"));
        String ch = channel != null ? channel : schedule.map(x -> str(x, "delivery_channel")).orElse("IN_APP");
        ObjectNode delivery = rows.object();
        delivery.put("channel", ch);
        switch (ch) {
            case "EMAIL" -> {
                String to = email != null ? email : schedule.map(x -> str(x, "email")).orElse(null);
                if (to == null) {
                    throw ApiException.unprocessable("email_required", "EMAIL delivery needs an email address");
                }
                delivery.put("recipient", to);
                delivery.put("password_protected", schedule.map(x -> x.path("password_protect").asBoolean(true)).orElse(true));
            }
            case "POST" -> {
                String address = schedule.map(x -> str(x, "postal_address_id")).orElse(null);
                if (address == null) {
                    throw ApiException.unprocessable("postal_address_required", "POST delivery needs a postal address");
                }
                delivery.put("postal_address_id", address);
            }
            case "SFTP" -> {
                String endpoint = schedule.map(x -> str(x, "sftp_endpoint")).orElse(null);
                if (endpoint == null) {
                    throw ApiException.unprocessable("sftp_endpoint_required", "SFTP delivery needs an sftp_endpoint");
                }
                delivery.put("sftp_endpoint", endpoint);
            }
            default -> { }
        }
        ObjectNode v = rows.object();
        v.put("status", "DELIVERED");
        v.put("delivered_at", OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).toString());
        ObjectNode delivered = rows.update(TABLE, id, v);
        ObjectNode payload = delivered.deepCopy();
        payload.set("delivery", delivery);
        payload.set("download", signedLink(id, TABLE));
        outbox.publish("accounts.statement.delivered", TABLE, id, payload);
        return delivered;
    }

    /** Lines in line order with a keyset cursor (ids are time-ordered in insertion = line order). */
    public ObjectNode lines(UUID id, Integer limit, String cursor) {
        rows.get(TABLE, id);
        int n = Cursor.limit(limit);
        List<ObjectNode> data = cursor == null || cursor.isBlank()
                ? rows.query("select row_to_json(l)::text from statement_line l where statement_id = ? order by id limit ?",
                id, n + 1)
                : rows.query("""
                        select row_to_json(l)::text from statement_line l where statement_id = ? and id > ?
                        order by id limit ?""", id, Cursor.decode(cursor), n + 1);
        boolean more = data.size() > n;
        List<ObjectNode> page = more ? data.subList(0, n) : data;
        ObjectNode out = rows.object();
        var arr = out.putArray("data");
        page.forEach(arr::add);
        out.put("has_more", more);
        if (more) {
            out.put("next_cursor", Cursor.encode(uuid(page.getLast(), "id")));
        } else {
            out.putNull("next_cursor");
        }
        return out;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    private static String truncate(String s, int n) {
        return s == null ? null : s.length() <= n ? s : s.substring(0, n);
    }
}
