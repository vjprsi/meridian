package io.meridian.disbursement.application;

import static io.meridian.disbursement.infrastructure.Rows.bool;
import static io.meridian.disbursement.infrastructure.Rows.dec;
import static io.meridian.disbursement.infrastructure.Rows.decOr0;
import static io.meridian.disbursement.infrastructure.Rows.integer;
import static io.meridian.disbursement.infrastructure.Rows.str;
import static io.meridian.disbursement.infrastructure.Rows.uuid;

import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.disbursement.config.DisbursementProperties;
import io.meridian.disbursement.domain.BeneficiaryCsvParser;
import io.meridian.disbursement.domain.Lifecycle;
import io.meridian.disbursement.domain.Msisdn;
import io.meridian.disbursement.domain.NameMatcher;
import io.meridian.disbursement.infrastructure.Rows;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Disbursement batches: file upload and parsing, beneficiary validation, multi-level maker-checker approval and
 * execution as wallet-service payouts from the program funding wallet (one idempotent wallet transaction per line and
 * attempt), retries and the execution report. Execution commits each line on its own so a partial run is never lost.
 */
@Service
public class BatchService {
    static final String TABLE = "disbursement_batch";
    static final String LINES = "disbursement_beneficiary";
    static final String APPROVALS = "disbursement_batch_approval";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String REF_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final Rows rows;
    private final Events events;
    private final WalletPort wallets;
    private final TransactionTemplate tx;
    private final DisbursementProperties props;
    private final Approvals approvals;

    /**
     * Approval resource type for disbursement batches in workflow-service (G11). Each approval level is its own
     * request with action {@code approve-level-<n>}: see {@link BatchApprovalHandler}.
     */
    public static final String APPROVAL_RESOURCE = "disbursement_batch";

    public BatchService(Rows rows, Events events, WalletPort wallets, TransactionTemplate tx, DisbursementProperties props,
                        Approvals approvals) {
        this.approvals = approvals;
        this.rows = rows;
        this.events = events;
        this.wallets = wallets;
        this.tx = tx;
        this.props = props;
    }

    // ------------------------------------------------------------------ create / upload

    @Transactional
    public JsonNode create(JsonNode req) {
        UUID programId = uuid(req, "disbursement_program_id");
        ObjectNode program = rows.get(ProgramService.TABLE, programId);
        requireActive(program);
        boolean inPeriod = rows.scalar("""
                select (start_date is null or start_date <= current_date) and (end_date is null or end_date >= current_date)
                from disbursement_program where id = ?""", Boolean.class, programId).orElse(false);
        if (!inPeriod) {
            throw ApiException.unprocessable("program_not_in_period", "Today is outside the program start and end dates");
        }
        if (!str(program, "currency").equals(str(req, "currency"))) {
            throw ApiException.unprocessable("currency_mismatch", "Batch currency must be the program currency " + str(program, "currency"));
        }
        ObjectNode v = rows.columns(TABLE, req);
        String day = rows.scalar("select to_char(current_date, 'YYYYMMDD')", String.class).orElseThrow();
        v.put("batch_reference", "DB" + day + "-" + randomCode(8));
        v.put("status", "DRAFT");
        v.put("approvals_required", integer(program, "approval_levels"));
        v.put("approvals_received", 0);
        v.put("total_count", 0);
        v.put("total_amount", "0");
        v.put("valid_count", 0);
        v.put("valid_amount", "0");
        v.put("invalid_count", 0);
        v.put("duplicate_count", 0);
        v.put("paid_count", 0);
        v.put("failed_count", 0);
        v.put("paid_amount", "0");
        v.put("fee_amount", "0");
        return rows.insert(TABLE, v);
    }

    @Transactional
    public JsonNode upload(UUID id, String content, String fileName, UUID documentId) {
        ObjectNode batch = rows.lock(TABLE, id);
        String status = str(batch, "status");
        if (!"DRAFT".equals(status) && !"VALIDATED".equals(status)) {
            throw ApiException.conflict("batch_not_editable", "Files can only be uploaded to a DRAFT or VALIDATED batch (is " + status + ")");
        }
        String sha = sha256(content == null ? "" : content);
        long reused = rows.scalar("""
                select count(*) from disbursement_batch where disbursement_program_id = ? and id <> ? and file_sha256 = ?
                and status not in ('CANCELLED', 'REJECTED')""", Long.class, uuid(batch, "disbursement_program_id"), id, sha)
                .orElse(0L);
        if (reused > 0) {
            throw ApiException.conflict("duplicate_file", "This file was already uploaded to another batch of the program");
        }
        List<BeneficiaryCsvParser.Row> parsed;
        try {
            parsed = new BeneficiaryCsvParser(props.defaultCallingCode(), props.maxLines()).parse(content);
        } catch (BeneficiaryCsvParser.FileFormatException e) {
            throw ApiException.unprocessable("invalid_file", e.getMessage());
        }
        rows.execute("delete from disbursement_beneficiary where disbursement_batch_id = ?", id);
        String currency = str(batch, "currency");
        List<ObjectNode> lines = new ArrayList<>();
        for (BeneficiaryCsvParser.Row r : parsed) {
            ObjectNode l = rows.object();
            l.put("id", io.meridian.common.id.Ids.newId().toString());
            l.put("disbursement_batch_id", id.toString());
            l.put("line_no", r.lineNo());
            putOrNull(l, "beneficiary_reference", r.beneficiaryReference());
            putOrNull(l, "msisdn", r.msisdn());
            putOrNull(l, "national_id", r.nationalId());
            putOrNull(l, "full_name", r.fullName());
            l.put("amount", (r.amount() == null ? BigDecimal.ZERO : r.amount()).toPlainString());
            l.put("currency", currency);
            putOrNull(l, "narrative", r.narrative());
            l.put("validation_status", r.valid() ? "PENDING" : "INVALID");
            putOrNull(l, "validation_message", r.error());
            l.put("payout_status", "PENDING");
            l.put("attempt_count", 0);
            lines.add(l);
        }
        for (int i = 0; i < lines.size(); i += 1000) {
            rows.insertMany(LINES, lines.subList(i, Math.min(lines.size(), i + 1000)));
        }
        ObjectNode v = rows.object();
        v.put("status", "DRAFT");
        v.put("file_sha256", sha);
        putOrNull(v, "file_name", fileName);
        putOrNull(v, "file_document_id", documentId == null ? null : documentId.toString());
        rows.update(TABLE, id, v);
        return recomputeTotals(id);
    }

    // ------------------------------------------------------------------ validate

    @Transactional
    public JsonNode validate(UUID id) {
        ObjectNode batch = rows.lock(TABLE, id);
        Lifecycle.batch(str(batch, "status"), "VALIDATING");
        ObjectNode program = rows.get(ProgramService.TABLE, uuid(batch, "disbursement_program_id"));
        requireActive(program);
        List<ObjectNode> lines = rows.query("""
                select row_to_json(t)::text from disbursement_beneficiary t where disbursement_batch_id = ? order by line_no""", id);
        if (lines.isEmpty()) {
            throw ApiException.unprocessable("empty_batch", "The batch has no beneficiary lines; upload a file first");
        }
        String currency = str(batch, "currency");
        BigDecimal cap = dec(program, "max_amount_per_beneficiary");
        Integer minTier = integer(program, "require_kyc_tier");
        boolean nameMatch = bool(program, "require_name_match");
        boolean autoCreate = bool(program, "auto_create_wallets");
        Map<String, Integer> seen = new HashMap<>();
        for (ObjectNode line : lines) {
            Verdict verdict = verdict(line, currency, cap, minTier, nameMatch, autoCreate, seen);
            ObjectNode v = rows.object();
            v.put("validation_status", verdict.status());
            putOrNull(v, "validation_message", verdict.message());
            putOrNull(v, "wallet_id", verdict.walletId() == null ? null : verdict.walletId().toString());
            putOrNull(v, "matched_name", verdict.matchedName());
            v.put("payout_status", "PENDING");
            rows.update(LINES, uuid(line, "id"), v);
        }
        ObjectNode s = rows.object();
        s.put("status", "VALIDATED");
        rows.update(TABLE, id, s);
        JsonNode updated = recomputeTotals(id);
        events.publish("wallets.disbursement_batch.validated", TABLE, updated);
        return updated;
    }

    record Verdict(String status, String message, UUID walletId, String matchedName) {}

    private Verdict verdict(ObjectNode line, String currency, BigDecimal cap, Integer minTier, boolean nameMatch,
                            boolean autoCreate, Map<String, Integer> seen) {
        String message = str(line, "validation_message");
        if ("INVALID".equals(str(line, "validation_status")) && message != null
                && message.startsWith(BeneficiaryCsvParser.ROW_ERROR_PREFIX)) {
            return new Verdict("INVALID", message, null, null);
        }
        String msisdn = str(line, "msisdn");
        BigDecimal amount = decOr0(line, "amount");
        if (!Msisdn.isE164(msisdn)) {
            return new Verdict("INVALID", "msisdn is missing or not a valid E.164 number", null, null);
        }
        if (amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2) {
            return new Verdict("INVALID", "amount must be positive with at most 2 decimals", null, null);
        }
        if (!currency.equals(str(line, "currency"))) {
            return new Verdict("INVALID", "currency " + str(line, "currency") + " differs from the batch currency " + currency, null, null);
        }
        int lineNo = integer(line, "line_no");
        List<String> keys = new ArrayList<>();
        keys.add("msisdn " + msisdn);
        if (str(line, "national_id") != null) {
            keys.add("national_id " + str(line, "national_id").strip().toUpperCase(Locale.ROOT));
        }
        if (str(line, "beneficiary_reference") != null) {
            keys.add("beneficiary_reference " + str(line, "beneficiary_reference").strip().toUpperCase(Locale.ROOT));
        }
        for (String k : keys) {
            Integer first = seen.get(k);
            if (first != null) {
                return new Verdict("DUPLICATE", "Duplicate of line " + first + " (same " + k.substring(0, k.indexOf(' ')) + ")", null, null);
            }
        }
        keys.forEach(k -> seen.put(k, lineNo));
        if (cap != null && amount.compareTo(cap) > 0) {
            return new Verdict("INVALID", "amount exceeds the program cap of " + cap.stripTrailingZeros().toPlainString(), null, null);
        }
        Optional<JsonNode> found = wallets.lookup(msisdn, currency);
        JsonNode wallet;
        if (found.isEmpty()) {
            if (!autoCreate) {
                return new Verdict("INVALID", "No wallet is registered for " + msisdn, null, null);
            }
            if (minTier != null && minTier > 0) {
                return new Verdict("INVALID", "No wallet is registered for " + msisdn
                        + " and an auto-created Tier 0 wallet is below the required tier " + minTier, null, null);
            }
            String name = str(line, "full_name") == null ? msisdn : str(line, "full_name");
            wallet = wallets.openTierZeroWallet("dsb-wallet-" + str(line, "id"), msisdn, name, currency);
        } else {
            wallet = found.get();
        }
        UUID walletId = uuid(wallet, "id");
        String walletName = str(wallet, "display_name");
        if (!"ACTIVE".equals(str(wallet, "status"))) {
            return new Verdict("INVALID", "Wallet is " + str(wallet, "status"), walletId, walletName);
        }
        if (!currency.equals(str(wallet, "currency"))) {
            return new Verdict("INVALID", "Wallet currency " + str(wallet, "currency") + " differs from " + currency, walletId, walletName);
        }
        Integer tier = integer(wallet, "kyc_tier");
        if (minTier != null && (tier == null || tier < minTier)) {
            return new Verdict("INVALID", "Wallet KYC tier " + tier + " is below the required tier " + minTier, walletId, walletName);
        }
        if (nameMatch && found.isPresent()) {
            if (str(line, "full_name") == null) {
                return new Verdict("INVALID", "full_name is required for name matching", walletId, walletName);
            }
            if (!NameMatcher.matches(str(line, "full_name"), walletName)) {
                return new Verdict("NAME_MISMATCH", "Name on the wallet does not match the file", walletId, walletName);
            }
        }
        return new Verdict("VALID", null, walletId, walletName);
    }

    // ------------------------------------------------------------------ approval workflow

    @Transactional
    public JsonNode submit(UUID id) {
        ObjectNode batch = rows.lock(TABLE, id);
        Lifecycle.batch(str(batch, "status"), "PENDING_APPROVAL");
        if (integer(batch, "valid_count") == null || integer(batch, "valid_count") == 0) {
            throw ApiException.unprocessable("no_valid_lines", "The batch has no valid beneficiary lines");
        }
        requireActive(rows.get(ProgramService.TABLE, uuid(batch, "disbursement_program_id")));
        ObjectNode v = rows.object();
        v.put("status", "PENDING_APPROVAL");
        v.put("submitted_by", TenantContext.principal());
        v.put("approvals_received", 0);
        rows.update(TABLE, id, v);
        rows.execute("update disbursement_batch set submitted_at = now() where id = ?", id);
        ObjectNode updated = rows.get(TABLE, id);
        events.publish("wallets.disbursement_batch.submitted", TABLE, updated);
        requestLevel(updated, 1);
        return updated;
    }

    /** The workflow-service action for one approval level. */
    public static String levelAction(int level) {
        return "approve-level-" + level;
    }

    /**
     * Central maker-checker: opens the workflow-service request for one approval level of the submitted batch. The
     * submission time and the level fingerprint the request, so a decision for an earlier submission or level is
     * stale. Approvers see who approved the earlier levels (they cannot decide again).
     */
    private void requestLevel(ObjectNode batch, int level) {
        UUID id = uuid(batch, "id");
        int required = integer(batch, "approvals_required");
        String submittedAt = str(batch, "submitted_at");
        BigDecimal amount = decOr0(batch, "valid_amount").stripTrailingZeros();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("batch_reference", str(batch, "batch_reference"));
        payload.put("disbursement_program_id", str(batch, "disbursement_program_id"));
        payload.put("currency", str(batch, "currency"));
        payload.put("valid_count", integer(batch, "valid_count"));
        payload.put("valid_amount", amount.toPlainString());
        payload.put("file_name", str(batch, "file_name"));
        payload.put("file_sha256", str(batch, "file_sha256"));
        payload.put("submitted_by", str(batch, "submitted_by"));
        payload.put("submitted_at", submittedAt);
        payload.put("level", level);
        payload.put("approvals_required", required);
        payload.put("earlier_approvers", rows.query("""
                select row_to_json(t)::text from (select level, decided_by from disbursement_batch_approval
                where disbursement_batch_id = ? and decision = 'APPROVED' order by level) t""", id)
                .stream().map(r -> str(r, "decided_by")).toList());
        approvals.request(ApprovalSpec.of(APPROVAL_RESOURCE, id, levelAction(level),
                        "Disbursement batch " + str(batch, "batch_reference") + " - approval " + level + " of " + required,
                        payload).withAmount(amount, str(batch, "currency")),
                "submit:" + id + ":" + submittedAt + ":level:" + level);
    }

    /**
     * True while the batch is PENDING_APPROVAL for the same submission ({@code submittedAt}) and waits for exactly
     * this approval level (levels already approved = level - 1).
     */
    public boolean awaitsLevel(UUID id, String submittedAt, int level) {
        return rows.find(TABLE, id)
                .filter(b -> "PENDING_APPROVAL".equals(str(b, "status")))
                .filter(b -> submittedAt == null || submittedAt.equals(str(b, "submitted_at")))
                .filter(b -> integer(b, "approvals_received") != null && integer(b, "approvals_received") == level - 1)
                .isPresent();
    }

    /** One approval level; the final level checks the funding and executes the batch. */
    public JsonNode approve(UUID id, String comment) {
        boolean fin = Boolean.TRUE.equals(tx.execute(s -> {
            ObjectNode batch = rows.lock(TABLE, id);
            Lifecycle.batch(str(batch, "status"), "APPROVED");
            String principal = TenantContext.principal();
            checkFourEyes(batch, id, principal);
            int level = integer(batch, "approvals_received") + 1;
            int required = integer(batch, "approvals_required");
            boolean last = level >= required;
            ObjectNode program = rows.get(ProgramService.TABLE, uuid(batch, "disbursement_program_id"));
            if (last) {
                requireActive(program);
                BigDecimal available = wallets.available(uuid(program, "funding_wallet_id"));
                BigDecimal needed = decOr0(batch, "valid_amount");
                if (available.compareTo(needed) < 0) {
                    throw ApiException.unprocessable("insufficient_funding", "Funding wallet available balance "
                            + available.toPlainString() + " is below the batch total " + needed.toPlainString());
                }
            }
            insertDecision(id, level, "APPROVED", principal, comment);
            ObjectNode v = rows.object();
            v.put("approvals_received", level);
            if (last) {
                v.put("status", "APPROVED");
                v.put("approved_by", principal);
            }
            ObjectNode updated = rows.update(TABLE, id, v);
            if (last) {
                events.publish("wallets.disbursement_batch.approved", TABLE, updated);
            } else {
                requestLevel(updated, level + 1);
            }
            approvals.cancelOpen(APPROVAL_RESOURCE, id, levelAction(level), "level " + level + " approved directly");
            return last;
        }));
        return fin ? execute(id, false) : rows.get(TABLE, id);
    }

    @Transactional
    public JsonNode reject(UUID id, String reason) {
        ObjectNode batch = rows.lock(TABLE, id);
        Lifecycle.batch(str(batch, "status"), "REJECTED");
        String principal = TenantContext.principal();
        checkFourEyes(batch, id, principal);
        insertDecision(id, integer(batch, "approvals_received") + 1, "REJECTED", principal, reason);
        ObjectNode v = rows.object();
        v.put("status", "REJECTED");
        v.put("rejection_reason", reason);
        ObjectNode updated = rows.update(TABLE, id, v);
        events.publish("wallets.disbursement_batch.rejected", TABLE, updated);
        approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "rejected directly");
        return updated;
    }

    @Transactional
    public JsonNode cancel(UUID id, String reason) {
        ObjectNode batch = rows.lock(TABLE, id);
        Lifecycle.batch(str(batch, "status"), "CANCELLED");
        ObjectNode v = rows.object();
        v.put("status", "CANCELLED");
        putOrNull(v, "cancel_reason", reason);
        ObjectNode updated = rows.update(TABLE, id, v);
        events.publish("wallets.disbursement_batch.cancelled", TABLE, updated);
        approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "batch cancelled");
        return updated;
    }

    private void checkFourEyes(ObjectNode batch, UUID id, String principal) {
        if (principal.equals(str(batch, "submitted_by"))) {
            throw ApiException.unprocessable("four_eyes_violation", "The submitter of a batch cannot approve or reject it");
        }
        long already = rows.scalar("select count(*) from disbursement_batch_approval where disbursement_batch_id = ? and decided_by = ?",
                Long.class, id, principal).orElse(0L);
        if (already > 0) {
            throw ApiException.unprocessable("four_eyes_violation", principal + " already decided on this batch; each level needs a different approver");
        }
    }

    private void insertDecision(UUID batchId, int level, String decision, String principal, String comment) {
        ObjectNode a = rows.object();
        a.put("disbursement_batch_id", batchId.toString());
        a.put("level", level);
        a.put("decision", decision);
        a.put("decided_by", principal);
        putOrNull(a, "comment", comment);
        rows.insert(APPROVALS, a);
    }

    // ------------------------------------------------------------------ execution

    public JsonNode retryFailed(UUID id) {
        tx.executeWithoutResult(s -> {
            ObjectNode batch = rows.lock(TABLE, id);
            String status = str(batch, "status");
            if (!List.of("PARTIALLY_COMPLETED", "FAILED", "PROCESSING").contains(status)) {
                throw ApiException.conflict("invalid_transition", "Only FAILED, PARTIALLY_COMPLETED or interrupted batches can be retried (is " + status + ")");
            }
            long open = rows.scalar("""
                    select count(*) from disbursement_beneficiary where disbursement_batch_id = ?
                    and validation_status = 'VALID' and payout_status in ('FAILED', 'PENDING')""", Long.class, id).orElse(0L);
            if (open == 0) {
                throw ApiException.unprocessable("nothing_to_retry", "The batch has no failed lines");
            }
            requireActive(rows.get(ProgramService.TABLE, uuid(batch, "disbursement_program_id")));
        });
        return execute(id, true);
    }

    private record Run(UUID fundingWalletId, String txnType, String currency, String batchReference, List<ObjectNode> lines) {}

    private JsonNode execute(UUID id, boolean retry) {
        Run run = tx.execute(s -> {
            ObjectNode batch = rows.lock(TABLE, id);
            Lifecycle.batch(str(batch, "status"), "PROCESSING");
            ObjectNode program = rows.get(ProgramService.TABLE, uuid(batch, "disbursement_program_id"));
            ObjectNode v = rows.object();
            v.put("status", "PROCESSING");
            rows.update(TABLE, id, v);
            if (!retry) {
                rows.execute("update disbursement_batch set started_at = now() where id = ?", id);
                rows.execute("""
                        update disbursement_beneficiary set payout_status = 'SKIPPED'
                        where disbursement_batch_id = ? and validation_status <> 'VALID' and payout_status = 'PENDING'""", id);
            }
            List<ObjectNode> lines = rows.query("""
                    select row_to_json(t)::text from disbursement_beneficiary t where disbursement_batch_id = ?
                    and validation_status = 'VALID' and payout_status in ('PENDING', 'FAILED') order by line_no""", id);
            return new Run(uuid(program, "funding_wallet_id"), Lifecycle.txnType(str(program, "program_type")),
                    str(batch, "currency"), str(batch, "batch_reference"), lines);
        });
        for (ObjectNode line : run.lines()) {
            payLine(id, line, run);
        }
        return tx.execute(s -> {
            rows.lock(TABLE, id);
            ObjectNode totals = recomputeTotals(id);
            String outcome = Lifecycle.outcome(integer(totals, "paid_count"), integer(totals, "failed_count"));
            ObjectNode v = rows.object();
            v.put("status", outcome);
            rows.update(TABLE, id, v);
            rows.execute("update disbursement_batch set completed_at = now() where id = ?", id);
            ObjectNode updated = rows.get(TABLE, id);
            events.publish("wallets.disbursement_batch.completed", TABLE, updated);
            return updated;
        });
    }

    /**
     * Pays one line. The attempt number only advances when an attempt has a recorded outcome, so an interrupted attempt
     * is resumed with the same Idempotency-Key and wallet-service returns the original transaction instead of paying twice.
     */
    private void payLine(UUID batchId, ObjectNode line, Run run) {
        UUID lineId = uuid(line, "id");
        int attempt = integer(line, "attempt_count") + 1;
        String key = "dbn-" + lineId + "-" + attempt;
        JsonNode txn = null;
        String failure = null;
        try {
            String narrative = str(line, "narrative") == null ? run.batchReference() : str(line, "narrative");
            txn = wallets.payout(key, new WalletPort.Payout(run.txnType(), run.fundingWalletId(), uuid(line, "wallet_id"),
                    decOr0(line, "amount"), run.currency(), lineId, narrative));
            if (!"COMPLETED".equals(str(txn, "status"))) {
                String code = str(txn, "failure_code");
                failure = "Payout " + str(txn, "status") + (code == null ? "" : ": " + code);
            }
        } catch (ApiException e) {
            failure = e.code() + ": " + e.getMessage();
        }
        JsonNode result = txn;
        String reason = failure == null ? null : (failure.length() > 255 ? failure.substring(0, 255) : failure);
        tx.executeWithoutResult(s -> {
            ObjectNode v = rows.object();
            v.put("attempt_count", attempt);
            if (reason == null) {
                v.put("payout_status", "PAID");
                v.put("wallet_transaction_id", str(result, "id"));
                v.putNull("failure_reason");
                ObjectNode updated = rows.update(LINES, lineId, v);
                rows.execute("update disbursement_beneficiary set paid_at = coalesce(?::timestamptz, now()) where id = ?",
                        str(result, "completed_at"), lineId);
                BigDecimal fee = decOr0(result, "fee_amount");
                if (fee.signum() != 0) {
                    rows.execute("update disbursement_batch set fee_amount = coalesce(fee_amount, 0) + ? where id = ?", fee, batchId);
                }
                updated = rows.get(LINES, lineId);
                events.publish("wallets.disbursement_beneficiary.paid", LINES, updated);
            } else {
                v.put("payout_status", "FAILED");
                v.put("failure_reason", reason);
                if (result != null && str(result, "id") != null) {
                    v.put("wallet_transaction_id", str(result, "id"));
                }
                ObjectNode updated = rows.update(LINES, lineId, v);
                events.publish("wallets.disbursement_beneficiary.failed", LINES, updated);
            }
        });
    }

    // ------------------------------------------------------------------ totals / report

    /** Recomputes the line counters and amounts of a batch from its lines. */
    public ObjectNode recomputeTotals(UUID id) {
        ObjectNode t = rows.queryOne("""
                select row_to_json(x)::text from (
                  select count(*) as total_count, coalesce(sum(amount), 0) as total_amount,
                    count(*) filter (where validation_status = 'VALID') as valid_count,
                    coalesce(sum(amount) filter (where validation_status = 'VALID'), 0) as valid_amount,
                    count(*) filter (where validation_status in ('INVALID', 'NAME_MISMATCH')) as invalid_count,
                    count(*) filter (where validation_status = 'DUPLICATE') as duplicate_count,
                    count(*) filter (where payout_status = 'PAID') as paid_count,
                    count(*) filter (where payout_status = 'FAILED') as failed_count,
                    coalesce(sum(amount) filter (where payout_status = 'PAID'), 0) as paid_amount
                  from disbursement_beneficiary where disbursement_batch_id = ?) x""", id).orElseThrow();
        return rows.update(TABLE, id, t);
    }

    @Transactional(readOnly = true)
    public String report(UUID id) {
        rows.get(TABLE, id);
        List<ObjectNode> lines = rows.query("""
                select row_to_json(t)::text from disbursement_beneficiary t where disbursement_batch_id = ? order by line_no""", id);
        StringBuilder sb = new StringBuilder("line_no,beneficiary_reference,msisdn,full_name,matched_name,amount,currency,"
                + "validation_status,validation_message,payout_status,attempts,wallet_transaction_id,paid_at,failure_reason\r\n");
        for (ObjectNode l : lines) {
            sb.append(String.join(",",
                    csv(str(l, "line_no")), csv(str(l, "beneficiary_reference")), csv(str(l, "msisdn")),
                    csv(str(l, "full_name")), csv(str(l, "matched_name")), csv(decOr0(l, "amount").setScale(2, java.math.RoundingMode.HALF_EVEN).toPlainString()),
                    csv(str(l, "currency")), csv(str(l, "validation_status")), csv(str(l, "validation_message")),
                    csv(str(l, "payout_status")), csv(str(l, "attempt_count")), csv(str(l, "wallet_transaction_id")),
                    csv(str(l, "paid_at")), csv(str(l, "failure_reason")))).append("\r\n");
        }
        return sb.toString();
    }

    static String csv(String v) {
        if (v == null) {
            return "";
        }
        // neutralise spreadsheet formula injection, then quote per RFC 4180
        String s = !v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0 && !v.matches("^[+-]?\\d+(\\.\\d+)?$") ? "'" + v : v;
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    // ------------------------------------------------------------------ helpers

    static void requireActive(JsonNode program) {
        if (!"ACTIVE".equals(str(program, "status"))) {
            throw ApiException.unprocessable("program_not_active", "The disbursement program is " + str(program, "status"));
        }
    }

    private static void putOrNull(ObjectNode n, String f, String v) {
        if (v == null) {
            n.putNull(f);
        } else {
            n.put(f, v);
        }
    }

    private static String randomCode(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(REF_CHARS.charAt(RANDOM.nextInt(REF_CHARS.length())));
        }
        return sb.toString();
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
