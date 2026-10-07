package io.meridian.merchantchannel.application;

import static io.meridian.merchantchannel.application.CurrentUser.merchant;

import io.meridian.common.error.ApiException;
import io.meridian.merchantchannel.application.Downstream.Query;
import io.meridian.merchantchannel.domain.FeeStatement;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Payment links, settlements, settlement reports and the fee (MDR) statement of the merchant. These are merchant-wide
 * (not store) objects: payment links are read by OWNER, MANAGER and VIEWER users and managed by OWNER and MANAGER;
 * settlements and fees are read by OWNER, MANAGER and VIEWER (never by cashiers). Link bodies are rebuilt here: the
 * merchant and the currency always come from the merchant record.
 */
@Service
public class CollectionService {
    static final String MERCHANTS = Access.MERCHANTS;
    static final String[] READERS = {"OWNER", "MANAGER", "VIEWER"};
    static final String[] MANAGERS = {"OWNER", "MANAGER"};

    private final Access access;
    private final Downstream down;
    private final ActionAudit audit;

    public CollectionService(Access access, Downstream down, ActionAudit audit) {
        this.access = access;
        this.down = down;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ payment links

    public JsonNode links(Boolean active, Integer limit, String cursor) {
        access.requireRole("merchantAppPaymentLinksList", "PAYMENT_LINK", null, READERS);
        return down.page(down.get(MERCHANTS, "/v1/merchant-payment-links", Query.of("merchant_id", merchant())
                .with("is_active", active).paging(limit, cursor)), Access::ofMerchant);
    }

    public JsonNode link(UUID id) {
        String action = "merchantAppPaymentLinksGet";
        JsonNode l = access.paymentLink(id, action);
        access.requireRole(action, "PAYMENT_LINK", id, READERS);
        return l;
    }

    public JsonNode createLink(JsonNode req, String key) {
        String action = "merchantAppPaymentLinksCreate";
        access.requireRole(action, "PAYMENT_LINK", null, MANAGERS);
        JsonNode m = access.merchantRecord();
        ObjectNode body = down.json().createObjectNode()
                .put("merchant_id", merchant().toString())
                .put("currency", m.path("currency").asString())
                .put("description", Req.required(req, "description"));
        if (Req.text(req, "amount") != null) {
            body.put("amount", Req.amount(req, "amount").toPlainString());
        }
        if (Req.text(req, "reference") != null) {
            body.put("reference", Req.text(req, "reference"));
        }
        Integer maxUses = Req.integer(req, "max_uses");
        if (maxUses != null) {
            if (maxUses < 1) {
                throw ApiException.unprocessable("invalid_max_uses", "max_uses must be at least 1");
            }
            body.put("max_uses", maxUses);
        }
        if (Req.text(req, "expires_at") != null) {
            body.put("expires_at", Req.text(req, "expires_at"));
        }
        JsonNode created = down.post(MERCHANTS, "/v1/merchant-payment-links", body, key, "create");
        audit.succeeded(action, "PAYMENT_LINK", null, created.path("id").asString(null));
        return created;
    }

    public JsonNode cancelLink(UUID id, String key) {
        String action = "merchantAppPaymentLinksCancel";
        access.paymentLink(id, action);
        access.requireRole(action, "PAYMENT_LINK", id, MANAGERS);
        JsonNode r = down.post(MERCHANTS, "/v1/merchant-payment-links/" + id + "/deactivate", null, key, "cancel");
        audit.succeeded(action, "PAYMENT_LINK", id, null);
        return r;
    }

    // ------------------------------------------------------------------ settlements

    public JsonNode settlements(String status, Integer limit, String cursor) {
        access.requireRole("merchantAppSettlementsList", "SETTLEMENT", null, READERS);
        return down.page(down.get(MERCHANTS, "/v1/merchant-settlements", Query.of("merchant_id", merchant())
                .with("status", status).paging(limit, cursor)), Access::ofMerchant);
    }

    public JsonNode settlement(UUID id) {
        String action = "merchantAppSettlementsGet";
        JsonNode s = access.settlement(id, action);
        access.requireRole(action, "SETTLEMENT", id, READERS);
        return s;
    }

    public String report(UUID id) {
        String action = "merchantAppSettlementsReport";
        access.settlement(id, action);
        access.requireRole(action, "SETTLEMENT", id, READERS);
        byte[] content = down.bytes(MERCHANTS, "/v1/merchant-settlements/" + id + "/report").content();
        audit.succeeded(action, "SETTLEMENT", id, null);
        return new String(content, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ fees

    /** {fee_plan, from, to, currency, merchant_code, settlements[], totals}. */
    public JsonNode fees(LocalDate from, LocalDate to) {
        access.requireRole("merchantAppFeesGet", "SETTLEMENT", null, READERS);
        JsonNode m = access.merchantRecord();
        List<FeeStatement.Line> lines = lines(from, to);
        ObjectNode out = down.json().createObjectNode();
        out.set("fee_plan", feePlan(m));
        out.put("merchant_code", m.path("merchant_code").asString(null));
        out.put("currency", m.path("currency").asString(null));
        out.put("from", from == null ? null : from.toString());
        out.put("to", to == null ? null : to.toString());
        ArrayNode arr = out.putArray("settlements");
        for (FeeStatement.Line l : lines) {
            arr.addObject().put("settlement_id", l.settlementId()).put("period_start", str(l.periodStart()))
                    .put("period_end", str(l.periodEnd())).put("status", l.status()).put("payment_count", l.payments())
                    .put("gross_amount", plain(l.gross())).put("mdr_amount", plain(l.mdr()))
                    .put("refund_amount", plain(l.refunds())).put("net_amount", plain(l.net()));
        }
        FeeStatement.Totals t = FeeStatement.totals(lines);
        out.putObject("totals").put("settlements", t.settlements()).put("payment_count", t.payments())
                .put("gross_amount", t.gross().toPlainString()).put("mdr_amount", t.mdr().toPlainString())
                .put("refund_amount", t.refunds().toPlainString()).put("net_amount", t.net().toPlainString())
                .put("effective_mdr_rate", t.effectiveRate().toPlainString());
        return out;
    }

    public String feeStatement(LocalDate from, LocalDate to) {
        String action = "merchantAppFeesStatement";
        access.requireRole(action, "SETTLEMENT", null, READERS);
        JsonNode m = access.merchantRecord();
        String csv = FeeStatement.csv(m.path("merchant_code").asString(null), m.path("currency").asString(null),
                lines(from, to));
        audit.succeeded(action, "SETTLEMENT", null, null);
        return csv;
    }

    private List<FeeStatement.Line> lines(LocalDate from, LocalDate to) {
        if (from != null && to != null && to.isBefore(from)) {
            throw ApiException.badRequest("invalid_range", "to is before from");
        }
        List<FeeStatement.Line> out = new ArrayList<>();
        for (JsonNode s : down.all(MERCHANTS, "/v1/merchant-settlements", Query.of("merchant_id", merchant()), 5000)) {
            if (!Access.ofMerchant(s)) {
                continue;
            }
            LocalDate end = Req.date(s, "period_end");
            String status = s.path("status").asString("");
            if (!FeeStatement.inRange(end, status, from, to)) {
                continue;
            }
            out.add(new FeeStatement.Line(s.path("id").asString(), Req.date(s, "period_start"), end, status,
                    s.path("payment_count").asInt(0), Req.rowDecimal(s, "gross_amount"), Req.rowDecimal(s, "mdr_amount"),
                    Req.rowDecimal(s, "refund_amount"), Req.rowDecimal(s, "net_amount")));
        }
        out.sort((a, b) -> a.periodEnd().compareTo(b.periodEnd()));
        return out;
    }

    private JsonNode feePlan(JsonNode m) {
        String planId = m.path("merchant_fee_plan_id").asString(null);
        ObjectNode plan = down.json().createObjectNode();
        if (planId == null) {
            return plan;
        }
        JsonNode p;
        try {
            p = down.get(MERCHANTS, "/v1/merchant-fee-plans/" + planId);
        } catch (ApiException e) {
            if (e.status() == 404) {
                return plan;
            }
            throw e;
        }
        for (String f : List.of("id", "code", "name", "currency", "mdr_percentage", "fixed_fee", "min_fee", "max_fee",
                "refund_fee", "settlement_fee", "valid_from", "valid_to")) {
            if (p.has(f)) {
                plan.set(f, p.get(f));
            }
        }
        return plan;
    }

    private static String str(LocalDate d) {
        return d == null ? null : d.toString();
    }

    private static String plain(BigDecimal d) {
        return d == null ? "0.00" : d.setScale(2, java.math.RoundingMode.HALF_EVEN).toPlainString();
    }
}
