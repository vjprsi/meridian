package io.meridian.rewards.application;

import static io.meridian.rewards.application.Json.text;

import io.meridian.common.error.ApiException;
import io.meridian.rewards.api.generated.model.Referral;
import io.meridian.rewards.api.generated.model.ReferralProgram;
import io.meridian.rewards.domain.Lifecycles;
import io.meridian.rewards.infrastructure.ExternalServices;
import io.meridian.rewards.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Refer-a-friend: one code per referrer and program, referee sign-up with fraud guards (self-referral, device reuse,
 * per-referrer cap), qualification by events within the program window, and payouts to both parties (cash through
 * the ledger within the program budget, points as BONUS entries) with exactly-once ledger keys per party.
 */
@Service
public class ReferralService {
    static final String PROGRAM = "referral_program";
    static final String T = "referral";

    enum EventType { ACCOUNT_OPENED, KYC_VERIFIED, FIRST_DEPOSIT, DEPOSIT, CARD_SPEND, DIRECT_DEPOSIT, LOAN_DISBURSED }

    private static final Set<String> COUNTED = Set.of("SIGNED_UP", "QUALIFYING", "QUALIFIED", "REWARDED");

    private final Rows rows;
    private final Patcher patcher;
    private final PointsLedger ledger;
    private final AccountService accounts;
    private final ExternalServices external;
    private final Events events;

    public ReferralService(Rows rows, Patcher patcher, PointsLedger ledger, AccountService accounts,
                           ExternalServices external, Events events) {
        this.rows = rows;
        this.patcher = patcher;
        this.ledger = ledger;
        this.accounts = accounts;
        this.external = external;
        this.events = events;
    }

    // ------------------------------------------------------------------ programs

    @Transactional
    public ObjectNode createProgram(Object body) {
        ObjectNode v = patcher.columns(PROGRAM, body);
        if (text(v, "code") != null) {
            v.put("code", text(v, "code").trim().toUpperCase());
        }
        validateProgram(v);
        if (rows.count("select count(*) from referral_program where code = ?", text(v, "code")) > 0) {
            throw ApiException.conflict("duplicate_code", "A referral program with code " + text(v, "code") + " exists");
        }
        v.put("status", "DRAFT");
        return rows.insert(PROGRAM, v);
    }

    @Transactional
    public ObjectNode updateProgram(UUID id, Object body, String ifMatch) {
        return patcher.patch(PROGRAM, id, body, ifMatch, (cur, merged) -> {
            if (!Objects.equals(text(cur, "code"), text(merged, "code"))) {
                throw ApiException.unprocessable("immutable_field", "code cannot change");
            }
            if ("ENDED".equals(text(cur, "status"))) {
                throw ApiException.conflict("invalid_transition", "An ended program cannot be changed");
            }
            validateProgram(merged);
        });
    }

    private void validateProgram(ObjectNode v) {
        Problems p = new Problems("invalid_referral_program");
        p.check(text(v, "code") != null && Problems.CODE.matcher(text(v, "code")).matches(), "code", "invalid_code",
                "code must be A-Z, 0-9, _ or -");
        JsonNode rules = v.get("qualification_rules");
        p.check(rules != null && rules.isArray() && !rules.isEmpty(), "qualification_rules", "required",
                "At least one qualification rule ({event, min_amount}) is required");
        if (rules != null && rules.isArray()) {
            for (JsonNode r : rules) {
                String ev = r.path("event").asString(null);
                boolean known = ev != null && EnumSet.allOf(EventType.class).stream().anyMatch(e -> e.name().equals(ev));
                p.check(known, "qualification_rules", "invalid_event", "Unknown qualification event " + ev);
                if (r.hasNonNull("min_amount")) {
                    try {
                        p.check(new BigDecimal(r.get("min_amount").asString()).signum() >= 0, "qualification_rules",
                                "invalid_amount", "min_amount must be >= 0");
                    } catch (NumberFormatException e) {
                        p.add("qualification_rules", "invalid_amount", "min_amount must be a decimal");
                    }
                }
            }
        }
        p.check(Json.integer(v, "qualify_within_days", 60) >= 1, "qualify_within_days", "out_of_range", "qualify_within_days must be >= 1");
        BigDecimal rr = Json.decimal(v, "referrer_reward");
        BigDecimal er = Json.decimal(v, "referee_reward");
        p.nonNegative(rr, "referrer_reward").nonNegative(er, "referee_reward").positive(Json.decimal(v, "budget_amount"), "budget_amount");
        boolean cash = (rr != null && rr.signum() > 0) || (er != null && er.signum() > 0);
        boolean points = Json.lng(v, "referrer_points", 0) > 0 || Json.lng(v, "referee_points", 0) > 0;
        p.check(cash || points, "referrer_reward", "no_reward", "The program needs a cash or points reward");
        if (cash) {
            p.check(text(v, "currency") != null, "currency", "required", "currency is required for cash rewards");
        }
        if (points) {
            p.check(text(v, "rewards_program_id") != null, "rewards_program_id", "required",
                    "rewards_program_id is required for points rewards");
        }
        LocalDate start = Json.date(v, "starts_on");
        LocalDate end = Json.date(v, "ends_on");
        p.check(start == null || end == null || !end.isBefore(start), "ends_on", "before_start", "ends_on is before starts_on");
        p.throwIfAny();
        if (text(v, "rewards_program_id") != null && rows.find("rewards_program", Json.uuid(v, "rewards_program_id")).isEmpty()) {
            throw ApiException.unprocessable("program_not_found", "Rewards program " + text(v, "rewards_program_id") + " does not exist");
        }
    }

    @Transactional
    public ObjectNode activateProgram(UUID id) {
        return programStatus(id, "activate", null, "engagement.referral_program.activated");
    }

    @Transactional
    public ObjectNode pauseProgram(UUID id, String reason) {
        return programStatus(id, "pause", reason, "engagement.referral_program.paused");
    }

    @Transactional
    public ObjectNode endProgram(UUID id, String reason) {
        return programStatus(id, "end", reason, "engagement.referral_program.ended");
    }

    private ObjectNode programStatus(UUID id, String action, String reason, String event) {
        ObjectNode p = rows.lock(PROGRAM, id);
        ObjectNode v = rows.object();
        v.put("status", Lifecycles.REFERRAL_PROGRAM.apply(action, text(p, "status")));
        if (reason != null) {
            v.put("status_reason", Json.truncate(reason, 255));
        } else if ("activate".equals(action)) {
            v.putNull("status_reason");
        }
        if ("end".equals(action) && text(p, "ends_on") == null) {
            v.put("ends_on", rows.now().toLocalDate().toString());
        }
        ObjectNode row = rows.update(PROGRAM, id, v);
        events.publish(event, PROGRAM, row, ReferralProgram.class);
        return row;
    }

    private ObjectNode activeProgram(UUID id) {
        ObjectNode p = rows.queryOne("select row_to_json(t)::text from referral_program t where t.id = ?", id)
                .orElseThrow(() -> ApiException.unprocessable("program_not_found", "Referral program " + id + " does not exist"));
        LocalDate today = rows.now().toLocalDate();
        if (!"ACTIVE".equals(text(p, "status")) || today.isBefore(Json.date(p, "starts_on"))
                || (Json.date(p, "ends_on") != null && today.isAfter(Json.date(p, "ends_on")))) {
            throw ApiException.unprocessable("program_not_active", "The referral program is not running");
        }
        return p;
    }

    // ------------------------------------------------------------------ codes and invitations

    @Transactional
    public ObjectNode code(UUID programId, UUID customerId, String device) {
        activeProgram(programId);
        Optional<ObjectNode> holder = holder(programId, customerId);
        if (holder.isPresent()) {
            return holder.get();
        }
        ObjectNode v = rows.object();
        v.put("referral_program_id", programId.toString());
        v.put("referrer_customer_id", customerId.toString());
        v.put("referral_code", uniqueCode());
        v.put("is_code_holder", true);
        v.put("status", "INVITED");
        if (device != null && !device.isBlank()) {
            v.put("device_hash", Lifecycles.sha256(device.trim()));
        }
        ObjectNode row = rows.insert(T, v);
        events.publish("engagement.referral.invited", T, row, Referral.class);
        return row;
    }

    @Transactional
    public ObjectNode invite(Object body) {
        ObjectNode v = patcher.columns(T, body);
        UUID programId = Json.uuid(v, "referral_program_id");
        UUID referrer = Json.uuid(v, "referrer_customer_id");
        ObjectNode holder = holder(programId, referrer).orElseGet(() -> code(programId, referrer, null));
        activeProgram(programId);
        ObjectNode n = rows.object();
        n.put("referral_program_id", programId.toString());
        n.put("referrer_customer_id", referrer.toString());
        n.put("referral_code", text(holder, "referral_code"));
        n.put("is_code_holder", false);
        n.put("status", "INVITED");
        if (text(v, "referee_contact") != null) {
            n.put("referee_contact", text(v, "referee_contact").trim());
        }
        if (text(v, "referrer_account_id") != null) {
            n.put("referrer_account_id", text(v, "referrer_account_id"));
        }
        ObjectNode row = rows.insert(T, n);
        events.publish("engagement.referral.invited", T, row, Referral.class);
        return row;
    }

    private Optional<ObjectNode> holder(UUID programId, UUID customerId) {
        return rows.queryOne("""
                select row_to_json(t)::text from referral t where t.referral_program_id = ? and t.referrer_customer_id = ?
                  and t.is_code_holder""", programId, customerId);
    }

    private String uniqueCode() {
        for (int i = 0; i < 20; i++) {
            String c = Lifecycles.code(8);
            if (rows.count("select count(*) from referral where referral_code = ?", c) == 0) {
                return c;
            }
        }
        throw new ApiException(500, "code_generation_failed", "Could not generate a unique referral code");
    }

    // ------------------------------------------------------------------ sign-up (fraud guards)

    @Transactional
    public ObjectNode signUp(JsonNode b) {
        String code = Json.required(b, "referral_code").toUpperCase();
        UUID referee = Json.uuid(b, "referee_customer_id");
        ObjectNode holder = rows.queryOne("select row_to_json(t)::text from referral t where t.referral_code = ? and t.is_code_holder", code)
                .orElseThrow(() -> ApiException.unprocessable("invalid_referral_code", "Unknown referral code " + code));
        UUID programId = Json.uuid(holder, "referral_program_id");
        ObjectNode program = activeProgram(programId);
        rows.lock(T, Json.uuid(holder, "id"));
        if (rows.count("""
                select count(*) from referral where referral_program_id = ? and referee_customer_id = ? and not is_code_holder""",
                programId, referee) > 0) {
            throw ApiException.conflict("already_referred", "The customer has already signed up with a referral in this program");
        }
        UUID referrer = Json.uuid(holder, "referrer_customer_id");
        String contact = text(b, "referee_contact");
        Optional<ObjectNode> invited = contact == null ? Optional.empty() : rows.queryOne("""
                select row_to_json(t)::text from referral t where t.referral_program_id = ? and t.referrer_customer_id = ?
                  and not t.is_code_holder and t.status = 'INVITED' and lower(t.referee_contact) = lower(?)
                order by t.id limit 1 for update""", programId, referrer, contact.trim());
        String device = text(b, "device_fingerprint");
        String hash = device == null || device.isBlank() ? null : Lifecycles.sha256(device.trim());
        OffsetDateTime now = rows.now();

        String rejection = null;
        if (referrer.equals(referee)) {
            rejection = "self_referral";
        } else if (hash != null && (hash.equals(text(holder, "device_hash")) || rows.count("""
                select count(*) from referral where referral_program_id = ? and device_hash = ?
                  and (referee_customer_id is null or referee_customer_id <> ?) and referrer_customer_id <> ?""",
                programId, hash, referee, referrer) > 0 || rows.count("""
                select count(*) from referral where referral_program_id = ? and device_hash = ? and not is_code_holder
                  and referee_customer_id is not null and referee_customer_id <> ?""", programId, hash, referee) > 0)) {
            rejection = "device_reuse";
        } else if (program.hasNonNull("max_referrals_per_customer") && rows.count("""
                select count(*) from referral where referral_program_id = ? and referrer_customer_id = ? and not is_code_holder
                  and status in ('SIGNED_UP', 'QUALIFYING', 'QUALIFIED', 'REWARDED')""", programId, referrer)
                >= program.get("max_referrals_per_customer").asInt()) {
            rejection = "referral_cap_reached";
        }
        ObjectNode v = rows.object();
        v.put("referee_customer_id", referee.toString());
        v.put("signed_up_at", now.toString());
        v.put("qualify_by", now.plusDays(Json.integer(program, "qualify_within_days", 60)).toString());
        if (hash != null) {
            v.put("device_hash", hash);
        }
        if (text(b, "referee_account_id") != null) {
            v.put("referee_account_id", text(b, "referee_account_id"));
        }
        if (contact != null) {
            v.put("referee_contact", contact.trim());
        }
        v.put("fraud_score", rejection == null ? (hash == null ? "0.2" : "0") : "1");
        v.put("status", rejection == null ? "SIGNED_UP" : "REJECTED");
        if (rejection != null) {
            v.put("rejection_reason", rejection);
        }
        ObjectNode row;
        if (invited.isPresent()) {
            row = rows.update(T, Json.uuid(invited.get(), "id"), v);
        } else {
            v.put("referral_program_id", programId.toString());
            v.put("referrer_customer_id", referrer.toString());
            v.put("referral_code", code);
            v.put("is_code_holder", false);
            row = rows.insert(T, v);
        }
        events.publish(rejection == null ? "engagement.referral.signed_up" : "engagement.referral.rejected", T, row, Referral.class);
        return row;
    }

    // ------------------------------------------------------------------ qualification

    @Transactional
    public JsonNode qualificationEvent(JsonNode b) {
        UUID referee = Json.uuid(b, "referee_customer_id");
        String event = Json.required(b, "event_type");
        BigDecimal amount = Json.decimal(b, "amount");
        OffsetDateTime now = rows.now();
        OffsetDateTime at = Json.ts(b, "occurred_at") == null ? now : Json.ts(b, "occurred_at");
        ObjectNode out = rows.object();
        ArrayNode list = out.putArray("referrals");
        for (ObjectNode r : rows.query("""
                select row_to_json(t)::text from referral t where t.referee_customer_id = ? and not t.is_code_holder
                  and t.status in ('SIGNED_UP', 'QUALIFYING') order by t.id for update""", referee)) {
            UUID id = Json.uuid(r, "id");
            ObjectNode program = rows.get(PROGRAM, Json.uuid(r, "referral_program_id"));
            ObjectNode item = list.addObject().put("referral_id", id.toString());
            if (Json.ts(r, "qualify_by") != null && at.isAfter(Json.ts(r, "qualify_by"))) {
                item.put("status", text(r, "status")).put("outcome", "after_deadline");
                continue;
            }
            ArrayNode progress = r.path("qualification_progress").isArray()
                    ? ((ArrayNode) r.get("qualification_progress")).deepCopy() : rows.object().arrayNode();
            Set<Integer> met = new java.util.HashSet<>();
            progress.forEach(p -> met.add(p.path("rule_index").asInt(-1)));
            JsonNode rules = program.get("qualification_rules");
            boolean matched = false;
            for (int i = 0; i < rules.size(); i++) {
                JsonNode rule = rules.get(i);
                if (met.contains(i) || !event.equals(rule.path("event").asString())) {
                    continue;
                }
                if (rule.hasNonNull("min_amount") && (amount == null || amount.compareTo(new BigDecimal(rule.get("min_amount").asString())) < 0)) {
                    continue;
                }
                ObjectNode p = progress.addObject();
                p.put("rule_index", i);
                p.put("event", event);
                p.put("met_at", at.toString());
                if (amount != null) {
                    p.put("amount", amount.toPlainString());
                }
                met.add(i);
                matched = true;
            }
            ObjectNode v = rows.object();
            v.set("qualification_progress", progress);
            if (met.size() >= rules.size()) {
                v.put("status", Lifecycles.REFERRAL.apply("qualify", text(r, "status")));
                v.put("qualified_at", now.toString());
                ObjectNode row = rows.update(T, id, v);
                events.publish("engagement.referral.qualified", T, row, Referral.class);
                row = payLocked(row);
                item.put("status", text(row, "status")).put("outcome", "qualified");
            } else {
                if (matched) {
                    v.put("status", Lifecycles.REFERRAL.apply("progress", text(r, "status")));
                }
                ObjectNode row = rows.update(T, id, v);
                item.put("status", text(row, "status")).put("outcome", matched ? "progress" : "no_match");
            }
        }
        out.put("count", list.size());
        return out;
    }

    @Transactional
    public ObjectNode qualify(UUID id, String reason) {
        ObjectNode r = rows.lock(T, id);
        if (r.path("is_code_holder").asBoolean()) {
            throw ApiException.conflict("invalid_transition", "A referral code record cannot qualify");
        }
        ObjectNode v = rows.object();
        v.put("status", Lifecycles.REFERRAL.apply("qualify", text(r, "status")));
        v.put("qualified_at", rows.now().toString());
        ObjectNode row = rows.update(T, id, v);
        events.publish("engagement.referral.qualified", T, row, Referral.class);
        return payLocked(row);
    }

    @Transactional
    public ObjectNode pay(UUID id) {
        ObjectNode r = rows.lock(T, id);
        Lifecycles.REFERRAL.apply("pay", text(r, "status"));
        return payLocked(r);
    }

    @Transactional
    public ObjectNode reject(UUID id, String reason) {
        ObjectNode r = rows.lock(T, id);
        ObjectNode v = rows.object();
        v.put("status", Lifecycles.REFERRAL.apply("reject", text(r, "status")));
        v.put("rejection_reason", Json.truncate(reason, 255));
        ObjectNode row = rows.update(T, id, v);
        events.publish("engagement.referral.rejected", T, row, Referral.class);
        return row;
    }

    /** Pays the outstanding parts of both rewards; REWARDED when all are paid, else stays QUALIFIED with the reason. */
    private ObjectNode payLocked(ObjectNode r) {
        UUID id = Json.uuid(r, "id");
        ObjectNode program = rows.lock(PROGRAM, Json.uuid(r, "referral_program_id"));
        ObjectNode v = rows.object();
        List<String> failures = new ArrayList<>();
        BigDecimal spent = Json.decimal(program, "spent_amount") == null ? BigDecimal.ZERO : Json.decimal(program, "spent_amount");
        BigDecimal budget = Json.decimal(program, "budget_amount");
        for (String party : List.of("referrer", "referee")) {
            BigDecimal cash = Json.decimal(program, party + "_reward");
            if (cash != null && cash.signum() > 0 && text(r, party + "_payout_journal_entry_id") == null) {
                UUID account = Json.uuid(r, party + "_account_id") != null ? Json.uuid(r, party + "_account_id")
                        : defaultAccount(Json.uuid(r, party + "_customer_id"), Json.uuid(program, "rewards_program_id"));
                try {
                    if (account == null) {
                        throw ApiException.unprocessable("no_credit_account", "No " + party + "_account_id to credit");
                    }
                    if (budget != null && spent.add(cash).compareTo(budget) > 0) {
                        throw ApiException.unprocessable("budget_exhausted", "The program budget is exhausted");
                    }
                    ExternalServices.CustomerAccount acct = external.customerAccount(account);
                    UUID journal = external.payout(Json.uuid(program, "ledger_id"), Json.uuid(program, "funding_ledger_account_id"),
                            acct.ledgerAccountId(), cash, text(program, "currency"), "REFERRAL", id,
                            Json.truncate("Referral reward (" + party + ") " + text(program, "code"), 500),
                            "rewards-referral:" + id + ":" + party);
                    v.put(party + "_payout_journal_entry_id", journal.toString());
                    spent = spent.add(cash);
                } catch (ApiException e) {
                    failures.add(party + ": " + e.code() + " - " + e.getMessage());
                }
            }
            long points = Json.lng(program, party + "_points", 0);
            if (points > 0 && text(r, party + "_points_entry_id") == null) {
                try {
                    UUID customer = Json.uuid(r, party + "_customer_id");
                    UUID rp = Json.uuid(program, "rewards_program_id");
                    ObjectNode acct = rows.queryOne("""
                            select row_to_json(t)::text from points_account t where t.rewards_program_id = ? and t.customer_id = ?""",
                            rp, customer).orElseGet(() -> accounts.enrol(rp, customer, null, null));
                    ObjectNode locked = ledger.lockAccount(Json.uuid(acct, "id"));
                    AccountService.requireActive(locked);
                    ObjectNode rewards = rows.get("rewards_program", rp);
                    ObjectNode entry = ledger.append(locked, new PointsLedger.Move("BONUS", points, "referral:" + id + ":" + party)
                            .source("REFERRAL", id).reason("Referral reward (" + party + ")")
                            .expires(PointsLedger.expiry(rewards, rows.now().toLocalDate())).event("engagement.points.earned"));
                    v.put(party + "_points_entry_id", text(entry, "id"));
                } catch (ApiException e) {
                    failures.add(party + " points: " + e.code() + " - " + e.getMessage());
                }
            }
        }
        if (spent.compareTo(Json.decimal(program, "spent_amount") == null ? BigDecimal.ZERO : Json.decimal(program, "spent_amount")) != 0) {
            ObjectNode pv = rows.object();
            pv.put("spent_amount", spent.toPlainString());
            rows.update(PROGRAM, Json.uuid(program, "id"), pv);
        }
        if (failures.isEmpty()) {
            v.put("status", "REWARDED");
            v.put("rewarded_at", rows.now().toString());
            v.putNull("payout_failure_reason");
            ObjectNode row = rows.update(T, id, v);
            events.publish("engagement.referral.rewarded", T, row, Referral.class);
            return row;
        }
        v.put("payout_failure_reason", Json.truncate(failures.stream().collect(Collectors.joining("; ")), 500));
        ObjectNode row = rows.update(T, id, v);
        events.publish("engagement.referral.payout_failed", T, row, Referral.class);
        return row;
    }

    /** The customer's default credit account from their points account (linked program first); null when none. */
    private UUID defaultAccount(UUID customerId, UUID rewardsProgramId) {
        if (customerId == null) {
            return null;
        }
        return rows.jdbc().sql("""
                select default_credit_account_id from points_account where customer_id = ? and default_credit_account_id is not null
                order by (rewards_program_id = ?) desc nulls last, id limit 1""")
                .params(customerId, rewardsProgramId).query(UUID.class).optional().orElse(null);
    }

    // ------------------------------------------------------------------ expiry (EOD)

    /** Expires referrals whose deadline passed by the end of the business date; returns the count. */
    public int expire(LocalDate businessDate) {
        OffsetDateTime cutoff = businessDate.plusDays(1).atStartOfDay().atOffset(java.time.ZoneOffset.UTC);
        int n = 0;
        for (ObjectNode r : rows.query("""
                select row_to_json(t)::text from referral t where not t.is_code_holder
                  and t.status in ('SIGNED_UP', 'QUALIFYING') and t.qualify_by < ? for update""", cutoff)) {
            ObjectNode v = rows.object();
            v.put("status", Lifecycles.REFERRAL.apply("expire", text(r, "status")));
            ObjectNode row = rows.update(T, Json.uuid(r, "id"), v);
            events.publish("engagement.referral.expired", T, row, Referral.class);
            n++;
        }
        return n;
    }

    static boolean counted(String status) {
        return COUNTED.contains(status);
    }
}
