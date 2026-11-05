package io.meridian.reconciliation.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Reads general-ledger control-account balances from gl-service for the safeguarding stock reconciliation
 * (WE-RECON-1): the e-money liability components B (GL 2700), C (GL 2710) and D (GL 2720), and - when the
 * safeguarding bank figure A is itself a GL account - A. Follows the same thin {@link ServiceClient} pattern as
 * {@link LedgerGateway}; it introduces no new HTTP machinery.
 *
 * <p>A GL balance is resolved by code within a legal entity: {@code GET /v1/gl/accounts?legal_entity_id&code} gives
 * the account, then {@code GET /v1/gl/balances?gl_account_id&currency} gives the current closing balance (summed
 * across the returned rows, which for a single currency is one row). The caller may instead pass the component
 * figures on the run request as a documented fallback when a live read is not wired for a tenant.
 */
@Component
public class GlBalanceGateway {
    private final ServiceClient services;

    public GlBalanceGateway(ServiceClient services) {
        this.services = services;
    }

    /**
     * Current closing balance of the GL account with {@code code} in {@code legalEntityId}, in {@code currency}
     * (null = sum across currencies). Returns {@link BigDecimal#ZERO} when the account exists but has no balance row.
     *
     * @throws ApiException 502 when gl-service is unreachable, or 422 when the code does not resolve to an account
     */
    public BigDecimal closingBalanceByCode(UUID legalEntityId, String code, String currency) {
        String acctPath = "/v1/gl/accounts?legal_entity_id=" + legalEntityId + "&code="
                + URLEncoder.encode(code, StandardCharsets.UTF_8) + "&limit=1";
        JsonNode accounts = services.get("gl-service", acctPath);
        if (accounts == null || !accounts.path("data").isArray() || accounts.path("data").isEmpty()) {
            throw ApiException.unprocessable("gl_account_not_found",
                    "No GL account with code " + code + " in legal entity " + legalEntityId);
        }
        UUID glAccountId = UUID.fromString(accounts.path("data").get(0).get("id").asString());
        return closingBalance(glAccountId, currency);
    }

    /** Current closing balance of a GL account by id, summed across the balance rows (optionally one currency). */
    public BigDecimal closingBalance(UUID glAccountId, String currency) {
        String path = "/v1/gl/balances?gl_account_id=" + glAccountId
                + (currency == null || currency.isBlank() ? "" : "&currency="
                        + URLEncoder.encode(currency.trim(), StandardCharsets.UTF_8));
        JsonNode body = services.get("gl-service", path);
        if (body == null || !body.path("data").isArray()) {
            throw new ApiException(502, "upstream_error", "gl-service returned no GL balance page");
        }
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode b : body.path("data")) {
            if (b.hasNonNull("closing_balance")) {
                total = total.add(new BigDecimal(b.get("closing_balance").asString()));
            }
        }
        return total;
    }
}
