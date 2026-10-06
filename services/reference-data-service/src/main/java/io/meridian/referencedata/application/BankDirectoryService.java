package io.meridian.referencedata.application;

import io.meridian.referencedata.domain.AccountNumbers;
import io.meridian.referencedata.domain.AccountNumbers.IbanCheck;
import io.meridian.referencedata.domain.AccountNumbers.Issue;
import io.meridian.referencedata.infrastructure.RefDataStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Account-number validation (IBAN mod-97 / domestic) and bank resolution from the bank directory. */
@Service
public class BankDirectoryService {
    private final RefDataStore store;
    private final JsonMapper json;

    public BankDirectoryService(RefDataStore store, JsonMapper json) {
        this.store = store;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public JsonNode validateAccount(String accountNumber, String country, String clearingCode) {
        String normalized = AccountNumbers.normalize(accountNumber);
        Optional<JsonNode> countryRow = store.country(country);
        Integer ibanLength = countryRow.map(c -> c.get("iban_length")).filter(n -> n != null && !n.isNull())
                .map(JsonNode::asInt).orElse(null);
        boolean ibanCountry = ibanLength != null || AccountNumbers.IBAN_LENGTHS.containsKey(country);
        List<Issue> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        ObjectNode out = json.createObjectNode();
        String bankCode = clearingCode == null || clearingCode.isBlank() ? null : AccountNumbers.normalize(clearingCode);
        String bicPrefix = null;
        if (AccountNumbers.looksLikeIban(normalized) || (ibanCountry && bankCode == null)) {
            IbanCheck check = AccountNumbers.checkIban(normalized, country, ibanLength);
            errors.addAll(check.issues());
            out.put("account_type", "IBAN");
            if (bankCode == null) {
                bankCode = check.clearingCode();
            }
            bicPrefix = check.bicBankCode();
        } else {
            errors.addAll(AccountNumbers.checkDomestic(normalized));
            out.put("account_type", "DOMESTIC");
            if (bankCode == null) {
                warnings.add("clearing_code_missing: the bank cannot be resolved without a national clearing code");
            }
        }
        if (countryRow.isEmpty()) {
            warnings.add("country_unknown: " + country + " is not in the country reference table");
        } else if (countryRow.get().path("is_sanctioned").asBoolean()) {
            warnings.add("country_sanctioned: " + country + " is a comprehensively sanctioned jurisdiction");
        }

        JsonNode bank = null;
        if (bankCode != null) {
            bank = store.banksByClearingCode(country, bankCode).stream().findFirst().orElse(null);
        }
        if (bank == null && bicPrefix != null) {
            bank = store.banksByBicPrefix(bicPrefix, country).stream().findFirst().orElse(null);
        }
        if (bank == null && clearingCode != null && AccountNumbers.isValidBic(clearingCode)) {
            bank = store.banksByBic(AccountNumbers.normalize(clearingCode)).stream().findFirst().orElse(null);
        }
        if (bank == null && errors.isEmpty() && (bankCode != null || bicPrefix != null)) {
            warnings.add("bank_not_found: no active bank in the directory for the account's bank code");
        }

        out.put("valid", errors.isEmpty());
        out.put("normalized_account_number", normalized);
        out.put("country", country);
        if (bankCode != null) {
            out.put("clearing_code", bankCode);
        }
        ArrayNode errs = out.putArray("errors");
        errors.forEach(e -> errs.addObject().put("code", e.code()).put("message", e.message()));
        ArrayNode warns = out.putArray("warnings");
        warnings.forEach(warns::add);
        if (bank != null) {
            ObjectNode b = out.putObject("bank");
            for (String f : List.of("id", "bic", "bank_name", "branch_name", "city", "clearing_system",
                    "national_clearing_code")) {
                if (bank.hasNonNull(f)) {
                    b.put(f, bank.get(f).asString().trim());
                }
            }
            if (bank.hasNonNull("rail_participation")) {
                b.set("rail_participation", bank.get("rail_participation"));
            }
            b.put("bic_valid", bank.hasNonNull("bic") && AccountNumbers.isValidBic(bank.get("bic").asString()));
        } else {
            out.putNull("bank");
        }
        return out;
    }
}
