package io.meridian.disbursement.application;

import static io.meridian.disbursement.infrastructure.Rows.date;
import static io.meridian.disbursement.infrastructure.Rows.dec;
import static io.meridian.disbursement.infrastructure.Rows.integer;
import static io.meridian.disbursement.infrastructure.Rows.str;
import static io.meridian.disbursement.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.disbursement.domain.Lifecycle;
import io.meridian.disbursement.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Disbursement programs: creation rules and the DRAFT / ACTIVE / SUSPENDED / CLOSED lifecycle. */
@Service
public class ProgramService {
    static final String TABLE = "disbursement_program";
    static final Set<String> PROGRAM_TYPES = Set.of("G2P", "PAYROLL", "SOCIAL_TRANSFER", "HUMANITARIAN", "B2C_PAYOUT");
    /** Fields that can only change while the program is still a draft. */
    static final Set<String> DRAFT_ONLY = Set.of("funding_wallet_id", "currency", "program_type", "code");

    private final Rows rows;
    private final Events events;
    private final WalletPort wallets;

    public ProgramService(Rows rows, Events events, WalletPort wallets) {
        this.rows = rows;
        this.events = events;
        this.wallets = wallets;
    }

    @Transactional
    public JsonNode create(JsonNode req) {
        validate(req, null);
        ObjectNode values = rows.columns(TABLE, req);
        values.put("status", "DRAFT");
        if (rows.scalar("select count(*) from disbursement_program where code = ?", Long.class, str(req, "code"))
                .orElse(0L) > 0) {
            throw ApiException.conflict("duplicate_code", "A program with code " + str(req, "code") + " already exists");
        }
        return rows.insert(TABLE, values);
    }

    /** Validates a PATCH before the generic update applies it. */
    @Transactional(readOnly = true)
    public void checkUpdate(UUID id, JsonNode req) {
        ObjectNode current = rows.get(TABLE, id);
        String status = str(current, "status");
        if ("CLOSED".equals(status)) {
            throw ApiException.conflict("program_closed", "A closed program cannot be changed");
        }
        if (!"DRAFT".equals(status)) {
            for (String f : DRAFT_ONLY) {
                if (req.hasNonNull(f) && !req.get(f).asString().equals(str(current, f))) {
                    throw ApiException.unprocessable("field_not_updatable",
                            f + " can only be changed while the program is DRAFT");
                }
            }
        }
        ObjectNode merged = current.deepCopy();
        req.propertyNames().forEach(n -> {
            if (!req.get(n).isNull()) {
                merged.set(n, req.get(n));
            }
        });
        validate(merged, id);
    }

    private static void validate(JsonNode p, UUID id) {
        String type = str(p, "program_type");
        if (type != null && !PROGRAM_TYPES.contains(type)) {
            throw ApiException.unprocessable("invalid_program_type", "program_type must be one of " + PROGRAM_TYPES);
        }
        Integer levels = integer(p, "approval_levels");
        if (levels != null && (levels < 1 || levels > 5)) {
            throw ApiException.unprocessable("invalid_approval_levels", "approval_levels must be between 1 and 5");
        }
        BigDecimal cap = dec(p, "max_amount_per_beneficiary");
        if (cap != null && cap.signum() <= 0) {
            throw ApiException.unprocessable("invalid_amount", "max_amount_per_beneficiary must be positive");
        }
        Integer tier = integer(p, "require_kyc_tier");
        if (tier != null && tier < 0) {
            throw ApiException.unprocessable("invalid_kyc_tier", "require_kyc_tier cannot be negative");
        }
        LocalDate start = date(p, "start_date");
        LocalDate end = date(p, "end_date");
        if (start != null && end != null && end.isBefore(start)) {
            throw ApiException.unprocessable("invalid_dates", "end_date is before start_date");
        }
    }

    @Transactional
    public JsonNode activate(UUID id) {
        ObjectNode p = rows.lock(TABLE, id);
        Lifecycle.program(str(p, "status"), "ACTIVE");
        boolean ended = str(p, "end_date") != null && java.time.LocalDate.parse(str(p, "end_date")).isBefore(rows.today());
        if (str(p, "end_date") != null && ended) {
            throw ApiException.unprocessable("program_ended", "The program end_date has passed");
        }
        JsonNode wallet = wallets.wallet(uuid(p, "funding_wallet_id"));
        if (!"ACTIVE".equals(str(wallet, "status"))) {
            throw ApiException.unprocessable("funding_wallet_not_active",
                    "Funding wallet is " + str(wallet, "status") + ", it must be ACTIVE");
        }
        if (!str(p, "currency").equals(str(wallet, "currency"))) {
            throw ApiException.unprocessable("currency_mismatch",
                    "Funding wallet currency " + str(wallet, "currency") + " differs from the program currency " + str(p, "currency"));
        }
        return changeStatus(id, "ACTIVE", null);
    }

    @Transactional
    public JsonNode suspend(UUID id, String reason) {
        ObjectNode p = rows.lock(TABLE, id);
        Lifecycle.program(str(p, "status"), "SUSPENDED");
        return changeStatus(id, "SUSPENDED", reason);
    }

    @Transactional
    public JsonNode close(UUID id, String reason) {
        ObjectNode p = rows.lock(TABLE, id);
        Lifecycle.program(str(p, "status"), "CLOSED");
        long inFlight = rows.scalar("select count(*) from disbursement_batch where disbursement_program_id = ?"
                + " and status in ('VALIDATING','PENDING_APPROVAL','APPROVED','PROCESSING')", Long.class, id).orElse(0L);
        if (inFlight > 0) {
            throw ApiException.conflict("batches_in_flight",
                    inFlight + " batch(es) are awaiting approval or executing; finish or cancel them first");
        }
        return changeStatus(id, "CLOSED", reason);
    }

    private JsonNode changeStatus(UUID id, String status, String reason) {
        ObjectNode v = rows.object();
        v.put("status", status);
        if (reason == null) {
            v.putNull("status_reason");
        } else {
            v.put("status_reason", reason);
        }
        ObjectNode updated = rows.update(TABLE, id, v);
        events.publish("wallets.disbursement_program.status_changed", TABLE, updated);
        return updated;
    }
}
