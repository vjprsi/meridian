package io.meridian.disbursement.application;

import static io.meridian.disbursement.infrastructure.Rows.dec;
import static io.meridian.disbursement.infrastructure.Rows.integer;
import static io.meridian.disbursement.infrastructure.Rows.str;
import static io.meridian.disbursement.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.disbursement.domain.Lifecycle;
import io.meridian.disbursement.infrastructure.Rows;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Manual maintenance of beneficiary lines; only while the batch is a DRAFT, and every change needs re-validation. */
@Service
public class BeneficiaryService {
    private final Rows rows;
    private final BatchService batches;

    public BeneficiaryService(Rows rows, BatchService batches) {
        this.rows = rows;
        this.batches = batches;
    }

    @Transactional
    public JsonNode create(JsonNode req) {
        UUID batchId = uuid(req, "disbursement_batch_id");
        ObjectNode batch = editableBatch(batchId);
        checkLine(req, batch);
        long clash = rows.scalar("select count(*) from disbursement_beneficiary where disbursement_batch_id = ? and line_no = ?",
                Long.class, batchId, integer(req, "line_no")).orElse(0L);
        if (clash > 0) {
            throw ApiException.conflict("duplicate_line", "Line " + integer(req, "line_no") + " already exists in the batch");
        }
        ObjectNode v = rows.columns(BatchService.LINES, req);
        v.put("validation_status", "PENDING");
        v.put("payout_status", "PENDING");
        v.put("attempt_count", 0);
        ObjectNode row = rows.insert(BatchService.LINES, v);
        batches.recomputeTotals(batchId);
        return row;
    }

    @Transactional
    public JsonNode update(UUID id, String ifMatch, JsonNode req) {
        ObjectNode line = rows.lock(BatchService.LINES, id);
        if (integer(line, "row_version") != version(ifMatch)) {
            throw ApiException.conflict("version_conflict", "If-Match does not match the current version; reload and retry");
        }
        UUID batchId = uuid(line, "disbursement_batch_id");
        if (req.hasNonNull("disbursement_batch_id") && !batchId.equals(uuid(req, "disbursement_batch_id"))) {
            throw ApiException.unprocessable("field_not_updatable", "A line cannot be moved to another batch");
        }
        ObjectNode batch = editableBatch(batchId);
        ObjectNode merged = line.deepCopy();
        req.propertyNames().forEach(n -> {
            if (!req.get(n).isNull()) {
                merged.set(n, req.get(n));
            }
        });
        checkLine(merged, batch);
        if (req.hasNonNull("line_no") && integer(req, "line_no").intValue() != integer(line, "line_no").intValue()) {
            long clash = rows.scalar("select count(*) from disbursement_beneficiary where disbursement_batch_id = ? and line_no = ?",
                    Long.class, batchId, integer(req, "line_no")).orElse(0L);
            if (clash > 0) {
                throw ApiException.conflict("duplicate_line", "Line " + integer(req, "line_no") + " already exists in the batch");
            }
        }
        ObjectNode v = rows.columns(BatchService.LINES, req);
        v.remove("disbursement_batch_id");
        v.put("validation_status", "PENDING");
        v.putNull("validation_message");
        v.putNull("wallet_id");
        v.putNull("matched_name");
        ObjectNode row = rows.update(BatchService.LINES, id, v);
        batches.recomputeTotals(batchId);
        return row;
    }

    private ObjectNode editableBatch(UUID batchId) {
        ObjectNode batch = rows.lock(BatchService.TABLE, batchId);
        if (!Lifecycle.EDITABLE_BATCH.contains(str(batch, "status"))) {
            throw ApiException.conflict("batch_not_editable",
                    "Beneficiary lines can only be changed while the batch is DRAFT (is " + str(batch, "status") + ")");
        }
        return batch;
    }

    private static void checkLine(JsonNode line, JsonNode batch) {
        if (!str(batch, "currency").equals(str(line, "currency"))) {
            throw ApiException.unprocessable("currency_mismatch", "Line currency must be the batch currency " + str(batch, "currency"));
        }
        BigDecimal amount = dec(line, "amount");
        if (amount == null || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2) {
            throw ApiException.unprocessable("invalid_amount", "amount must be positive with at most 2 decimals");
        }
        Integer lineNo = integer(line, "line_no");
        if (lineNo == null || lineNo < 1) {
            throw ApiException.unprocessable("invalid_line_no", "line_no must be 1 or more");
        }
    }

    static int version(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw ApiException.badRequest("if_match_required", "If-Match header with the current row_version is required");
        }
        String v = ifMatch.strip().replace("W/", "").replace("\"", "");
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("invalid_if_match", "If-Match must be the row_version number");
        }
    }
}
