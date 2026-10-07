package io.meridian.test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Service-level check of the internal data-lifecycle API (GAP-08) against a running service (Spring Boot test on a
 * random port, real PostgreSQL): a personal-data row of the customer is exported, erased (anonymised) and reachable
 * by archive count / extract; callers other than archive-service / customer-service are refused.
 */
public final class DataLifecycleCheck {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final int port;
    private final String schema;
    private final UUID tenant = UUID.randomUUID();
    private final UUID customer = UUID.randomUUID();

    public DataLifecycleCheck(int port, String schema) {
        this.port = port;
        this.schema = schema;
    }

    public UUID tenant() {
        return tenant;
    }

    public UUID customer() {
        return customer;
    }

    /** Direct database access as the owner (test data setup and verification), bound to this check's tenant. */
    public TestRows rows() {
        return new TestRows(schema, tenant);
    }

    /** POST to {@code /v1/internal/data-lifecycle/<op>} as {@code caller}; returns {status, body}. */
    public Response call(String op, Object body, String caller) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/internal/data-lifecycle/" + op))
                    .header("Content-Type", "application/json")
                    .header("X-Tenant-Id", tenant.toString())
                    .header("X-User", "dpo-test")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            if (caller != null) {
                b.header("X-Meridian-Service", caller);
            }
            HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(r.statusCode(), r.body() == null || r.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(r.body()));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** A response of the data-lifecycle API. */
    public record Response(int status, JsonNode body) {}

    /**
     * Runs the standard scenario on {@code table} for a row {@code rowId} of this check's customer whose pii column
     * {@code piiColumn} holds {@code piiValue}. Returns a description of the first failed expectation, or null.
     */
    public String verify(String table, UUID rowId, String piiColumn, String piiValue) {
        return verify(table, rowId, piiColumn, piiValue, "ANONYMISED");
    }

    /**
     * As {@link #verify(String, UUID, String, String)}; {@code expectedErasure} is {@code ANONYMISED}, or {@code RETAINED}
     * for append-only tables (the personal data must then still be there, and reported as retained).
     */
    public String verify(String table, UUID rowId, String piiColumn, String piiValue, String expectedErasure) {
        Response denied = call("export", Map.of("customer_id", customer.toString()), null);
        if (denied.status() != 403) {
            return "export without a calling service: expected 403, got " + denied.status() + " " + denied.body();
        }
        Response export = call("export", Map.of("customer_id", customer.toString()), "customer-service");
        if (export.status() != 200) {
            return "export: HTTP " + export.status() + " " + export.body();
        }
        JsonNode exported = export.body().path("tables").path(table);
        boolean found = false;
        for (JsonNode r : exported) {
            if (rowId.toString().equals(r.path("id").asString()) && piiValue.equals(r.path(piiColumn).asString())) {
                found = true;
            }
        }
        if (!found) {
            return "export does not contain " + table + " row " + rowId + ": " + export.body();
        }
        ObjectNode count = JSON.createObjectNode();
        count.put("table_name", table);
        count.put("created_before", LocalDate.now().plusDays(2).toString());
        count.putObject("record_selector").putArray("customer_ids").add(customer.toString());
        Response counted = call("count", count, "archive-service");
        if (counted.status() != 200 || counted.body().path("count").asLong() < 1) {
            return "count: " + counted.status() + " " + counted.body();
        }
        ObjectNode extract = JSON.createObjectNode();
        extract.put("table_name", table);
        extract.put("created_before", LocalDate.now().plusDays(2).toString());
        extract.put("limit", 1000);
        Response extracted = call("extract", extract, "archive-service");
        if (extracted.status() != 200 || !extracted.body().path("records").toString().contains(rowId.toString())) {
            return "extract: " + extracted.status() + " " + extracted.body();
        }
        ObjectNode erase = JSON.createObjectNode();
        erase.put("customer_id", customer.toString());
        erase.put("within_retention", false);
        Response erased = call("erase", erase, "customer-service");
        if (erased.status() != 200) {
            return "erase: HTTP " + erased.status() + " " + erased.body();
        }
        boolean anonymised = false;
        for (JsonNode t : erased.body().path("tables")) {
            if (table.equals(t.path("table").asString()) && expectedErasure.equals(t.path("action").asString())
                    && t.path("records").asLong() >= 1) {
                anonymised = true;
            }
        }
        if (!anonymised) {
            return "erase did not report " + expectedErasure + " for " + table + ": " + erased.body();
        }
        try (TestRows rows = rows()) {
            Object now = rows.value("select " + piiColumn + "::text from " + table + " where id = ?", rowId);
            if ("ANONYMISED".equals(expectedErasure) == piiValue.equals(now)) {
                return table + "." + piiColumn + " after erasure: " + now + " (expected " + expectedErasure + ")";
            }
        }
        return null;
    }
}
