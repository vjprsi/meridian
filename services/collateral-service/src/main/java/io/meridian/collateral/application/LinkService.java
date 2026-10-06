package io.meridian.collateral.application;

import io.meridian.collateral.domain.CollateralRules;
import io.meridian.collateral.domain.Currencies;
import io.meridian.collateral.domain.LtvCalculator;
import io.meridian.collateral.infrastructure.ExposureClient;
import io.meridian.collateral.infrastructure.Rows;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Collateral links (allocation of lending value to a loan or credit line) and LTV monitoring. The LTV of an
 * exposure is its outstanding amount in loan-service divided by the lending value allocated to it by all active
 * links; every link of the exposure carries that LTV and its own status against its max_ltv.
 */
@Service
public class LinkService {
    static final String TABLE = "collateral_link";
    private static final Set<String> SUPPORTED_EXPOSURES = Set.of("LOAN", "BNPL", "CREDIT_LINE");
    private static final Set<String> CLOSED_EXPOSURE = Set.of("PAID_OFF", "CLOSED", "CANCELLED");

    private final Rows rows;
    private final ExposureClient exposures;
    private final Events events;

    public LinkService(Rows rows, ExposureClient exposures, Events events) {
        this.rows = rows;
        this.exposures = exposures;
        this.events = events;
    }

    @Transactional
    public ObjectNode create(Object body) {
        ObjectNode values = rows.columns(TABLE, body);
        UUID collateralId = Rows.uuid(values, "collateral_id");
        ObjectNode collateral = rows.queryOne("select row_to_json(c)::text from collateral c where id = ? for update",
                collateralId).orElseThrow(() -> ApiException.unprocessable("collateral_not_found",
                "collateral_id " + collateralId + " does not reference an existing collateral"));
        if (!CollateralRules.linkable(Rows.str(collateral, "status"))) {
            throw ApiException.conflict("collateral_not_linkable",
                    "Only VERIFIED or PLEDGED collateral can secure an exposure (status " + Rows.str(collateral, "status") + ")");
        }
        String type = Rows.str(values, "exposure_type");
        if (!SUPPORTED_EXPOSURES.contains(type)) {
            throw ApiException.unprocessable("unsupported_exposure_type", "exposure_type must be LOAN, BNPL or CREDIT_LINE");
        }
        BigDecimal allocated = Rows.dec(values, "allocated_value");
        String currency = Rows.str(collateral, "currency");
        validateAllocation(values, allocated, currency);
        UUID exposureId = Rows.uuid(values, "exposure_id");
        boolean duplicate = rows.scalar("""
                select count(*) > 0 from collateral_link where collateral_id = ? and exposure_type = ? and exposure_id = ?
                  and is_active""", Boolean.class, collateralId, type, exposureId).orElse(false);
        if (duplicate) {
            throw ApiException.conflict("link_exists", "The collateral already secures this exposure; update the link instead");
        }
        requireCapacity(collateral, allocated, null);
        ExposureClient.Exposure exposure = exposures.require(type, exposureId);
        if (exposure.currency() != null && !exposure.currency().equals(currency)) {
            throw ApiException.unprocessable("currency_mismatch", "Exposure currency " + exposure.currency()
                    + " differs from the collateral valuation currency " + currency);
        }
        if (exposure.status() != null && CLOSED_EXPOSURE.contains(exposure.status())) {
            throw ApiException.unprocessable("exposure_closed", "Exposure is " + exposure.status());
        }
        values.put("is_active", true);
        ObjectNode link = rows.insert(TABLE, values);
        syncAllocated(collateralId);
        events.publish("lending.collateral_link.created", TABLE, link);
        recalculate(exposure);
        return rows.get(TABLE, Rows.uuid(link, "id"));
    }

    /** PATCH: allocation, rank and LTV limit may change (re-validated, LTV recomputed); the target may not. */
    @Transactional
    public ObjectNode update(UUID id, String ifMatch, Object body) {
        int version = Support.version(ifMatch);
        ObjectNode link = rows.lock(TABLE, id);
        JsonNode req = rows.json().valueToTree(body);
        for (String f : List.of("collateral_id", "exposure_type", "exposure_id")) {
            if (req.hasNonNull(f)) {
                throw ApiException.unprocessable("field_not_updatable", f + " cannot be changed; release the link and create a new one");
            }
        }
        if (!Rows.bool(link, "is_active")) {
            throw ApiException.conflict("link_released", "A released link cannot be changed");
        }
        ObjectNode values = rows.columns(TABLE, body);
        ObjectNode collateral = rows.lock("collateral", Rows.uuid(link, "collateral_id"));
        BigDecimal allocated = values.hasNonNull("allocated_value") ? Rows.dec(values, "allocated_value") : Rows.dec(link, "allocated_value");
        ObjectNode merged = link.deepCopy();
        merged.setAll(values);
        validateAllocation(merged, allocated, Rows.str(collateral, "currency"));
        if (values.hasNonNull("allocated_value")) {
            requireCapacity(collateral, allocated, id);
        }
        String updated = rows.crud().updateRow(TABLE, Map.of("id", id), values, version)
                .orElseThrow(() -> ApiException.conflict("version_conflict",
                        "If-Match " + version + " does not match the current version; reload and retry"));
        syncAllocated(Rows.uuid(link, "collateral_id"));
        recalculate(Rows.str(link, "exposure_type"), Rows.uuid(link, "exposure_id"));
        return rows.get(TABLE, Rows.uuid(rows.parse(updated), "id"));
    }

    @Transactional
    public ObjectNode release(UUID id, String reason) {
        ObjectNode link = rows.lock(TABLE, id);
        if (!Rows.bool(link, "is_active")) {
            throw ApiException.conflict("invalid_transition", "Collateral link " + id + " is already released");
        }
        ObjectNode released = deactivate(link, reason);
        try {
            recalculate(Rows.str(link, "exposure_type"), Rows.uuid(link, "exposure_id"));
        } catch (ApiException e) {
            // the exposure may already be closed in loan-service; the release itself stands
        }
        return rows.get(TABLE, Rows.uuid(released, "id"));
    }

    /** Deactivates a link (caller holds the lock), keeps the collateral allocation in sync and publishes. */
    ObjectNode deactivate(ObjectNode link, String reason) {
        UUID id = Rows.uuid(link, "id");
        ObjectNode released = rows.queryOne("""
                update collateral_link set is_active = false, release_reason = ?, released_at = now(), updated_by = ?
                where id = ? returning row_to_json(collateral_link)::text""",
                truncate(reason, 255), TenantContext.principal(), id).orElseThrow();
        syncAllocated(Rows.uuid(link, "collateral_id"));
        events.publish("lending.collateral_link.released", TABLE, released);
        return released;
    }

    @Transactional
    public ObjectNode recalculateLink(UUID id) {
        ObjectNode link = rows.get(TABLE, id);
        if (!Rows.bool(link, "is_active")) {
            throw ApiException.conflict("link_released", "A released link has no LTV to recalculate");
        }
        recalculate(Rows.str(link, "exposure_type"), Rows.uuid(link, "exposure_id"));
        return rows.get(TABLE, id);
    }

    /** Recalculates the LTV of one exposure (fetching its outstanding from loan-service); returns link results. */
    @Transactional
    public List<ObjectNode> recalculate(String exposureType, UUID exposureId) {
        return recalculate(exposures.fetch(exposureType, exposureId));
    }

    List<ObjectNode> recalculate(ExposureClient.Exposure exposure) {
        List<ObjectNode> links = rows.query("""
                select row_to_json(l)::text from collateral_link l where exposure_type = ? and exposure_id = ? and is_active
                order by charge_rank, created_at for update""", exposure.type(), exposure.id());
        List<BigDecimal> allocations = links.stream().map(l -> Rows.dec(l, "allocated_value")).toList();
        List<ObjectNode> out = new ArrayList<>();
        for (ObjectNode l : links) {
            LtvCalculator.Result r = LtvCalculator.calculate(exposure.outstanding(), allocations, Rows.dec(l, "max_ltv"));
            String previous = Rows.str(l, "ltv_status");
            ObjectNode updated = rows.queryOne("""
                    update collateral_link set exposure_amount = ?, ltv = ?, ltv_status = ?, ltv_calculated_at = now(),
                           updated_by = ? where id = ? returning row_to_json(collateral_link)::text""",
                    r.exposure(), r.ltv(), r.status().name(), TenantContext.principal(), Rows.uuid(l, "id")).orElseThrow();
            if (LtvCalculator.worsened(previous == null ? null : LtvCalculator.Status.valueOf(previous), r.status())) {
                events.publish("lending.collateral_link.ltv_breached", TABLE, updated);
            }
            out.add(updated);
        }
        return out;
    }

    /** LTV report of every exposure secured by a collateral item. */
    public ObjectNode report(UUID collateralId) {
        ObjectNode collateral = rows.get("collateral", collateralId);
        ObjectNode out = rows.object();
        out.put("collateral_id", collateralId.toString());
        out.put("collateral_number", Rows.str(collateral, "collateral_number"));
        out.put("currency", Rows.str(collateral, "currency"));
        out.put("market_value", Rows.str(collateral, "market_value"));
        out.put("lending_value", Rows.str(collateral, "lending_value"));
        out.put("allocated_value", Rows.str(collateral, "allocated_value"));
        BigDecimal lending = Rows.decOr0(collateral, "lending_value");
        BigDecimal alloc = Rows.decOr0(collateral, "allocated_value");
        out.put("unallocated_value", lending.subtract(alloc).max(BigDecimal.ZERO).toPlainString());
        ArrayNode exp = out.putArray("exposures");
        String worst = "OK";
        for (ObjectNode l : rows.query("""
                select row_to_json(l)::text from collateral_link l where collateral_id = ? and is_active
                order by charge_rank, created_at""", collateralId)) {
            ObjectNode e = exp.addObject();
            e.put("link_id", Rows.str(l, "id"));
            e.put("exposure_type", Rows.str(l, "exposure_type"));
            e.put("exposure_id", Rows.str(l, "exposure_id"));
            e.put("charge_rank", Rows.integer(l, "charge_rank"));
            e.put("allocated_value", Rows.str(l, "allocated_value"));
            e.put("exposure_amount", Rows.str(l, "exposure_amount"));
            e.put("ltv", Rows.str(l, "ltv"));
            e.put("max_ltv", Rows.str(l, "max_ltv"));
            String status = Rows.str(l, "ltv_status");
            e.put("ltv_status", status);
            e.put("ltv_calculated_at", Rows.str(l, "ltv_calculated_at"));
            if (status != null && LtvCalculator.Status.valueOf(status).ordinal() > LtvCalculator.Status.valueOf(worst).ordinal()) {
                worst = status;
            }
        }
        out.put("worst_ltv_status", worst);
        return out;
    }

    /** Recomputes collateral.allocated_value from its active links. */
    void syncAllocated(UUID collateralId) {
        rows.execute("""
                update collateral c set allocated_value = coalesce((select sum(allocated_value) from collateral_link
                  where collateral_id = c.id and is_active), 0), updated_by = ? where id = ?""",
                TenantContext.principal(), collateralId);
    }

    private void requireCapacity(ObjectNode collateral, BigDecimal allocated, UUID excludeLink) {
        UUID cid = Rows.uuid(collateral, "id");
        BigDecimal used = rows.scalar("""
                select coalesce(sum(allocated_value), 0) from collateral_link where collateral_id = ? and is_active
                  and (?::uuid is null or id <> ?::uuid)""", BigDecimal.class, cid, excludeLink, excludeLink)
                .orElse(BigDecimal.ZERO);
        BigDecimal lending = Rows.decOr0(collateral, "lending_value");
        if (used.add(allocated).compareTo(lending) > 0) {
            throw ApiException.unprocessable("over_allocated", "Allocating " + allocated.toPlainString()
                    + " would exceed the lending value " + lending.toPlainString() + " (already allocated "
                    + used.toPlainString() + ")");
        }
    }

    private static void validateAllocation(JsonNode values, BigDecimal allocated, String currency) {
        if (allocated == null || allocated.signum() <= 0) {
            throw ApiException.unprocessable("invalid_amount", "allocated_value must be greater than zero");
        }
        if (!Currencies.fitsMinorUnit(allocated, currency)) {
            throw ApiException.unprocessable("invalid_amount", "allocated_value has more decimals than " + currency + " allows");
        }
        Integer rank = Rows.integer(values, "charge_rank");
        if (rank != null && rank < 1) {
            throw ApiException.unprocessable("invalid_charge_rank", "charge_rank must be 1 or more");
        }
        BigDecimal maxLtv = Rows.dec(values, "max_ltv");
        if (maxLtv != null && (maxLtv.signum() <= 0 || maxLtv.compareTo(BigDecimal.valueOf(10)) > 0)) {
            throw ApiException.unprocessable("invalid_max_ltv", "max_ltv must be a ratio in (0, 10], e.g. 0.80");
        }
    }

    static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
