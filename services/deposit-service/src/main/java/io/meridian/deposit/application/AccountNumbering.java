package io.meridian.deposit.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.deposit.config.DepositProperties;
import io.meridian.deposit.domain.AccountNumbers;
import io.meridian.deposit.domain.NumberingRule;
import io.meridian.deposit.infrastructure.Rows;
import io.meridian.web.client.ServiceClient;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Account identifiers follow the account's booking legal entity (docs/SERVICE_IMPLEMENTATION_GUIDE.md "Account
 * numbering"): the country, BIC and code come from the legal entity in tenant-service, the bank / branch codes and
 * format from the tenant's numbering settings in config-service (namespace {@value #NAMESPACE}), the IBAN length
 * from reference-data-service. Numbers are allocated unique per tenant under an advisory lock per candidate.
 *
 * <p>Settings: one config entry per legal entity, keyed by its id or its code, plus an optional {@code default}
 * entry; per field the id entry wins over the code entry over {@code default}. Fields (snake_case): {@code scheme}
 * (IBAN, NATIONAL, AUTO), {@code bank_code}, {@code branch_code}, {@code bic}, {@code pattern}, {@code serial_length},
 * {@code check_digit} (LUHN, MOD97, NUBAN, NONE), {@code account_number_length}, {@code iban_length}. A home branch
 * with a routing code of the scheme's branch-code length replaces {@code branch_code}. Where the IBAN layout has an
 * alphabetic bank code (GB, IE, NL) and no {@code bank_code} is set, it is the BIC's institution code. The deployment properties
 * {@code meridian.deposit.bank-code / branch-code / account-number-length} apply only to a legal entity in
 * {@code meridian.deposit.country} (single-tenant installs, tests); they never number another country's accounts.
 */
@Component
public class AccountNumbering {
    public static final String NAMESPACE = "account_numbering";
    static final String TENANT_SERVICE = "tenant-service";
    static final String CONFIG = "config-service";
    static final String REFERENCE = "reference-data-service";
    private static final Logger log = LoggerFactory.getLogger(AccountNumbering.class);
    private static final int ATTEMPTS = 50;

    private final ServiceClient client;
    private final DepositProperties props;
    private final Rows rows;
    private final long ttlMs;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Cached> legalEntities = new ConcurrentHashMap<>();
    private final Map<UUID, Cached> settings = new ConcurrentHashMap<>();
    private final Map<String, Cached> countries = new ConcurrentHashMap<>();

    private record Cached(JsonNode value, long expiresAt) {}

    public AccountNumbering(ServiceClient client, DepositProperties props, Rows rows,
                            @Value("${meridian.tenant-settings.ttl-ms:30000}") long ttlMs) {
        this.client = client;
        this.props = props;
        this.rows = rows;
        this.ttlMs = ttlMs;
    }

    /** The numbering rule of a legal entity (optionally with the account's home branch). 422 when not configurable. */
    public NumberingRule rule(UUID legalEntityId, UUID branchId) {
        if (legalEntityId == null) {
            throw ApiException.unprocessable("legal_entity_required", "legal_entity_id is required to number an account");
        }
        UUID tenant = TenantContext.current().orElseThrow(() -> new IllegalStateException("no tenant bound"));
        JsonNode entity = legalEntity(tenant, legalEntityId);
        String country = text(entity, "country");
        if (country == null) {
            throw ApiException.unprocessable("account_numbering_not_configured",
                    "Legal entity " + legalEntityId + " has no country");
        }
        country = country.toUpperCase(Locale.ROOT);
        JsonNode all = settings(tenant);
        ObjectNode s = JsonNodeFactory.instance.objectNode();
        // config keys are lower-case and start with a letter: entity code lower-cased, entity id as "le-<uuid>"
        String code = text(entity, "code");
        for (String key : new String[] {"default", code == null ? null : code.toLowerCase(java.util.Locale.ROOT),
                "le-" + legalEntityId}) {
            if (key == null) {
                continue;
            }
            JsonNode entry = key == null ? null : all.get(key);
            if (entry != null && entry.isObject()) {
                entry.properties().forEach(e -> {
                    if (!e.getValue().isNull() && !(e.getValue().isString() && e.getValue().asString().isBlank())) {
                        s.set(e.getKey(), e.getValue());
                    }
                });
            }
        }
        boolean deploymentCountry = props.country() != null && props.country().equalsIgnoreCase(country);
        String bic = Optional.ofNullable(text(s, "bic")).orElse(text(entity, "swift_bic"));
        // the entity's BIC gives the bank code of alphabetic-bank IBAN layouts (GB, IE, NL) before the deployment value
        AccountNumbers.BbanFormat layout = AccountNumbers.IBAN_COUNTRIES.get(country);
        boolean bankFromBic = bic != null && layout != null && layout.bankAlpha();
        String bank = Optional.ofNullable(text(s, "bank_code"))
                .orElse(deploymentCountry && !bankFromBic ? props.bankCode() : null);
        String branch = Optional.ofNullable(text(s, "branch_code")).orElse(deploymentCountry ? props.branchCode() : null);
        Integer length = integer(s, "account_number_length");
        if (length == null && deploymentCountry) {
            length = props.accountNumberLength();
        }
        Integer ibanLength = integer(s, "iban_length");
        if (ibanLength == null) {
            ibanLength = ibanLength(country);
        }
        NumberingRule.Input input = new NumberingRule.Input(country, text(s, "scheme"), bank, branch,
                bic, text(s, "pattern"),
                integer(s, "serial_length"), text(s, "check_digit"), length, ibanLength);
        try {
            NumberingRule rule = NumberingRule.resolve(input);
            String routing = branchId == null ? null : branchRoutingCode(tenant, branchId, legalEntityId);
            if (routing != null && !rule.branchCode().isEmpty() && routing.length() == rule.branchCode().length()
                    && !routing.equals(rule.branchCode())) {
                rule = NumberingRule.resolve(new NumberingRule.Input(input.country(), input.scheme(), input.bankCode(),
                        routing, input.bic(), input.pattern(), input.serialLength(), input.checkDigit(),
                        input.accountNumberLength(), input.ibanLength()));
            }
            return rule;
        } catch (NumberingRule.Invalid e) {
            throw ApiException.unprocessable(e.code(), "Account numbering of legal entity " + text(entity, "code")
                    + " (" + country + "): " + e.getMessage() + ". Configure config-service namespace " + NAMESPACE
                    + " (key " + text(entity, "code") + " or default)");
        }
    }

    /**
     * Allocates identifiers unique in the tenant (account numbers and IBANs of deposit and virtual accounts). Each
     * candidate is locked with a transaction-scoped advisory lock before the check, so concurrent openings that draw
     * the same candidate are serialised and the later one sees the earlier one's committed row. Call inside the
     * transaction that inserts the account.
     */
    public NumberingRule.Numbers allocate(NumberingRule rule) {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            NumberingRule.Numbers n = rule.generate(random);
            rows.scalar("select 1 from (select pg_advisory_xact_lock(hashtextextended(?, 0))) l", Integer.class,
                    "deposit-account-number:" + n.accountNumber());
            boolean taken = rows.scalar("""
                    select 1 where exists (select 1 from deposit_account where account_number = ? or iban = ?)
                       or exists (select 1 from virtual_account where virtual_account_number = ? or iban = ?)""",
                    Integer.class, n.accountNumber(), n.iban(), n.accountNumber(), n.iban()).isPresent();
            if (!taken) {
                return n;
            }
        }
        throw ApiException.conflict("account_number_exhausted", "Could not allocate a unique account number");
    }

    /** The IBAN length of a country from reference-data-service (null when unknown or not an IBAN country). */
    Integer ibanLength(String country) {
        long now = System.currentTimeMillis();
        Cached c = countries.get(country);
        if (c == null || c.expiresAt() <= now) {
            JsonNode value;
            long ttl = Math.max(ttlMs, 3_600_000L);
            try {
                JsonNode page = client.get(REFERENCE, "/v1/reference/countries?code=" + country + "&limit=1");
                JsonNode row = page == null ? null : page.path("data").path(0);
                value = row == null || row.isMissingNode() ? JsonNodeFactory.instance.nullNode() : row.path("iban_length");
            } catch (RuntimeException e) {
                log.debug("reference data for {} unavailable: {}", country, e.getMessage());
                value = JsonNodeFactory.instance.nullNode();
                ttl = Math.min(ttlMs, 5_000);
            }
            c = new Cached(value, now + ttl);
            countries.put(country, c);
        }
        return c.value().isNumber() && c.value().asInt() > 0 ? c.value().asInt() : null;
    }

    /** Drops cached legal entities, settings and reference data (tests, configuration change). */
    public void evict() {
        legalEntities.clear();
        settings.clear();
        countries.clear();
    }

    private JsonNode legalEntity(UUID tenant, UUID id) {
        String key = tenant + "|" + id;
        long now = System.currentTimeMillis();
        Cached c = legalEntities.get(key);
        if (c != null && c.expiresAt() > now) {
            return c.value();
        }
        JsonNode entity;
        try {
            entity = client.get(TENANT_SERVICE, "/v1/legal-entities/" + id);
        } catch (ApiException e) {
            if (e.status() == 404) {
                throw ApiException.unprocessable("legal_entity_not_found", "Legal entity " + id + " does not exist");
            }
            throw e;
        }
        if (entity == null || !entity.isObject()) {
            throw ApiException.unprocessable("legal_entity_not_found", "Legal entity " + id + " does not exist");
        }
        legalEntities.put(key, new Cached(entity, now + ttlMs));
        return entity;
    }

    private JsonNode settings(UUID tenant) {
        long now = System.currentTimeMillis();
        Cached c = settings.get(tenant);
        if (c != null && c.expiresAt() > now) {
            return c.value();
        }
        JsonNode values;
        long ttl = ttlMs;
        try {
            JsonNode resolved = client.get(CONFIG, "/v1/config/entries/resolve?namespace=" + NAMESPACE);
            values = resolved == null ? null : resolved.get("values");
            if (values == null || !values.isObject()) {
                values = JsonNodeFactory.instance.objectNode();
            }
        } catch (RuntimeException e) {
            log.debug("account numbering settings unavailable: {}", e.getMessage());
            values = JsonNodeFactory.instance.objectNode();
            ttl = Math.min(ttlMs, 5_000);
        }
        settings.put(tenant, new Cached(values, now + ttl));
        return values;
    }

    private String branchRoutingCode(UUID tenant, UUID branchId, UUID legalEntityId) {
        try {
            JsonNode branch = client.get(TENANT_SERVICE, "/v1/branches/" + branchId);
            if (branch == null || !legalEntityId.toString().equals(text(branch, "legal_entity_id"))) {
                return null;
            }
            String routing = text(branch, "routing_code");
            return routing == null ? null : routing.replaceAll("[\\s-]", "");
        } catch (RuntimeException e) {
            log.debug("branch {} of tenant {} unavailable: {}", branchId, tenant, e.getMessage());
            return null;
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.isString() ? v.asString() : v.toString();
        return s.isBlank() ? null : s.trim();
    }

    private static Integer integer(JsonNode n, String field) {
        String s = text(n, field);
        if (s == null) {
            return null;
        }
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            throw ApiException.unprocessable("account_numbering_invalid", field + " must be an integer, got " + s);
        }
    }
}
