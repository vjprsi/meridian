package io.meridian.collateral.application;

import io.meridian.collateral.domain.CollateralRules;
import io.meridian.collateral.domain.Currencies;
import io.meridian.collateral.domain.LiquidationAllocator;
import io.meridian.collateral.infrastructure.ExposureClient;
import io.meridian.collateral.infrastructure.Rows;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Collateral registry: registration, lifecycle (verify, pledge, release, liquidate), valuations and insurance. */
@Service
public class CollateralService {
    private static final Logger log = LoggerFactory.getLogger(CollateralService.class);
    static final String TABLE = "collateral";
    private static final Set<String> VALUATION_METHODS =
            Set.of("FULL_SURVEY", "DESKTOP", "INDEX", "AUTOMATED_AVM", "MARKET_PRICE");
    /** PATCH may not touch these: values come from valuations, actions or are immutable identity. */
    private static final List<String> PROTECTED = List.of("owner_customer_id", "collateral_type", "currency",
            "market_value", "forced_sale_value", "status", "deposit_account_id");

    private final Rows rows;
    private final Events events;
    private final LinkService links;
    private final ExposureClient exposures;

    public CollateralService(Rows rows, Events events, LinkService links, ExposureClient exposures) {
        this.rows = rows;
        this.events = events;
        this.links = links;
        this.exposures = exposures;
    }

    // ------------------------------------------------------------------ registration

    @Transactional
    public ObjectNode register(Object body) {
        ObjectNode values = rows.columns(TABLE, body);
        String type = Rows.str(values, "collateral_type");
        String currency = Rows.str(values, "currency");
        BigDecimal market = Rows.dec(values, "market_value");
        validateAmounts(market, Rows.dec(values, "forced_sale_value"), currency);
        List<CollateralRules.Violation> violations = new ArrayList<>(CollateralRules.validateAssetDetails(type,
                details(values.get("asset_details")), values.hasNonNull("deposit_account_id")));
        BigDecimal haircut = values.hasNonNull("haircut_pct") ? Rows.dec(values, "haircut_pct") : CollateralRules.defaultHaircut(type);
        violations.addAll(CollateralRules.validateHaircut(haircut));
        if (!violations.isEmpty()) {
            throw Support.invalid("invalid_asset_details", "Collateral attributes are invalid for type " + type, violations);
        }
        values.put("haircut_pct", haircut);
        values.put("lending_value", CollateralRules.lendingValue(market, haircut, currency));
        values.put("allocated_value", BigDecimal.ZERO);
        values.put("status", "PROPOSED");
        int year = today().getYear();
        for (int attempt = 0; ; attempt++) {
            values.put("collateral_number", Support.collateralNumber(year));
            try {
                ObjectNode row = rows.insert(TABLE, values.deepCopy());
                events.publish("lending.collateral.registered", TABLE, row);
                return row;
            } catch (DuplicateKeyException e) {
                if (attempt >= 4) {
                    throw e;
                }
            }
        }
    }

    @Transactional
    public ObjectNode update(UUID id, String ifMatch, Object body) {
        int version = Support.version(ifMatch);
        JsonNode req = rows.json().valueToTree(body);
        for (String f : PROTECTED) {
            if (req.hasNonNull(f)) {
                throw ApiException.unprocessable("field_not_updatable", f + " cannot be changed with PATCH"
                        + (f.endsWith("value") ? "; record a valuation" : f.equals("status") ? "; use the lifecycle actions" : ""));
            }
        }
        ObjectNode current = rows.lock(TABLE, id);
        if (Set.of("RELEASED", "LIQUIDATED", "REJECTED").contains(Rows.str(current, "status"))) {
            throw ApiException.conflict("collateral_closed", "Collateral " + id + " is " + Rows.str(current, "status"));
        }
        ObjectNode values = rows.columns(TABLE, body);
        String type = Rows.str(current, "collateral_type");
        List<CollateralRules.Violation> violations = new ArrayList<>();
        if (values.has("asset_details")) {
            violations.addAll(CollateralRules.validateAssetDetails(type, details(values.get("asset_details")),
                    current.hasNonNull("deposit_account_id")));
        }
        boolean haircutChanged = values.hasNonNull("haircut_pct");
        if (haircutChanged) {
            violations.addAll(CollateralRules.validateHaircut(Rows.dec(values, "haircut_pct")));
        }
        if (!violations.isEmpty()) {
            throw Support.invalid("invalid_asset_details", "Collateral attributes are invalid for type " + type, violations);
        }
        if (haircutChanged) {
            BigDecimal lending = CollateralRules.lendingValue(Rows.dec(current, "market_value"),
                    Rows.dec(values, "haircut_pct"), Rows.str(current, "currency"));
            values.put("lending_value", lending);
        }
        rows.crud().updateRow(TABLE, Map.of("id", id), values, version)
                .orElseThrow(() -> ApiException.conflict("version_conflict",
                        "If-Match " + version + " does not match the current version; reload and retry"));
        if (haircutChanged) {
            recalcLinksBestEffort(id);
        }
        return rows.get(TABLE, id);
    }

    // ------------------------------------------------------------------ lifecycle

    @Transactional
    public ObjectNode verify(UUID id) {
        ObjectNode c = rows.lock(TABLE, id);
        Support.requireTransition(TABLE, Rows.str(c, "status"), "VERIFIED");
        if (TenantContext.principal().equals(Rows.str(c, "created_by"))) {
            throw ApiException.unprocessable("four_eyes_violation", "Collateral must be verified by a user other than the one who registered it");
        }
        List<CollateralRules.Violation> violations = CollateralRules.validateAssetDetails(Rows.str(c, "collateral_type"),
                details(c.get("asset_details")), c.hasNonNull("deposit_account_id"));
        if (!violations.isEmpty()) {
            throw Support.invalid("invalid_asset_details", "Collateral attributes are incomplete", violations);
        }
        int valuations = rows.scalar("select count(*) from collateral_valuation where collateral_id = ?", Integer.class, id).orElse(0);
        if (valuations == 0) {
            throw ApiException.unprocessable("valuation_required", "At least one valuation is required before verification");
        }
        ObjectNode row = rows.queryOne("""
                update collateral set status = 'VERIFIED', verified_by = ?, verified_at = now(), updated_by = ?
                where id = ? returning row_to_json(collateral)::text""",
                TenantContext.principal(), TenantContext.principal(), id).orElseThrow();
        events.publish("lending.collateral.verified", TABLE, row);
        return row;
    }

    @Transactional
    public ObjectNode reject(UUID id, String reason) {
        ObjectNode c = rows.lock(TABLE, id);
        Support.requireTransition(TABLE, Rows.str(c, "status"), "REJECTED");
        requireNoActiveLinks(id);
        ObjectNode row = setStatus(id, "REJECTED", reason);
        events.publish("lending.collateral.rejected", TABLE, row);
        return row;
    }

    @Transactional
    public ObjectNode pledge(UUID id, String registrationReference, LocalDate lienRegisteredAt) {
        ObjectNode c = rows.lock(TABLE, id);
        Support.requireTransition(TABLE, Rows.str(c, "status"), "PLEDGED");
        if (registrationReference == null || registrationReference.isBlank()) {
            throw ApiException.unprocessable("registration_reference_required", "registration_reference is required");
        }
        Support.notAfter(lienRegisteredAt, today(), "lien_registered_at");
        ObjectNode row = rows.queryOne("""
                update collateral set status = 'PLEDGED', registration_reference = ?, lien_registered_at = ?,
                       status_reason = null, updated_by = ? where id = ? returning row_to_json(collateral)::text""",
                registrationReference.trim(), lienRegisteredAt, TenantContext.principal(), id).orElseThrow();
        events.publish("lending.collateral.pledged", TABLE, row);
        return row;
    }

    @Transactional
    public ObjectNode release(UUID id, String reason, boolean force) {
        ObjectNode c = rows.lock(TABLE, id);
        Support.requireTransition(TABLE, Rows.str(c, "status"), "RELEASED");
        List<ObjectNode> active = activeLinks(id);
        if (!active.isEmpty() && !force) {
            throw ApiException.conflict("active_links_exist", "Collateral still secures " + active.size()
                    + " exposure(s); release the links first or pass force=true");
        }
        for (ObjectNode l : active) {
            links.deactivate(rows.lock(LinkService.TABLE, Rows.uuid(l, "id")), "Collateral released: " + reason);
        }
        ObjectNode row = rows.queryOne("""
                update collateral set status = 'RELEASED', status_reason = ?, released_at = now(), updated_by = ?
                where id = ? returning row_to_json(collateral)::text""",
                LinkService.truncate(reason, 255), TenantContext.principal(), id).orElseThrow();
        events.publish("lending.collateral.released", TABLE, row);
        return row;
    }

    /**
     * Liquidation: net proceeds (proceeds - costs) go to the secured exposures by charge rank, capped at each
     * exposure's outstanding, as loan / credit-line repayments (or recoveries for written-off loans) with keys
     * deterministic per collateral and link; the links are released and the surplus is reported for the owner.
     */
    @Transactional
    public ObjectNode liquidate(UUID id, BigDecimal proceeds, LocalDate saleDate, BigDecimal costs, String reference) {
        ObjectNode c = rows.lock(TABLE, id);
        Support.requireTransition(TABLE, Rows.str(c, "status"), "LIQUIDATED");
        String currency = Rows.str(c, "currency");
        BigDecimal cost = costs == null ? BigDecimal.ZERO : costs;
        if (proceeds == null || proceeds.signum() <= 0 || cost.signum() < 0) {
            throw ApiException.unprocessable("invalid_amount", "proceeds must be positive and costs not negative");
        }
        if (!Currencies.fitsMinorUnit(proceeds, currency) || !Currencies.fitsMinorUnit(cost, currency)) {
            throw ApiException.unprocessable("invalid_amount", "amounts have more decimals than " + currency + " allows");
        }
        if (cost.compareTo(proceeds) > 0) {
            throw ApiException.unprocessable("costs_exceed_proceeds", "costs must not exceed the proceeds");
        }
        Support.notAfter(saleDate, today(), "sale_date");
        BigDecimal net = proceeds.subtract(cost);
        List<ObjectNode> active = activeLinks(id);
        List<LiquidationAllocator.Claim> claims = new ArrayList<>();
        Map<UUID, ExposureClient.Exposure> byLink = new HashMap<>();
        int order = 0;
        for (ObjectNode l : active) {
            ExposureClient.Exposure e = exposures.fetch(Rows.str(l, "exposure_type"), Rows.uuid(l, "exposure_id"));
            if (e.currency() != null && !e.currency().equals(currency)) {
                throw ApiException.unprocessable("currency_mismatch", "Exposure " + e.id() + " is in " + e.currency()
                        + "; cross-currency liquidation is not supported");
            }
            UUID linkId = Rows.uuid(l, "id");
            byLink.put(linkId, e);
            claims.add(new LiquidationAllocator.Claim(linkId, e.id(), Rows.integer(l, "charge_rank"), order++, e.outstanding()));
        }
        LiquidationAllocator.Result allocation = LiquidationAllocator.allocate(net, claims);
        String ref = reference == null || reference.isBlank() ? Rows.str(c, "collateral_number") : reference.trim();
        String receivedAt = rows.scalar("""
                select to_char(now() at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')""", String.class).orElseThrow();
        ObjectNode out = rows.object();
        out.put("collateral_id", id.toString());
        out.put("currency", currency);
        out.put("proceeds", proceeds.toPlainString());
        out.put("costs", cost.toPlainString());
        out.put("net_proceeds", net.toPlainString());
        out.put("sale_date", saleDate.toString());
        ArrayNode alloc = out.putArray("allocations");
        for (LiquidationAllocator.Allocation a : allocation.allocations()) {
            ExposureClient.Exposure e = byLink.get(a.linkId());
            ObjectNode applied = exposures.applyProceeds(e, a.amount(), ref, receivedAt,
                    "collateral-liquidation:" + id + ":" + a.linkId());
            ObjectNode item = alloc.addObject();
            item.put("link_id", a.linkId().toString());
            item.put("exposure_type", e.type());
            item.put("exposure_id", e.id().toString());
            item.put("exposure_outstanding", e.outstanding().toPlainString());
            item.put("amount", a.amount().toPlainString());
            item.put("applied_as", applied.path("kind").asString(null));
            item.put("loan_service_id", applied.path("id").asString(null));
        }
        out.put("surplus", allocation.surplus().toPlainString());
        out.put("surplus_payable_to", Rows.str(c, "owner_customer_id"));
        for (ObjectNode l : active) {
            links.deactivate(rows.lock(LinkService.TABLE, Rows.uuid(l, "id")), "Collateral liquidated");
        }
        ObjectNode row = rows.queryOne("""
                update collateral set status = 'LIQUIDATED', liquidation_proceeds = ?, liquidated_at = now(),
                       status_reason = ?, updated_by = ? where id = ? returning row_to_json(collateral)::text""",
                net, LinkService.truncate("Sold on " + saleDate + " (" + ref + ")", 255), TenantContext.principal(), id)
                .orElseThrow();
        events.publish("lending.collateral.liquidated", TABLE, row);
        out.put("status", "LIQUIDATED");
        return out;
    }

    // ------------------------------------------------------------------ valuations

    @Transactional
    public ObjectNode addValuation(UUID collateralId, Object body) {
        ObjectNode c = rows.queryOne("select row_to_json(c)::text from collateral c where id = ? for update", collateralId)
                .orElseThrow(() -> ApiException.notFound("collateral", collateralId));
        String status = Rows.str(c, "status");
        if (Set.of("RELEASED", "LIQUIDATED", "REJECTED").contains(status)) {
            throw ApiException.conflict("collateral_closed", "Collateral " + collateralId + " is " + status);
        }
        ObjectNode values = rows.columns("collateral_valuation", body);
        // the generated create model repeats the path parent id; it must agree with the path
        if (values.hasNonNull("collateral_id") && !collateralId.equals(Rows.uuid(values, "collateral_id"))) {
            throw ApiException.unprocessable("collateral_id_mismatch", "collateral_id in the body must match the path");
        }
        String currency = Rows.str(c, "currency");
        if (!currency.equals(Rows.str(values, "currency"))) {
            throw ApiException.unprocessable("currency_mismatch", "Valuation currency must be the collateral currency " + currency);
        }
        BigDecimal market = Rows.dec(values, "market_value");
        BigDecimal forced = Rows.dec(values, "forced_sale_value");
        validateAmounts(market, forced, currency);
        String method = Rows.str(values, "method");
        if (!VALUATION_METHODS.contains(method)) {
            throw ApiException.unprocessable("invalid_valuation_method", "method must be one of " + VALUATION_METHODS);
        }
        LocalDate date = Rows.date(values, "valuation_date");
        Support.notAfter(date, today(), "valuation_date");
        BigDecimal haircut = Rows.dec(c, "haircut_pct");
        if (haircut == null) {
            haircut = CollateralRules.defaultHaircut(Rows.str(c, "collateral_type"));
        }
        BigDecimal lending = CollateralRules.lendingValue(market, haircut, currency);
        values.put("collateral_id", collateralId.toString());
        values.put("haircut_pct", haircut);
        values.put("lending_value", lending);
        ObjectNode valuation = rows.insert("collateral_valuation", values);
        LocalDate latest = rows.scalar("select max(valuation_date) from collateral_valuation where collateral_id = ?",
                LocalDate.class, collateralId).orElse(date);
        if (!date.isBefore(latest)) {
            LocalDate next = date.plusMonths(CollateralRules.revaluationMonths(Rows.str(c, "collateral_type")));
            rows.execute("""
                    update collateral set market_value = ?, forced_sale_value = ?, haircut_pct = ?, lending_value = ?,
                           next_valuation_date = ?, updated_by = ? where id = ?""",
                    market, forced, haircut, lending, next, TenantContext.principal(), collateralId);
            recalcLinksBestEffort(collateralId);
        }
        events.publish("lending.collateral.revalued", "collateral_valuation", valuation);
        return valuation;
    }

    // ------------------------------------------------------------------ insurance

    @Transactional
    public ObjectNode addInsurance(Object body) {
        ObjectNode values = rows.columns("collateral_insurance", body);
        UUID cid = Rows.uuid(values, "collateral_id");
        ObjectNode c = rows.find(TABLE, cid).orElseThrow(() -> ApiException.unprocessable("collateral_not_found",
                "collateral_id " + cid + " does not reference an existing collateral"));
        if (Set.of("RELEASED", "LIQUIDATED", "REJECTED").contains(Rows.str(c, "status"))) {
            throw ApiException.conflict("collateral_closed", "Collateral " + cid + " is " + Rows.str(c, "status"));
        }
        validateInsurance(values, Rows.str(c, "currency"));
        return rows.insert("collateral_insurance", values);
    }

    @Transactional
    public ObjectNode updateInsurance(UUID id, String ifMatch, Object body) {
        int version = Support.version(ifMatch);
        ObjectNode current = rows.lock("collateral_insurance", id);
        JsonNode req = rows.json().valueToTree(body);
        for (String f : List.of("collateral_id", "currency")) {
            if (req.hasNonNull(f)) {
                throw ApiException.unprocessable("field_not_updatable", f + " cannot be changed with PATCH");
            }
        }
        ObjectNode values = rows.columns("collateral_insurance", body);
        ObjectNode merged = current.deepCopy();
        merged.setAll(values);
        validateInsurance(merged, Rows.str(current, "currency"));
        if (values.has("end_date") && !Rows.date(values, "end_date").equals(Rows.date(current, "end_date"))) {
            values.putNull("expiry_notified_at"); // a renewed policy is warned again before its new expiry
        }
        rows.crud().updateRow("collateral_insurance", Map.of("id", id), values, version)
                .orElseThrow(() -> ApiException.conflict("version_conflict",
                        "If-Match " + version + " does not match the current version; reload and retry"));
        return rows.get("collateral_insurance", id);
    }

    // ------------------------------------------------------------------ helpers

    LocalDate today() {
        return rows.scalar("select current_date", LocalDate.class).orElseThrow();
    }

    private void recalcLinksBestEffort(UUID collateralId) {
        for (ObjectNode l : activeLinks(collateralId)) {
            try {
                links.recalculate(Rows.str(l, "exposure_type"), Rows.uuid(l, "exposure_id"));
            } catch (ApiException e) {
                log.warn("LTV recalculation of link {} failed: {}", Rows.str(l, "id"), e.getMessage());
            }
        }
    }

    private List<ObjectNode> activeLinks(UUID collateralId) {
        return rows.query("""
                select row_to_json(l)::text from collateral_link l where collateral_id = ? and is_active
                order by charge_rank, created_at""", collateralId);
    }

    private void requireNoActiveLinks(UUID id) {
        if (!activeLinks(id).isEmpty()) {
            throw ApiException.conflict("active_links_exist", "Collateral still secures exposures; release the links first");
        }
    }

    private ObjectNode setStatus(UUID id, String status, String reason) {
        return rows.queryOne("""
                update collateral set status = ?, status_reason = ?, updated_by = ? where id = ?
                returning row_to_json(collateral)::text""",
                status, LinkService.truncate(reason, 255), TenantContext.principal(), id).orElseThrow();
    }

    private static void validateAmounts(BigDecimal market, BigDecimal forced, String currency) {
        if (market == null || market.signum() <= 0) {
            throw ApiException.unprocessable("invalid_amount", "market_value must be greater than zero");
        }
        if (forced != null && (forced.signum() < 0 || forced.compareTo(market) > 0)) {
            throw ApiException.unprocessable("invalid_forced_sale_value", "forced_sale_value must be between 0 and market_value");
        }
        if (!Currencies.fitsMinorUnit(market, currency) || forced != null && !Currencies.fitsMinorUnit(forced, currency)) {
            throw ApiException.unprocessable("invalid_amount", "values have more decimals than " + currency + " allows");
        }
    }

    private static void validateInsurance(JsonNode v, String currency) {
        if (!currency.equals(Rows.str(v, "currency"))) {
            throw ApiException.unprocessable("currency_mismatch", "Insurance currency must be the collateral currency " + currency);
        }
        BigDecimal sum = Rows.dec(v, "sum_insured");
        if (sum == null || sum.signum() <= 0) {
            throw ApiException.unprocessable("invalid_amount", "sum_insured must be greater than zero");
        }
        BigDecimal premium = Rows.dec(v, "premium");
        if (premium != null && premium.signum() < 0) {
            throw ApiException.unprocessable("invalid_amount", "premium must not be negative");
        }
        LocalDate start = Rows.date(v, "start_date");
        LocalDate end = Rows.date(v, "end_date");
        if (start != null && end != null && !end.isAfter(start)) {
            throw ApiException.unprocessable("invalid_period", "end_date must be after start_date");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> details(JsonNode node) {
        return node == null || node.isNull() || !node.isObject() ? Map.of() : rows.json().treeToValue(node, Map.class);
    }
}
