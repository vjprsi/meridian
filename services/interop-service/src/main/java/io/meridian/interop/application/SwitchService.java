package io.meridian.interop.application;

import io.meridian.common.error.ApiException;
import io.meridian.interop.domain.InteropRules;
import io.meridian.interop.infrastructure.Downstream;
import io.meridian.interop.infrastructure.Rows;
import io.meridian.interop.infrastructure.SwitchGateway;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Switch configuration rules, participant directory sync and the net position against the net debit cap. */
@Service
public class SwitchService {
    private static final Logger log = LoggerFactory.getLogger(SwitchService.class);
    static final Set<String> PROTOCOLS = Set.of("MOJALOOP_FSPIOP", "ISO20022", "ISO8583", "PROPRIETARY_API");
    static final Set<String> PARTICIPANT_TYPES = Set.of("WALLET", "BANK", "MFI");

    private final Rows rows;
    private final SwitchGateway gateway;
    private final Downstream downstream;
    private final TransactionTemplate tx;

    public SwitchService(Rows rows, SwitchGateway gateway, Downstream downstream, TransactionTemplate tx) {
        this.rows = rows;
        this.gateway = gateway;
        this.downstream = downstream;
        this.tx = tx;
    }

    public static void validateSwitch(JsonNode s) {
        String protocol = Rows.str(s, "protocol");
        if (protocol != null && !PROTOCOLS.contains(protocol)) {
            throw ApiException.unprocessable("invalid_protocol", "protocol must be one of " + PROTOCOLS);
        }
        for (String f : List.of("fee_flat", "fee_percentage", "net_debit_cap", "liquidity_limit")) {
            BigDecimal v = Rows.dec(s, f);
            if (v != null && v.signum() < 0) {
                throw ApiException.unprocessable("invalid_switch", f + " must not be negative");
            }
        }
        for (String f : List.of("quote_validity_seconds", "transfer_timeout_seconds")) {
            Integer v = Rows.integer(s, f);
            if (v != null && (v < 5 || v > 86_400)) {
                throw ApiException.unprocessable("invalid_switch", f + " must be between 5 and 86400 seconds");
            }
        }
    }

    public static void validateParticipant(JsonNode p) {
        String type = Rows.str(p, "participant_type");
        if (type != null && !PARTICIPANT_TYPES.contains(type)) {
            throw ApiException.unprocessable("invalid_participant_type", "participant_type must be one of " + PARTICIPANT_TYPES);
        }
    }

    /** Settlement account and currency are fixed while transfers are in flight on the switch. */
    public void guardUpdate(UUID id, JsonNode patch) {
        if ((patch.hasNonNull("settlement_account_id") || patch.hasNonNull("currency"))
                && inFlight(id) > 0) {
            throw ApiException.conflict("transfers_in_flight",
                    "settlement_account_id and currency cannot change while transfers are reserved on the switch");
        }
        validateSwitch(patch);
    }

    long inFlight(UUID switchId) {
        return rows.scalar("select count(*) from interop_transfer where interop_switch_id = ? and status in ('QUOTED', 'RESERVED')",
                Long.class, switchId).orElse(0L);
    }

    public ObjectNode requireActive(UUID id) {
        ObjectNode sw = rows.find("interop_switch", id).orElseThrow(() ->
                ApiException.unprocessable("switch_not_found", "Interop switch " + id + " does not exist"));
        if (!Rows.bool(sw, "is_active")) {
            throw ApiException.unprocessable("switch_inactive", "The switch " + Rows.str(sw, "code") + " is not active");
        }
        return sw;
    }

    public List<SwitchGateway.Participant> lookupCandidates(UUID switchId) {
        return rows.query("""
                select row_to_json(p)::text from interop_participant p
                where p.interop_switch_id = ? and p.is_active and p.supports_lookup order by p.fsp_id""", switchId)
                .stream().map(p -> new SwitchGateway.Participant(Rows.str(p, "fsp_id"), Rows.str(p, "name"),
                        Rows.str(p, "participant_type"), Rows.str(p, "currency"))).toList();
    }

    /** Upserts the directory of the switch and deactivates participants that left it. */
    public ObjectNode syncParticipants(UUID id) {
        ObjectNode sw = requireActive(id);
        List<SwitchGateway.Participant> directory = gateway.directory(sw);
        return tx.execute(s -> {
            rows.lock("interop_switch", id);
            int added = 0;
            int updated = 0;
            Set<String> seen = new HashSet<>();
            for (SwitchGateway.Participant p : directory) {
                seen.add(p.fspId());
                var existing = rows.queryOne("select row_to_json(p)::text from interop_participant p where interop_switch_id = ? and fsp_id = ?",
                        id, p.fspId());
                ObjectNode v = rows.object();
                v.put("name", p.name());
                v.put("participant_type", p.participantType());
                if (p.currency() != null) {
                    v.put("currency", p.currency());
                }
                v.put("is_active", true);
                if (existing.isPresent()) {
                    rows.update("interop_participant", Rows.uuid(existing.get(), "id"), v);
                    updated++;
                } else {
                    v.put("interop_switch_id", id.toString());
                    v.put("fsp_id", p.fspId());
                    rows.insert("interop_participant", v);
                    added++;
                }
            }
            int deactivated = 0;
            for (ObjectNode p : rows.query("select row_to_json(p)::text from interop_participant p where interop_switch_id = ? and is_active", id)) {
                if (!seen.contains(Rows.str(p, "fsp_id"))) {
                    ObjectNode v = rows.object();
                    v.put("is_active", false);
                    rows.update("interop_participant", Rows.uuid(p, "id"), v);
                    deactivated++;
                }
            }
            rows.execute("update interop_switch set last_directory_sync_at = now() where id = ?", id);
            ObjectNode out = rows.object();
            out.put("interop_switch_id", id.toString());
            out.put("added", added);
            out.put("updated", updated);
            out.put("deactivated", deactivated);
            out.put("total", directory.size());
            return out;
        });
    }

    /** Position figures of the switch from our own transfer book. */
    public record Position(BigDecimal reservedOutbound, BigDecimal committedOutbound, BigDecimal committedInbound) {
        public BigDecimal net() {
            return committedOutbound.subtract(committedInbound);
        }
    }

    public Position positionOf(UUID switchId) {
        ObjectNode p = rows.queryOne("""
                select json_build_object(
                  'reserved', coalesce(sum(amount + coalesce(fee_amount, 0)) filter (where direction = 'OUTBOUND' and status in ('QUOTED', 'RESERVED')), 0),
                  'out', coalesce(sum(amount + coalesce(fee_amount, 0)) filter (where direction = 'OUTBOUND' and status = 'COMMITTED'), 0),
                  'in', coalesce(sum(amount) filter (where direction = 'INBOUND' and status = 'COMMITTED'), 0))::text
                from interop_transfer where interop_switch_id = ?""", switchId).orElseThrow();
        return new Position(Rows.dec(p, "reserved"), Rows.dec(p, "out"), Rows.dec(p, "in"));
    }

    public ObjectNode position(UUID id) {
        ObjectNode sw = rows.get("interop_switch", id);
        Position p = positionOf(id);
        ObjectNode out = rows.object();
        out.put("interop_switch_id", id.toString());
        out.put("currency", Rows.str(sw, "currency"));
        UUID account = Rows.uuid(sw, "settlement_account_id");
        if (account != null) {
            try {
                JsonNode bal = downstream.ledgerBalance(account);
                out.put("settlement_balance", bal.path("posted_balance").asString(null));
                out.put("settlement_available", bal.path("available_balance").asString(null));
            } catch (ApiException e) {
                log.warn("settlement balance of switch {} unavailable: {}", id, e.getMessage());
                out.putNull("settlement_balance");
                out.put("settlement_balance_error", e.code());
            }
        } else {
            out.putNull("settlement_balance");
        }
        out.put("reserved_outbound", p.reservedOutbound().toPlainString());
        out.put("committed_outbound", p.committedOutbound().toPlainString());
        out.put("committed_inbound", p.committedInbound().toPlainString());
        out.put("net_position", p.net().toPlainString());
        BigDecimal cap = Rows.dec(sw, "net_debit_cap");
        BigDecimal headroom = InteropRules.headroom(cap, p.net(), p.reservedOutbound());
        out.put("net_debit_cap", cap == null ? null : cap.toPlainString());
        out.put("headroom", headroom == null ? null : headroom.toPlainString());
        BigDecimal liquidity = Rows.dec(sw, "liquidity_limit");
        out.put("liquidity_limit", liquidity == null ? null : liquidity.toPlainString());
        return out;
    }
}
