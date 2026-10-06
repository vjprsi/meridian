package io.meridian.regulatoryreporting.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.regulatoryreporting.domain.CrsXml;
import io.meridian.regulatoryreporting.domain.ReportRenderer;
import io.meridian.regulatoryreporting.infrastructure.Store;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * CRS / FATCA reportable account records built from customer tax residency (customer-service) and deposit accounts
 * (deposit-service, year-end balance from ledger-service). Records are upserted per regime, year, account and holder
 * so the generation can be repeated. CRS reports holders tax resident outside the reporting jurisdiction (US persons
 * are reported under FATCA instead); holders without a declared tax residence are reported as undocumented to the
 * reporting jurisdiction. FATCA reports US persons.
 */
@Service
public class CrsService {
    static final Set<String> SKIPPED_ACCOUNT_STATUSES = Set.of("PENDING_APPROVAL", "PENDING_FUNDING", "REJECTED");
    static final Set<String> INDIVIDUAL_TYPES = Set.of("INDIVIDUAL", "SOLE_TRADER");
    static final int PAGE = 200;
    static final int MAX_PAGES = 100;

    private final ServiceClient services;
    private final Store store;
    private final JsonMapper json;
    private final TransactionTemplate tx;

    public CrsService(ServiceClient services, Store store, JsonMapper json, TransactionTemplate tx) {
        this.services = services;
        this.store = store;
        this.json = json;
        this.tx = tx;
    }

    public ObjectNode generate(String regime, int year, UUID legalEntityId, String reportingJurisdiction) {
        if (!Set.of("CRS", "FATCA").contains(regime)) {
            throw Fields.invalid("regime", "invalid_enum", "regime must be CRS or FATCA");
        }
        int current = store.scalar("select extract(year from now())::int", Integer.class).orElseThrow();
        if (year < 2014 || year >= current + 1) {
            throw Fields.invalid("reporting_year", "out_of_range", "reporting_year must be between 2014 and " + current);
        }
        String jurisdiction = reportingJurisdiction.trim().toUpperCase(java.util.Locale.ROOT);
        String yearEnd = year + "-12-31T23:59:59Z";
        int customers = 0;
        int accounts = 0;
        int created = 0;
        int updated = 0;
        int undocumented = 0;
        int notReportable = 0;
        List<String> errors = new ArrayList<>();
        for (JsonNode c : page("customer-service", "/v1/customers?legal_entity_id=" + legalEntityId)) {
            customers++;
            String residence = trim(c, "tax_residence_country");
            if (residence == null) {
                residence = trim(c, "residence_country");
            }
            boolean usPerson = c.path("us_person").asBoolean(false) || "US".equals(residence) || "US".equals(trim(c, "nationality"));
            boolean undocumentedHolder = false;
            String reportable;
            if ("FATCA".equals(regime)) {
                if (!usPerson) {
                    notReportable++;
                    continue;
                }
                reportable = "US";
            } else {
                if (residence == null && trim(c, "tax_residence_country") == null) {
                    undocumentedHolder = true;
                    reportable = jurisdiction;
                } else if (usPerson || residence.equals(jurisdiction)) {
                    notReportable++;
                    continue;
                } else {
                    reportable = residence;
                }
            }
            String customerId = c.get("id").asString();
            try {
                Holder holder = holder(c, reportable, regime);
                for (JsonNode a : page("deposit-service", "/v1/deposit-accounts?customer_id=" + customerId)) {
                    if (a.hasNonNull("legal_entity_id") && !legalEntityId.toString().equals(a.get("legal_entity_id").asString())) {
                        continue;
                    }
                    if (SKIPPED_ACCOUNT_STATUSES.contains(a.path("status").asString(""))) {
                        continue;
                    }
                    accounts++;
                    boolean closed = "CLOSED".equals(a.path("status").asString(""));
                    BigDecimal balance = closed ? BigDecimal.ZERO : yearEndBalance(a, yearEnd);
                    BigDecimal interest = interest(a, year);
                    String ref = a.hasNonNull("account_number") ? a.get("account_number").asString()
                            : a.hasNonNull("iban") ? a.get("iban").asString() : a.get("id").asString();
                    boolean isNew = upsert(regime, year, legalEntityId, UUID.fromString(customerId), ref, holder,
                            reportable, balance, a.path("currency").asString("XXX").trim(), interest, undocumentedHolder, closed);
                    if (isNew) {
                        created++;
                    } else {
                        updated++;
                    }
                    if (undocumentedHolder) {
                        undocumented++;
                    }
                }
            } catch (ApiException e) {
                errors.add("customer " + customerId + ": " + e.code() + " - " + e.getMessage());
            }
        }
        ObjectNode out = json.createObjectNode();
        out.put("regime", regime);
        out.put("reporting_year", year);
        out.put("legal_entity_id", legalEntityId.toString());
        out.put("reporting_jurisdiction", jurisdiction);
        out.put("customers_scanned", customers);
        out.put("accounts_scanned", accounts);
        out.put("not_reportable", notReportable);
        out.put("records_created", created);
        out.put("records_updated", updated);
        out.put("undocumented", undocumented);
        ArrayNode errs = out.putArray("errors");
        errors.stream().limit(50).forEach(errs::add);
        return out;
    }

    private record Holder(String type, String name, String address, String dateOfBirth, String tin) {}

    private Holder holder(JsonNode c, String reportable, String regime) {
        String customerId = c.get("id").asString();
        boolean individual = INDIVIDUAL_TYPES.contains(c.path("customer_type").asString("INDIVIDUAL"));
        String name = text(c, "display_name");
        if (name == null) {
            name = individual ? String.join(" ", nonNull(text(c, "first_name"), text(c, "last_name"))).trim() : text(c, "legal_name");
        }
        if (name == null || name.isBlank()) {
            name = "UNKNOWN";
        }
        String tin = null;
        for (JsonNode id : page("customer-service", "/v1/customers/" + customerId + "/identifiers")) {
            String type = id.path("identifier_type").asString("");
            String issuer = trimText(id.path("issuing_country").asString(""));
            boolean taxId = "TAX_ID".equals(type) || ("FATCA".equals(regime) && "SOCIAL_SECURITY".equals(type));
            if (taxId && (tin == null || reportable.equals(issuer))) {
                tin = text(id, "identifier_value");
            }
        }
        String address = null;
        for (JsonNode ad : page("customer-service", "/v1/customers/" + customerId + "/addresses")) {
            String type = ad.path("address_type").asString("");
            if (address == null || "RESIDENTIAL".equals(type) || "REGISTERED".equals(type)) {
                List<String> parts = nonNull(text(ad, "line1"), text(ad, "line2"), text(ad, "city"), text(ad, "postal_code"),
                        trim(ad, "country"));
                address = String.join(", ", parts);
                if ("RESIDENTIAL".equals(type) || "REGISTERED".equals(type)) {
                    break;
                }
            }
        }
        return new Holder(individual ? "INDIVIDUAL" : "ENTITY", name, address, individual ? text(c, "date_of_birth") : null, tin);
    }

    private BigDecimal yearEndBalance(JsonNode account, String yearEnd) {
        JsonNode bal;
        if (account.hasNonNull("ledger_account_id")) {
            bal = services.get("ledger-service", "/v1/ledger-accounts/" + account.get("ledger_account_id").asString()
                    + "/balance-at?at=" + URLEncoder.encode(yearEnd, StandardCharsets.UTF_8));
        } else {
            bal = services.get("deposit-service", "/v1/deposit-accounts/" + account.get("id").asString() + "/balance");
        }
        String v = bal == null ? null : text(bal, "posted_balance");
        return v == null ? BigDecimal.ZERO : new BigDecimal(v).setScale(2, RoundingMode.HALF_EVEN);
    }

    private BigDecimal interest(JsonNode account, int year) {
        BigDecimal sum = null;
        for (JsonNode cap : page("deposit-service", "/v1/deposit-accounts/" + account.get("id").asString() + "/capitalisations")) {
            String end = text(cap, "period_end");
            if (end != null && LocalDate.parse(end).getYear() == year && "CREDIT".equals(cap.path("kind").asString("CREDIT"))) {
                String g = text(cap, "gross_amount");
                if (g != null) {
                    sum = (sum == null ? BigDecimal.ZERO : sum).add(new BigDecimal(g));
                }
            }
        }
        return sum == null ? null : sum.setScale(2, RoundingMode.HALF_EVEN);
    }

    /** Inserts or refreshes one record; true when created. */
    private boolean upsert(String regime, int year, UUID le, UUID customerId, String accountRef, Holder h, String residence,
                           BigDecimal balance, String currency, BigDecimal interest, boolean undocumented, boolean closed) {
        return Boolean.TRUE.equals(tx.execute(s -> store.scalar("""
                insert into crs_fatca_record as r (id, tenant_id, created_by, updated_by, regime, reporting_year, legal_entity_id,
                    customer_id, account_ref, account_holder_type, holder_name, holder_address, date_of_birth, tax_residence, tin,
                    account_balance, currency, gross_interest, is_undocumented, is_closed)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::date, ?, ?, ?, ?, ?, ?, ?)
                on conflict (tenant_id, regime, reporting_year, account_ref, customer_id) do update set
                    legal_entity_id = excluded.legal_entity_id, account_holder_type = excluded.account_holder_type,
                    holder_name = excluded.holder_name, holder_address = excluded.holder_address,
                    date_of_birth = excluded.date_of_birth, tax_residence = excluded.tax_residence,
                    tin = coalesce(excluded.tin, r.tin), account_balance = excluded.account_balance,
                    currency = excluded.currency, gross_interest = excluded.gross_interest,
                    is_undocumented = excluded.is_undocumented, is_closed = excluded.is_closed, updated_by = excluded.updated_by
                returning (xmax = 0)""", Boolean.class,
                Ids.newId(), TenantContext.require(), TenantContext.principal(), TenantContext.principal(), regime, year, le,
                customerId, Fields.clip(accountRef, 64), h.type(), Fields.clip(h.name(), 255), h.address(), h.dateOfBirth(),
                residence, Fields.clip(h.tin(), 64), balance, currency, interest, undocumented, closed).orElse(false)));
    }

    /** OECD XML of the stored records of a year. */
    public String oecdXml(String regime, int year, UUID legalEntityId, String transmittingCountry) {
        if (!Set.of("CRS", "FATCA").contains(regime)) {
            throw Fields.invalid("regime", "invalid_enum", "regime must be CRS or FATCA");
        }
        List<JsonNode> rows = store.rows("""
                select row_to_json(t)::text from crs_fatca_record t
                where t.regime = ? and t.reporting_year = ? and t.legal_entity_id = ? order by t.tax_residence, t.id""",
                regime, year, legalEntityId);
        if (rows.isEmpty()) {
            throw ApiException.unprocessable("no_records", "No " + regime + " records for " + year + "; run /generate first");
        }
        List<Map<String, Object>> records = rows.stream()
                .map(r -> json.convertValue(r, new TypeReference<Map<String, Object>>() {})).toList();
        String ts = store.scalar("select to_char(now() at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS')", String.class).orElseThrow();
        String digest = ReportRenderer.sha256(json.writeValueAsString(records)).substring(0, 12).toUpperCase(java.util.Locale.ROOT);
        String refId = transmittingCountry + year + regime + "-" + digest;
        return CrsXml.render(new CrsXml.Message(regime, year, legalEntityId.toString(), transmittingCountry, refId, ts), records);
    }

    private List<JsonNode> page(String service, String path) {
        List<JsonNode> out = new ArrayList<>();
        String cursor = null;
        for (int i = 0; i < MAX_PAGES; i++) {
            String p = path + (path.contains("?") ? "&" : "?") + "limit=" + PAGE
                    + (cursor == null ? "" : "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
            JsonNode r = services.get(service, p);
            if (r == null) {
                break;
            }
            r.path("data").forEach(out::add);
            if (!r.path("has_more").asBoolean(false) || !r.hasNonNull("next_cursor")) {
                break;
            }
            cursor = r.get("next_cursor").asString();
        }
        return out;
    }

    private static String text(JsonNode n, String f) {
        return n != null && n.hasNonNull(f) && !n.get(f).asString().isBlank() ? n.get(f).asString() : null;
    }

    private static String trim(JsonNode n, String f) {
        String v = text(n, f);
        return v == null ? null : trimText(v);
    }

    private static String trimText(String v) {
        return v.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static List<String> nonNull(String... parts) {
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                out.add(p);
            }
        }
        return out;
    }
}
