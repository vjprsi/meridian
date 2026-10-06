package io.meridian.card.application;

import io.meridian.card.config.CardProperties;
import io.meridian.card.domain.CardLifecycle;
import io.meridian.card.domain.PanGenerator;
import io.meridian.card.domain.ProgramRules;
import io.meridian.card.infrastructure.Peers;
import io.meridian.card.infrastructure.Rows;
import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Card issuing and lifecycle. Calls to the vault (PAN, PIN) happen outside database transactions; every state change
 * is one transaction with its status history row and outbox event. The PAN exists only in local variables on its way
 * to the vault: it is never stored, logged or published here.
 */
@Service
public class CardService {
    private static final Logger log = LoggerFactory.getLogger(CardService.class);
    /** Card columns a PATCH may change; everything else is owned by issuing and lifecycle actions. */
    public static final Set<String> CARD_PROTECTED = Set.of("card_program_id", "card_product_id", "customer_id",
            "form_factor", "linked_account_type", "ledger_account_id", "deposit_account_id", "wallet_id", "credit_line_id");
    /** Card columns copied to a replacement card. */
    private static final String[] COPIED = {"card_program_id", "card_product_id", "customer_id", "business_customer_id",
            "cardholder_name", "linked_account_type", "ledger_account_id", "deposit_account_id", "wallet_id",
            "credit_line_id", "nickname", "metadata"};

    private final Rows rows;
    private final Events events;
    private final Peers peers;
    private final PanGenerator pans;
    private final CardProperties props;
    private final TransactionTemplate tx;

    public CardService(Rows rows, Events events, Peers peers, PanGenerator pans, CardProperties props,
                       TransactionTemplate tx) {
        this.rows = rows;
        this.events = events;
        this.peers = peers;
        this.pans = pans;
        this.props = props;
        this.tx = tx;
    }

    // ------------------------------------------------------------------ issue

    private record IssuePlan(ObjectNode program, ObjectNode product, String status, int expiryMonth, int expiryYear,
                             ObjectNode bureau, JsonNode address, String shippingMethod) {}

    public JsonNode issue(JsonNode req, String clientKey) {
        IssuePlan plan = tx.execute(s -> planIssue(req));
        UUID cardId = Ids.newId();
        JsonNode vault = vaultNewPan(plan.program(), cardId, plan.expiryMonth(), plan.expiryYear());
        String pinEventType = null;
        String block = Rows.str(req, "encrypted_pin_block");
        if (block != null) {
            String keyId = Rows.str(req, "pin_key_id");
            if (keyId == null) {
                throw ApiException.unprocessable("pin_key_required", "pin_key_id is required with encrypted_pin_block");
            }
            JsonNode pin = peers.setPin(Rows.str(vault, "pan_token"), block, keyId, Peers.key("card-pin", cardId, null));
            pinEventType = pin.path("event_type").asString("SET");
        }
        String pinType = pinEventType;
        return tx.execute(s -> {
            ObjectNode values = rows.columns("card", req);
            ObjectNode card = persistCard(cardId, values, vault, plan, null, 0, pinType != null);
            JsonNode dc = plan.program().get("default_controls");
            if (dc != null && dc.isArray()) {
                for (JsonNode c : dc) {
                    ObjectNode control = rows.columns("card_control", c);
                    control.put("card_id", cardId.toString());
                    control.remove("card_program_id");
                    events.publish("cards.card_control.changed", "card_control", rows.insert("card_control", control));
                }
            }
            if ("CREDIT".equals(Rows.str(plan.program(), "funding_model")) && Rows.str(card, "credit_line_id") != null) {
                openCycle(card, rows.dbToday(), BigDecimal.ZERO, Rows.str(plan.program(), "currency"));
            }
            if (pinType != null) {
                pinEvent(cardId, pinType, true);
            }
            return card;
        });
    }

    private IssuePlan planIssue(JsonNode req) {
        UUID programId = Rows.uuid(req, "card_program_id");
        UUID productId = Rows.uuid(req, "card_product_id");
        ObjectNode program = rows.find("card_program", programId)
                .orElseThrow(() -> ApiException.unprocessable("program_not_found", "card program " + programId + " not found"));
        ObjectNode product = rows.find("card_product", productId)
                .orElseThrow(() -> ApiException.unprocessable("product_not_found", "card product " + productId + " not found"));
        if (!"ACTIVE".equals(Rows.str(program, "status"))) {
            throw ApiException.unprocessable("program_not_active", "card program is " + Rows.str(program, "status"));
        }
        if (!"ACTIVE".equals(Rows.str(product, "status"))) {
            throw ApiException.unprocessable("product_not_active", "card product is " + Rows.str(product, "status"));
        }
        if (!programId.equals(Rows.uuid(product, "card_program_id"))) {
            throw ApiException.unprocessable("product_program_mismatch", "card product does not belong to the program");
        }
        String formFactor = Rows.str(req, "form_factor");
        requireFormFactor(product, formFactor);
        ProgramRules.validateFunding(Rows.str(program, "funding_model"), Rows.str(req, "linked_account_type"));
        if ("CREDIT".equals(Rows.str(program, "funding_model")) && Rows.str(req, "credit_line_id") == null) {
            throw ApiException.unprocessable("credit_line_required", "credit cards require credit_line_id");
        }
        String name = Rows.str(req, "cardholder_name");
        if (name == null || name.isBlank() || !name.matches("[A-Za-z0-9 .'/-]{2,26}")) {
            throw ApiException.unprocessable("invalid_cardholder_name",
                    "cardholder_name must be 2-26 embossable characters (letters, digits, space . ' / -)");
        }
        long open = rows.scalar("""
                select count(*) from card where customer_id = ?::uuid and card_product_id = ?::uuid
                and status not in ('BLOCKED','LOST','STOLEN','EXPIRED','CLOSED','REPLACED')""", Long.class,
                Rows.str(req, "customer_id"), productId.toString()).orElse(0L);
        if (open >= Rows.integer(product, "max_cards_per_customer")) {
            throw ApiException.unprocessable("card_limit_reached",
                    "the customer already holds " + open + " active cards on this product");
        }
        LocalDate expiry = rows.dbToday().plusYears(Rows.integer(product, "expiry_years"));
        ObjectNode bureau = null;
        JsonNode address = null;
        if ("PHYSICAL".equals(formFactor)) {
            address = req.get("shipping_address");
            bureau = bureauFor(product, address);
        }
        return new IssuePlan(program, product, CardLifecycle.initialStatus(formFactor), expiry.getMonthValue(),
                expiry.getYear(), bureau, address, Rows.str(req, "shipping_method"));
    }

    private static void requireFormFactor(JsonNode product, String formFactor) {
        boolean allowed = false;
        for (JsonNode f : product.path("form_factors")) {
            allowed |= f.asString().equals(formFactor);
        }
        if (!allowed) {
            throw ApiException.unprocessable("form_factor_not_allowed", formFactor + " cards are not offered on this product");
        }
    }

    /** Validates a delivery address and resolves the product's bureau. */
    private ObjectNode bureauFor(JsonNode product, JsonNode address) {
        if (address == null || !address.isObject() || Rows.str(address, "address_line1") == null
                || Rows.str(address, "city") == null || Rows.str(address, "country") == null
                || !Rows.str(address, "country").matches("[A-Z]{2}")) {
            throw ApiException.unprocessable("address_required",
                    "a physical card needs an address with address_line1, city and a 2-letter country");
        }
        UUID bureauId = Rows.uuid(product, "card_bureau_id");
        if (bureauId == null) {
            throw ApiException.unprocessable("bureau_not_configured", "the card product has no card bureau");
        }
        ObjectNode bureau = rows.get("card_bureau", bureauId);
        if (!Rows.bool(bureau, "is_active")) {
            throw ApiException.unprocessable("bureau_inactive", "the card bureau is not active");
        }
        JsonNode countries = bureau.get("supported_countries");
        if (countries != null && countries.isArray() && !countries.isEmpty()) {
            boolean ok = false;
            for (JsonNode c : countries) {
                ok |= c.asString().equals(Rows.str(address, "country"));
            }
            if (!ok) {
                throw ApiException.unprocessable("country_not_supported", "the bureau does not ship to " + Rows.str(address, "country"));
            }
        }
        return bureau;
    }

    /** Generates a PAN in the program's account range and vaults it, retrying on a (rare) collision. */
    private JsonNode vaultNewPan(JsonNode program, UUID cardId, int month, int year) {
        String start = Rows.str(program, "bin_range_start");
        String end = Rows.str(program, "bin_range_end");
        int length = Rows.integer(program, "pan_length");
        for (int attempt = 1; ; attempt++) {
            String pan = pans.generate(start, end, length);
            try {
                return peers.tokenize(pan, month, year, cardId, "card-issue:" + cardId + ":" + attempt);
            } catch (ApiException e) {
                if (!"pan_already_vaulted".equals(e.code()) || attempt >= props.maxPanAttempts()) {
                    throw e;
                }
                log.info("PAN collision in the vault for card {}, attempt {}", cardId, attempt);
            }
        }
    }

    /** Inserts a new card row with its history, order (physical) and events. Runs inside a transaction. */
    private ObjectNode persistCard(UUID cardId, ObjectNode values, JsonNode vault, IssuePlan plan, UUID replaces,
                                   int sequence, boolean pinSet) {
        values.put("id", cardId.toString());
        values.put("pan_token", Rows.str(vault, "pan_token"));
        values.put("pan_fingerprint", Rows.str(vault, "pan_fingerprint"));
        values.put("bin", Rows.str(vault, "bin") != null ? Rows.str(vault, "bin") : Rows.str(plan.program(), "bin"));
        values.put("last4", Rows.str(vault, "last4"));
        values.put("expiry_month", plan.expiryMonth());
        values.put("expiry_year", plan.expiryYear());
        values.put("sequence_number", sequence);
        values.put("status", plan.status());
        values.put("pin_set", pinSet);
        if ("ACTIVE".equals(plan.status())) {
            values.put("activated_at", rows.dbNow());
        }
        if (replaces != null) {
            values.put("replaces_card_id", replaces.toString());
        }
        ObjectNode card = rows.insert("card", values);
        history(cardId, null, plan.status(), replaces == null ? "Issued" : "Issued as replacement");
        events.publish("cards.card.created", "card", card);
        if ("ACTIVE".equals(plan.status())) {
            events.publish("cards.card.activated", "card", card);
        }
        if (plan.bureau() != null) {
            createOrder(cardId, plan.bureau(), plan.address(), Rows.str(values, "cardholder_name"),
                    plan.shippingMethod(), null);
        }
        return card;
    }

    ObjectNode createOrder(UUID cardId, JsonNode bureau, JsonNode address, String cardholderName, String shippingMethod,
                           UUID reshipOf) {
        ObjectNode o = rows.object();
        o.put("card_id", cardId.toString());
        o.put("card_bureau_id", Rows.str(bureau, "id"));
        o.put("shipping_method", shippingMethod == null ? "STANDARD" : shippingMethod);
        String recipient = Rows.str(address, "recipient_name");
        o.put("recipient_name", recipient == null ? cardholderName : recipient);
        for (String f : new String[] {"address_line1", "address_line2", "city", "region", "postal_code", "country", "phone"}) {
            if (Rows.str(address, f) != null) {
                o.put(f, Rows.str(address, f));
            }
        }
        if (reshipOf != null) {
            o.put("reship_of_order_id", reshipOf.toString());
        }
        ObjectNode order = rows.insert("card_order", o);
        events.publish("cards.card_order.created", "card_order", order);
        return order;
    }

    void history(UUID cardId, String from, String to, String reason) {
        ObjectNode h = rows.object();
        h.put("card_id", cardId.toString());
        if (from != null) {
            h.put("from_status", from);
        }
        h.put("to_status", to);
        if (reason != null) {
            h.put("reason", reason.length() > 255 ? reason.substring(0, 255) : reason);
        }
        h.put("channel", "API");
        h.put("actor_id", TenantContext.principal());
        rows.insert("card_status_history", h);
    }

    void openCycle(JsonNode card, LocalDate start, BigDecimal opening, String currency) {
        ObjectNode c = rows.object();
        LocalDate end = start.plusMonths(1).minusDays(1);
        c.put("card_id", Rows.str(card, "id"));
        c.put("credit_line_id", Rows.str(card, "credit_line_id"));
        c.put("cycle_start", start.toString());
        c.put("cycle_end", end.toString());
        c.put("due_date", end.plusDays(21).toString());
        c.put("opening_balance", opening.toPlainString());
        c.put("currency", currency);
        rows.insert("card_statement_cycle", c);
    }

    // ------------------------------------------------------------------ status changes

    public JsonNode activate(UUID id, String activationCode) {
        return transition(id, (c, from) -> {
            String to = CardLifecycle.activate(from);
            if ("PHYSICAL".equals(Rows.str(c, "form_factor"))
                    && (activationCode == null || !activationCode.equals(Rows.str(c, "last4")))) {
                throw ApiException.unprocessable("invalid_activation_code",
                        "physical cards are activated with the last four digits of the card number");
            }
            return to;
        }, "Activated");
    }

    public JsonNode freeze(UUID id, String reason) {
        return transition(id, (c, from) -> CardLifecycle.freeze(from), reason == null ? "Frozen" : reason);
    }

    public JsonNode unfreeze(UUID id) {
        return transition(id, (c, from) -> CardLifecycle.unfreeze(from), "Unfrozen");
    }

    public JsonNode block(UUID id, String status, String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("missing_parameter", "reason is required");
        }
        return transition(id, (c, from) -> CardLifecycle.block(from, status), reason);
    }

    public JsonNode close(UUID id, String reason) {
        return transition(id, (c, from) -> CardLifecycle.close(from), reason == null ? "Closed" : reason);
    }

    private JsonNode transition(UUID id, BiFunction<ObjectNode, String, String> next, String reason) {
        ObjectNode card = tx.execute(s -> {
            ObjectNode c = rows.lock("card", id);
            String from = Rows.str(c, "status");
            String to = next.apply(c, from);
            return applyStatus(id, from, to, reason);
        });
        syncTokens(id, Rows.str(card, "status"));
        return card;
    }

    /** Sets a new status with its timestamps, history row and event; caller holds the row lock. */
    ObjectNode applyStatus(UUID id, String from, String to, String reason) {
        rows.execute("""
                update card set status = ?, status_reason = ?, updated_by = ?,
                  activated_at = case when ? = 'ACTIVE' and activated_at is null then now() else activated_at end,
                  frozen_at = case when ? = 'FROZEN' then now() else frozen_at end,
                  closed_at = case when ? = 'CLOSED' then now() else closed_at end
                where id = ?""", to, reason, TenantContext.principal(), to, to, to, id);
        history(id, from, to, reason);
        ObjectNode card = rows.get("card", id);
        events.publish("CREATED".equals(from) && "ACTIVE".equals(to) ? "cards.card.activated" : "cards.card.status_changed",
                "card", card);
        return card;
    }

    /** Best effort, after commit: network tokens follow the card status. Never fails the card action. */
    void syncTokens(UUID cardId, String status) {
        if (!props.statusSyncEnabled()) {
            return;
        }
        try {
            peers.syncTokenStatus(cardId, status, Peers.key("card-status-sync", cardId, status + ":" + Ids.newId()));
        } catch (RuntimeException e) {
            log.warn("token status sync failed for card {} ({}): {}", cardId, status,
                    e instanceof ApiException a ? a.code() : e.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------ replace / renew

    private record ReplacePlan(ObjectNode old, IssuePlan issue, String oldNewStatus, boolean keepPan) {}

    public JsonNode replace(UUID id, JsonNode req, String clientKey) {
        String reason = Rows.str(req, "reason");
        if (reason == null) {
            throw ApiException.badRequest("missing_parameter", "reason is required");
        }
        boolean keepPan = Rows.bool(req, "keep_pan");
        ReplacePlan plan = tx.execute(s -> {
            ObjectNode old = rows.get("card", id);
            String oldNew = CardLifecycle.replace(Rows.str(old, "status"), reason);
            if (keepPan && Set.of("LOST", "STOLEN", "FRAUD").contains(reason)) {
                throw ApiException.unprocessable("keep_pan_not_allowed",
                        "a card replaced for " + reason + " must get a new PAN");
            }
            ObjectNode program = rows.get("card_program", Rows.uuid(old, "card_program_id"));
            ObjectNode product = rows.get("card_product", Rows.uuid(old, "card_product_id"));
            String formFactor = Rows.str(req, "form_factor") != null ? Rows.str(req, "form_factor") : Rows.str(old, "form_factor");
            requireFormFactor(product, formFactor);
            ObjectNode bureau = null;
            JsonNode address = null;
            if ("PHYSICAL".equals(formFactor)) {
                address = req.get("address");
                if (address == null || address.isNull()) {
                    address = rows.queryOne("select row_to_json(t)::text from card_order t where card_id = ? "
                            + "order by created_at desc limit 1", id).orElse(null);
                }
                bureau = bureauFor(product, address);
            }
            LocalDate expiry = rows.dbToday().plusYears(Rows.integer(product, "expiry_years"));
            IssuePlan ip = new IssuePlan(program, product, CardLifecycle.initialStatus(formFactor),
                    expiry.getMonthValue(), expiry.getYear(), bureau, address, Rows.str(req, "shipping_method"));
            return new ReplacePlan(old, ip, oldNew, keepPan);
        });
        ObjectNode old = plan.old();
        UUID newId = Ids.newId();
        JsonNode vault = keepPan
                ? peers.reissue(Rows.str(old, "pan_token"), newId, plan.issue().expiryMonth(), plan.issue().expiryYear(),
                        "card-replace:" + newId)
                : vaultNewPan(plan.issue().program(), newId, plan.issue().expiryMonth(), plan.issue().expiryYear());
        String formFactor = Rows.str(req, "form_factor") != null ? Rows.str(req, "form_factor") : Rows.str(old, "form_factor");
        ObjectNode replacement = tx.execute(s -> {
            ObjectNode locked = rows.lock("card", id);
            if (!Rows.str(locked, "status").equals(Rows.str(old, "status"))) {
                throw ApiException.conflict("concurrent_change", "the card changed while being replaced; retry");
            }
            ObjectNode values = rows.object();
            for (String f : COPIED) {
                if (old.hasNonNull(f)) {
                    values.set(f, old.get(f));
                }
            }
            values.put("form_factor", formFactor);
            int seq = keepPan ? Rows.integer(old, "sequence_number") + 1 : 0;
            persistCard(newId, values, vault, plan.issue(), id, seq, false);
            copyCardRules(id, newId);
            if (!plan.oldNewStatus().equals(Rows.str(locked, "status"))) {
                applyStatus(id, Rows.str(locked, "status"), plan.oldNewStatus(), "Replaced: " + reason);
            }
            ObjectNode r = rows.object();
            r.put("old_card_id", id.toString());
            r.put("new_card_id", newId.toString());
            r.put("reason", reason);
            r.put("keep_pan", keepPan);
            r.put("requested_by", TenantContext.principal());
            ObjectNode row = rows.insert("card_replacement", r);
            events.publish("cards.card.replaced", "card_replacement", row);
            return row;
        });
        syncTokens(id, plan.oldNewStatus());
        UUID replacementId = Rows.uuid(replacement, "id");
        if (props.replacementFee().signum() > 0 && !Set.of("EXPIRING", "FRAUD").contains(reason)) {
            try {
                JsonNode fee = peers.chargeFee(newId, Rows.uuid(old, "ledger_account_id"), "REPLACEMENT",
                        props.replacementFee(), Rows.str(plan.issue().program(), "currency"),
                        "Card replacement fee (" + reason + ")", Peers.key("card-replacement-fee", replacementId, null));
                replacement = tx.execute(s -> {
                    rows.execute("update card_replacement set fee_charged = true, fee_id = ?::uuid, updated_by = ? where id = ?",
                            Rows.str(fee, "id"), TenantContext.principal(), replacementId);
                    return rows.get("card_replacement", replacementId);
                });
            } catch (RuntimeException e) {
                log.warn("replacement fee could not be charged for replacement {}: {}", replacementId,
                        e instanceof ApiException a ? a.code() : e.getClass().getSimpleName());
            }
        }
        return replacement;
    }

    /** A replacement inherits the active card-level controls and spend limits of the old card. */
    private void copyCardRules(UUID oldId, UUID newId) {
        for (ObjectNode c : rows.query("select row_to_json(t)::text from card_control t where card_id = ? and is_active", oldId)) {
            ObjectNode v = rows.columns("card_control", c);
            v.put("card_id", newId.toString());
            events.publish("cards.card_control.changed", "card_control", rows.insert("card_control", v));
        }
        for (ObjectNode l : rows.query("select row_to_json(t)::text from card_spend_limit t where card_id = ? and is_active", oldId)) {
            ObjectNode v = rows.columns("card_spend_limit", l);
            v.put("card_id", newId.toString());
            events.publish("cards.card_spend_limit.changed", "card_spend_limit", rows.insert("card_spend_limit", v));
        }
    }

    public JsonNode renew(UUID id, String clientKey) {
        int[] expiry = tx.execute(s -> {
            ObjectNode c = rows.get("card", id);
            CardLifecycle.renew(Rows.str(c, "status"));
            ObjectNode product = rows.get("card_product", Rows.uuid(c, "card_product_id"));
            LocalDate e = rows.dbToday().plusYears(Rows.integer(product, "expiry_years"));
            return new int[] {e.getMonthValue(), e.getYear()};
        });
        ObjectNode before = rows.get("card", id);
        JsonNode vault = peers.reissue(Rows.str(before, "pan_token"), id, expiry[0], expiry[1],
                Peers.key("card-renew", id, clientKey));
        return tx.execute(s -> {
            ObjectNode c = rows.lock("card", id);
            CardLifecycle.renew(Rows.str(c, "status"));
            rows.execute("""
                    update card set pan_token = ?, pan_fingerprint = coalesce(?, pan_fingerprint), expiry_month = ?,
                      expiry_year = ?, sequence_number = sequence_number + 1, updated_by = ? where id = ?""",
                    Rows.str(vault, "pan_token"), Rows.str(vault, "pan_fingerprint"), expiry[0], expiry[1],
                    TenantContext.principal(), id);
            ObjectNode card = rows.get("card", id);
            events.publish("cards.card.renewed", "card", card);
            return card;
        });
    }

    // ------------------------------------------------------------------ physical order

    public JsonNode orderPhysical(UUID id, JsonNode req) {
        return tx.execute(s -> {
            ObjectNode c = rows.lock("card", id);
            CardLifecycle.orderPhysicalAllowed(Rows.str(c, "status"));
            if (!"VIRTUAL".equals(Rows.str(c, "form_factor"))) {
                throw ApiException.conflict("already_physical", "the card already has a physical form factor");
            }
            ObjectNode product = rows.get("card_product", Rows.uuid(c, "card_product_id"));
            requireFormFactor(product, "PHYSICAL");
            ObjectNode bureau = bureauFor(product, req.get("address"));
            ObjectNode order = createOrder(id, bureau, req.get("address"), Rows.str(c, "cardholder_name"),
                    Rows.str(req, "shipping_method"), null);
            rows.execute("update card set form_factor = 'PHYSICAL', updated_by = ? where id = ?", TenantContext.principal(), id);
            return order;
        });
    }

    // ------------------------------------------------------------------ PIN

    public JsonNode setPin(UUID id, String block, String keyId, String clientKey) {
        if (block == null || keyId == null) {
            throw ApiException.badRequest("missing_parameter", "encrypted_pin_block and key_id are required");
        }
        ObjectNode card = rows.get("card", id);
        CardLifecycle.pinAllowed(Rows.str(card, "status"));
        JsonNode result;
        try {
            result = peers.setPin(Rows.str(card, "pan_token"), block, keyId, Peers.key("card-pin", id, clientKey));
        } catch (ApiException e) {
            if (e.status() == 422) {
                tx.executeWithoutResult(s -> pinEvent(id, Rows.bool(card, "pin_set") ? "CHANGE" : "SET", false));
            }
            throw e;
        }
        String type = result.path("event_type").asString(Rows.bool(card, "pin_set") ? "CHANGE" : "SET");
        return tx.execute(s -> {
            rows.lock("card", id);
            rows.execute("update card set pin_set = true, pin_try_count = 0, updated_by = ? where id = ?",
                    TenantContext.principal(), id);
            return pinEvent(id, type, true);
        });
    }

    public JsonNode unblockPin(UUID id, String reason, String clientKey) {
        ObjectNode card = rows.get("card", id);
        CardLifecycle.pinAllowed(Rows.str(card, "status"));
        if (!Rows.bool(card, "pin_set")) {
            throw ApiException.unprocessable("pin_not_set", "the card has no PIN");
        }
        peers.unblockPin(Rows.str(card, "pan_token"), reason, Peers.key("card-pin-unblock", id, clientKey));
        return tx.execute(s -> {
            rows.lock("card", id);
            rows.execute("update card set pin_try_count = 0, updated_by = ? where id = ?", TenantContext.principal(), id);
            return pinEvent(id, "UNBLOCK", true);
        });
    }

    private ObjectNode pinEvent(UUID cardId, String type, boolean success) {
        ObjectNode e = rows.object();
        e.put("card_id", cardId.toString());
        e.put("event_type", type);
        e.put("channel", "API");
        e.put("success", success);
        ObjectNode row = rows.insert("card_pin_event", e);
        if (success) {
            events.publish("cards.card_pin.changed", "card_pin_event", row);
        }
        return row;
    }

    // ------------------------------------------------------------------ auth context

    /** Everything the authorisation hot path needs about a card, in one statement. */
    public JsonNode authContext(UUID id) {
        return rows.queryOne("""
                select json_build_object(
                  'as_of', now(),
                  'card', json_build_object('id', c.id, 'status', c.status, 'card_program_id', c.card_program_id,
                     'card_product_id', c.card_product_id, 'customer_id', c.customer_id,
                     'ledger_account_id', c.ledger_account_id, 'linked_account_type', c.linked_account_type,
                     'form_factor', c.form_factor, 'pan_token', c.pan_token, 'last4', c.last4,
                     'expiry_month', c.expiry_month, 'expiry_year', c.expiry_year, 'pin_set', c.pin_set),
                  'program', json_build_object('id', p.id, 'status', p.status, 'scheme', p.scheme,
                     'funding_model', p.funding_model, 'currency', p.currency, 'country', p.country,
                     'ledger_account_id', p.ledger_account_id, 'jit_webhook_url', p.jit_webhook_url,
                     'jit_timeout_ms', p.jit_timeout_ms, 'stand_in_enabled', p.stand_in_enabled,
                     'stand_in_max_amount', p.stand_in_max_amount::text, 'fraud_policy', p.fraud_policy,
                     'fraud_timeout_ms', p.fraud_timeout_ms, 'fraud_decline_score', p.fraud_decline_score,
                     'fx_markup_rate', p.fx_markup_rate::text, 'auth_hold_days', p.auth_hold_days),
                  'product', json_build_object('id', pr.id, 'status', pr.status, 'pin_policy', pr.pin_policy,
                     'contactless_enabled', pr.contactless_enabled, 'atm_enabled', pr.atm_enabled,
                     'ecom_enabled', pr.ecom_enabled, 'international_enabled', pr.international_enabled),
                  'controls', coalesce((select json_agg(x order by x.id) from card_control x
                     where (x.card_id = c.id or x.card_program_id = c.card_program_id) and x.is_active
                       and (x.valid_from is null or x.valid_from <= now()) and (x.valid_to is null or x.valid_to > now())),
                     '[]'::json),
                  'limits', coalesce((select json_agg(l order by l.id) from card_spend_limit l
                     where (l.card_id = c.id or l.card_program_id = c.card_program_id) and l.is_active), '[]'::json)
                )::text
                from card c join card_program p on p.id = c.card_program_id join card_product pr on pr.id = c.card_product_id
                where c.id = ?""", id)
                .orElseThrow(() -> ApiException.notFound("card", id));
    }

    // ------------------------------------------------------------------ expiry scan

    /**
     * Cards ACTIVE/FROZEN whose expiry month ends within the window get cards.card.expiring once per expiry (marker
     * {@code _expiry_notice} = yyyy-mm in metadata, so re-running the scan the same or the next day does not
     * re-notify); cards whose expiry month has ended become EXPIRED.
     */
    public JsonNode expiryScan(LocalDate asOfParam, Integer windowDays) {
        if (windowDays != null && (windowDays < 1 || windowDays > 365)) {
            throw ApiException.badRequest("invalid_parameter", "window_days must be between 1 and 365");
        }
        LocalDate asOf = asOfParam != null ? asOfParam : tx.execute(s -> rows.dbToday());
        int[] counts = new int[2];
        java.util.List<UUID> expired = new java.util.ArrayList<>();
        tx.executeWithoutResult(s -> {
            for (ObjectNode c : rows.query("""
                    select row_to_json(t)::text from card t
                    where t.status in ('CREATED','ACTIVE','FROZEN')
                      and (make_date(t.expiry_year, t.expiry_month, 1) + interval '1 month' - interval '1 day')::date < ?::date
                    for update""", asOf.toString())) {
                UUID id = Rows.uuid(c, "id");
                applyStatus(id, Rows.str(c, "status"), CardLifecycle.expire(Rows.str(c, "status")), "Card expired");
                expired.add(id);
                counts[1]++;
            }
            for (ObjectNode c : rows.query("""
                    select row_to_json(t)::text from card t join card_program p on p.id = t.card_program_id
                    where t.status in ('ACTIVE','FROZEN')
                      and (make_date(t.expiry_year, t.expiry_month, 1) + interval '1 month' - interval '1 day')::date
                          between ?::date and ?::date + coalesce(nullif(?, -1), p.renewal_window_days)
                      and coalesce(t.metadata->>'_expiry_notice', '') <> lpad(t.expiry_year::text, 4, '0') || '-' || lpad(t.expiry_month::text, 2, '0')
                    for update of t""", asOf.toString(), asOf.toString(), windowDays == null ? -1 : windowDays)) {
                UUID id = Rows.uuid(c, "id");
                rows.execute("""
                        update card set metadata = coalesce(metadata, '{}'::jsonb) || jsonb_build_object('_expiry_notice',
                          lpad(expiry_year::text, 4, '0') || '-' || lpad(expiry_month::text, 2, '0')) where id = ?""", id);
                events.publish("cards.card.expiring", "card", rows.get("card", id));
                counts[0]++;
            }
        });
        expired.forEach(id -> syncTokens(id, "EXPIRED"));
        ObjectNode out = rows.object();
        out.put("as_of", asOf.toString());
        if (windowDays != null) {
            out.put("window_days", windowDays);
        }
        out.put("expiring", counts[0]);
        out.put("expired", counts[1]);
        ArrayNode ids = out.putArray("expired_card_ids");
        expired.forEach(i -> ids.add(i.toString()));
        return out;
    }
}
