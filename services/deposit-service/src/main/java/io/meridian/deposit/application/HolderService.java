package io.meridian.deposit.application;

import static io.meridian.deposit.infrastructure.Rows.date;
import static io.meridian.deposit.infrastructure.Rows.dec;
import static io.meridian.deposit.infrastructure.Rows.str;
import static io.meridian.deposit.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.deposit.domain.MandateEvaluator;
import io.meridian.deposit.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Account holders (joint, minors with guardians, POA) and signing-mandate evaluation. */
@Service
public class HolderService {
    private static final Set<String> NEEDS_DOCUMENT = Set.of("POWER_OF_ATTORNEY", "GUARDIAN", "TRUSTEE");

    private final Rows rows;
    private final AccountSupport accounts;
    private final Events events;

    public HolderService(Rows rows, AccountSupport accounts, Events events) {
        this.rows = rows;
        this.accounts = accounts;
        this.events = events;
    }

    @Transactional
    public ObjectNode add(Object body) {
        ObjectNode v = rows.columns("account_holder", body);
        UUID accountId = uuid(v, "deposit_account_id");
        accounts.reference(accountId, "deposit_account_id");
        ObjectNode acc = accounts.lock(accountId);
        accounts.requireNotClosed(acc);
        String role = str(v, "role");
        LocalDate from = date(v, "valid_from");
        LocalDate to = date(v, "valid_to");
        if (from != null && to != null && to.isBefore(from)) {
            throw ApiException.unprocessable("invalid_validity", "valid_to must not be before valid_from");
        }
        BigDecimal limit = dec(v, "transaction_limit");
        if (limit != null && limit.signum() <= 0) {
            throw ApiException.unprocessable("invalid_transaction_limit", "transaction_limit must be positive");
        }
        if (NEEDS_DOCUMENT.contains(role) && !v.hasNonNull("document_id")) {
            throw ApiException.unprocessable("document_required", role + " holders need a supporting document_id");
        }
        List<ObjectNode> current = activeHolders(accountId);
        if ("PRIMARY".equals(role) && current.stream().anyMatch(h -> "PRIMARY".equals(str(h, "role")))) {
            throw ApiException.conflict("primary_exists", "The account already has an active PRIMARY holder");
        }
        if ("MINOR".equals(role) && current.stream().noneMatch(h -> "GUARDIAN".equals(str(h, "role")))) {
            throw ApiException.unprocessable("guardian_required", "Add an active GUARDIAN before adding a MINOR holder");
        }
        if ("MINOR".equals(role) || "BENEFICIARY".equals(role)) {
            v.put("can_transact", v.path("can_transact").asBoolean(false) && "MINOR".equals(role));
        }
        if (current.stream().anyMatch(h -> str(h, "customer_id").equals(str(v, "customer_id")) && role.equals(str(h, "role")))) {
            throw ApiException.conflict("duplicate", "Customer already holds this role on the account");
        }
        ObjectNode row = rows.insert("account_holder", v);
        events.publish("accounts.account_holder.added", "account_holder", row);
        return row;
    }

    @Transactional
    public void remove(UUID holderId) {
        ObjectNode h = rows.lock("account_holder", holderId);
        UUID accountId = uuid(h, "deposit_account_id");
        accounts.lock(accountId);
        List<ObjectNode> others = activeHolders(accountId).stream().filter(o -> !str(o, "id").equals(holderId.toString())).toList();
        String role = str(h, "role");
        if ("PRIMARY".equals(role) && others.stream().noneMatch(o -> Set.of("PRIMARY", "JOINT").contains(str(o, "role")))) {
            throw ApiException.unprocessable("last_owner", "The last owning holder of an account cannot be removed; close the account instead");
        }
        if ("GUARDIAN".equals(role) && others.stream().anyMatch(o -> "MINOR".equals(str(o, "role")))
                && others.stream().noneMatch(o -> "GUARDIAN".equals(str(o, "role")))) {
            throw ApiException.unprocessable("guardian_required", "A minor's only guardian cannot be removed");
        }
        rows.execute("delete from account_holder where id = ?", holderId);
        h.put("status", "REMOVED");
        events.publish("accounts.account_holder.removed", "account_holder", h);
    }

    private List<ObjectNode> activeHolders(UUID accountId) {
        return rows.query("""
                select row_to_json(h)::text from account_holder h where deposit_account_id = ? and status = 'ACTIVE'""",
                accountId);
    }

    /**
     * Which approvals an operation needs. Picks the most specific valid mandate (operation type, then ALL) whose
     * amount band contains the amount; without one the account's {@code mandate_rule} applies.
     */
    public ObjectNode evaluate(UUID accountId, String operationType, BigDecimal amount, Set<UUID> signatories) {
        ObjectNode acc = accounts.reference(accountId, "deposit_account_id");
        LocalDate today = accounts.today();
        List<ObjectNode> mandates = rows.query("""
                select row_to_json(m)::text from account_mandate m
                where deposit_account_id = ? and operation_type in (?, 'ALL') and valid_from <= ?
                  and (valid_to is null or valid_to >= ?)
                  and min_amount <= coalesce(?::numeric, min_amount) and (max_amount is null or ?::numeric is null or max_amount >= ?::numeric)
                order by case when operation_type = ? then 0 else 1 end, min_amount desc, created_at desc""",
                accountId, operationType, today, today, amount, amount, amount, operationType);
        JsonNode mandate = mandates.isEmpty() ? null : mandates.getFirst();
        String rule = mandate == null ? str(acc, "mandate_rule") : str(mandate, "rule");
        int required = mandate == null ? 1 : mandate.path("required_approvals").asInt(1);
        List<List<String>> combos = mandate == null ? List.of() : combinations(mandate.get("approver_groups"));
        List<MandateEvaluator.Holder> holders = new ArrayList<>();
        for (ObjectNode h : rows.query("select row_to_json(h)::text from account_holder h where deposit_account_id = ?",
                accountId)) {
            holders.add(new MandateEvaluator.Holder(uuid(h, "customer_id"), str(h, "role"), str(h, "status"),
                    h.path("can_transact").asBoolean(false), dec(h, "transaction_limit"), str(h, "signing_group"),
                    date(h, "valid_from"), date(h, "valid_to")));
        }
        MandateEvaluator.Result r = MandateEvaluator.evaluate(new MandateEvaluator.Mandate(rule, required, combos),
                holders, signatories, amount, today);
        ObjectNode out = rows.object();
        out.put("deposit_account_id", accountId.toString());
        out.put("operation_type", operationType);
        if (amount != null) {
            out.put("amount", amount.toPlainString());
        }
        out.put("allowed", r.allowed());
        out.put("rule", r.rule());
        if (mandate != null) {
            out.put("mandate_id", str(mandate, "id"));
        }
        out.put("required_approvals", r.requiredApprovals());
        out.put("approvals_counted", r.approvalsCounted());
        out.put("missing_approvals", Math.max(0, r.requiredApprovals() - r.approvalsCounted()));
        ArrayNode eligible = out.putArray("eligible_approvers");
        r.eligibleApprovers().forEach(id -> eligible.add(id.toString()));
        ArrayNode counted = out.putArray("counted_signatories");
        r.countedSignatories().forEach(id -> counted.add(id.toString()));
        ArrayNode ignored = out.putArray("ignored_signatories");
        r.ignoredSignatories().forEach(id -> ignored.add(id.toString()));
        if (!combos.isEmpty()) {
            out.set("required_groups", rows.json().valueToTree(combos));
        }
        ArrayNode reasons = out.putArray("reasons");
        r.reasons().forEach(reasons::add);
        return out;
    }

    /**
     * approver_groups is declared as a JSON object in the contract; accepted shapes:
     * {"any_of": [["A"], ["B","B"]]} (alternative combinations) or {"A": 1, "B": 2} (one combination with counts).
     */
    static List<List<String>> combinations(JsonNode groups) {
        List<List<String>> out = new ArrayList<>();
        if (groups == null || groups.isNull()) {
            return out;
        }
        JsonNode anyOf = groups.isArray() ? groups : groups.get("any_of");
        if (anyOf != null && anyOf.isArray()) {
            for (JsonNode combo : anyOf) {
                List<String> c = new ArrayList<>();
                combo.forEach(g -> c.add(g.asString()));
                out.add(c);
            }
            return out;
        }
        List<String> single = new ArrayList<>();
        for (String name : groups.propertyNames()) {
            int n = groups.get(name).asInt(1);
            for (int i = 0; i < n; i++) {
                single.add(name);
            }
        }
        if (!single.isEmpty()) {
            out.add(single);
        }
        return out;
    }
}
