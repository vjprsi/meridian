package io.meridian.routing.application;

import static io.meridian.routing.infrastructure.Rows.str;

import io.meridian.common.error.ApiException;
import io.meridian.routing.domain.BankCodes;
import io.meridian.routing.infrastructure.Rows;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Bank directory lookups (global directory, valid today): by BIC, by national clearing id, or derived from an IBAN. */
@Service
public class ParticipantService {
    /** Valid on the business date: binds the date twice. */
    private static final String VALID = " and (valid_from is null or valid_from <= ?::date)"
            + " and (valid_to is null or valid_to >= ?::date)";

    private final Rows rows;

    public ParticipantService(Rows rows) {
        this.rows = rows;
    }

    public void validate(JsonNode body) {
        String bic = str(body, "bic");
        if (bic != null && !BankCodes.isBic(bic)) {
            throw ApiException.unprocessable("invalid_bic", "bic must be an ISO 9362 BIC (8 or 11 characters)");
        }
    }

    @Transactional(readOnly = true)
    public ObjectNode lookup(JsonNode req) {
        String bic = upper(str(req, "bic"));
        String iban = BankCodes.normaliseIban(str(req, "iban"));
        String system = upper(str(req, "clearing_system"));
        String clearingId = str(req, "clearing_id");
        ObjectNode out = rows.object();
        Optional<ObjectNode> found;
        String derivedFrom;
        if (bic != null) {
            if (!BankCodes.isBic(bic)) {
                throw ApiException.unprocessable("invalid_bic", "bic must be an ISO 9362 BIC");
            }
            String bic8 = bic.substring(0, 8);
            LocalDate today = rows.today();
            found = rows.queryOne("select row_to_json(t)::text from rail_participant t where (bic = ? or bic = ? or bic = ?)"
                    + VALID + " order by (bic = ?) desc, length(bic) desc limit 1",
                    bic, bic8, bic8 + "XXX", today, today, bic);
            derivedFrom = "bic";
        } else if (iban != null) {
            Optional<String> err = BankCodes.ibanError(iban);
            if (err.isPresent()) {
                throw ApiException.unprocessable(err.get(), "iban is not valid (" + err.get() + ")");
            }
            BankCodes.IbanBank bank = BankCodes.bankOf(iban);
            out.put("iban_country", bank.country());
            out.put("bank_code", bank.bankCode());
            found = bank.bankCode() == null ? Optional.empty() : rows.queryOne("""
                    select row_to_json(t)::text from rail_participant t where country = ? and clearing_id = ?"""
                    + VALID + " order by (clearing_system = ?) desc limit 1",
                    bank.country(), bank.bankCode(), rows.today(), rows.today(), bank.clearingSystem());
            derivedFrom = "iban";
        } else if (clearingId != null) {
            found = rows.queryOne("select row_to_json(t)::text from rail_participant t where clearing_id = ?"
                    + " and (?::varchar is null or clearing_system = ?)" + VALID + " limit 1", clearingId, system, system,
                    rows.today(), rows.today());
            derivedFrom = "clearing_id";
        } else {
            throw ApiException.unprocessable("lookup_key_required", "Provide bic, iban or clearing_id");
        }
        out.put("derived_from", derivedFrom);
        out.put("found", found.isPresent());
        if (found.isPresent()) {
            ObjectNode p = found.get();
            out.set("participant", p);
            out.put("bic", str(p, "bic"));
            out.put("name", str(p, "name"));
            out.put("country", str(p, "country"));
            out.put("clearing_system", str(p, "clearing_system"));
            out.put("clearing_id", str(p, "clearing_id"));
            out.set("reachable_rails", p.has("reachable_rails") && !p.get("reachable_rails").isNull()
                    ? p.get("reachable_rails") : rows.json().createArrayNode());
        } else {
            out.set("reachable_rails", rows.json().createArrayNode());
        }
        return out;
    }

    private static String upper(String s) {
        return s == null || s.isBlank() ? null : s.trim().toUpperCase(Locale.ROOT);
    }
}
