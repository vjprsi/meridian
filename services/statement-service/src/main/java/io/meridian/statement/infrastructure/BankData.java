package io.meridian.statement.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Reads from other services through {@link ServiceClient}: deposit accounts and interest capitalisations from
 * deposit-service, balances, entries and journal entries from ledger-service; registers generated files with
 * document-service.
 */
@Component
public class BankData {
    static final String DEPOSITS = "deposit-service";
    static final String LEDGER = "ledger-service";
    static final String DOCUMENTS = "document-service";
    private static final int MAX_PAGES = 500;

    private final ServiceClient client;

    public BankData(ServiceClient client) {
        this.client = client;
    }

    /** The deposit account, or 422 account_not_found when deposit-service does not know it. */
    public JsonNode account(UUID accountId) {
        try {
            return client.get(DEPOSITS, "/v1/deposit-accounts/" + accountId);
        } catch (ApiException e) {
            if (e.status() == 404) {
                throw ApiException.unprocessable("account_not_found", "Deposit account " + accountId + " does not exist");
            }
            throw e;
        }
    }

    /**
     * Registers a generated file with document-service (owner = the deposit account) and returns the document id.
     * The idempotency key is derived from the statement and its checksum, so a retry never registers twice.
     */
    public UUID registerDocument(UUID accountId, String documentType, String fileName, String contentType,
                                 String sha256, String sourceRef, String idempotencyKey) {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("owner_type", "ACCOUNT");
        body.put("owner_id", accountId.toString());
        body.put("document_type", documentType);
        body.put("file_name", fileName);
        body.put("content_type", contentType);
        body.put("classification", "CONFIDENTIAL");
        body.put("uploaded_via", "SYSTEM");
        body.put("metadata", java.util.Map.of("sha256", sha256, "source", sourceRef));
        JsonNode doc = client.post(DOCUMENTS, "/v1/documents", body, idempotencyKey);
        return doc == null || !doc.hasNonNull("id") ? null : UUID.fromString(doc.get("id").asString());
    }

    public List<JsonNode> accountsOfCustomer(UUID customerId, String currency) {
        List<JsonNode> out = new ArrayList<>();
        pages("/v1/deposit-accounts?customer_id=" + customerId + "&currency=" + currency, DEPOSITS, out::add);
        return out;
    }

    public List<JsonNode> capitalisations(UUID accountId) {
        List<JsonNode> out = new ArrayList<>();
        pages("/v1/interest-capitalisations?deposit_account_id=" + accountId, DEPOSITS, out::add);
        return out;
    }

    public BigDecimal balanceAt(UUID ledgerAccountId, OffsetDateTime at) {
        JsonNode b = client.get(LEDGER, "/v1/ledger-accounts/" + ledgerAccountId + "/balance-at?at=" + enc(at.toString()));
        return new BigDecimal(b.path("posted_balance").asString("0"));
    }

    /** Ledger entries booked in [from, to), all pages. */
    public List<JsonNode> entries(UUID ledgerAccountId, OffsetDateTime from, OffsetDateTime to) {
        List<JsonNode> out = new ArrayList<>();
        pages("/v1/ledger-accounts/" + ledgerAccountId + "/entries?from=" + enc(from.toString()) + "&to=" + enc(to.toString()),
                LEDGER, out::add);
        return out;
    }

    public JsonNode journalEntry(UUID id) {
        return client.get(LEDGER, "/v1/journal-entries/" + id);
    }

    private void pages(String basePath, String service, Consumer<JsonNode> sink) {
        String cursor = null;
        int n = 0;
        do {
            String path = basePath + (basePath.contains("?") ? "&" : "?") + "limit=200"
                    + (cursor == null ? "" : "&cursor=" + enc(cursor));
            JsonNode page = client.get(service, path);
            page.path("data").forEach(sink);
            cursor = page.path("has_more").asBoolean(false) ? page.path("next_cursor").asString(null) : null;
        } while (cursor != null && ++n < MAX_PAGES);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
