package io.meridian.customer.application;

import static io.meridian.customer.application.Json.text;
import static io.meridian.customer.application.Json.today;

import io.meridian.common.error.ApiException;
import io.meridian.customer.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Households, corporate groups and connected-exposure groups. A natural person belongs to at most one active
 * household; corporate groups hold organisations; the lead customer cannot be removed while it leads.
 */
@Service
public class GroupService {
    static final String T = "customer_group";
    static final String M = "customer_group_member";

    private final Rows rows;
    private final Guards guards;
    private final JsonMapper json;

    public GroupService(Rows rows, Guards guards, JsonMapper json) {
        this.rows = rows;
        this.guards = guards;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public JsonNode members(UUID groupId, Integer limit, String cursor) {
        rows.get(T, groupId);
        return rows.page("select row_to_json(t)::text from customer_group_member t where t.customer_group_id = ?",
                List.of(groupId), limit, cursor);
    }

    @Transactional
    public ObjectNode addMember(UUID groupId, JsonNode req) {
        ObjectNode group = rows.lock(T, groupId);
        UUID customerId = Json.uuid(req, "customer_id");
        ObjectNode customer = guards.openCustomer(customerId);
        LocalDate joined = Json.date(req, "joined_on");
        if (joined.isAfter(today().plusDays(1))) {
            throw ApiException.unprocessable("invalid_joined_on", "joined_on cannot be in the future");
        }
        String groupType = text(group, "group_type");
        boolean natural = CustomerService.NATURAL_PERSON.contains(text(customer, "customer_type"));
        if ("HOUSEHOLD".equals(groupType)) {
            if (!natural) {
                throw ApiException.unprocessable("invalid_member", "Only individuals can join a household");
            }
            int other = rows.jdbc().sql("""
                    select count(*) from customer_group_member m join customer_group g on g.id = m.customer_group_id
                    where m.customer_id = ? and g.group_type = 'HOUSEHOLD' and g.id <> ?
                    and (m.left_on is null or m.left_on >= current_date)""").params(customerId, groupId)
                    .query(Integer.class).single();
            if (other > 0) {
                throw ApiException.conflict("already_in_household", "Customer already belongs to another household");
            }
        } else if ("CORPORATE_GROUP".equals(groupType) && natural) {
            throw ApiException.unprocessable("invalid_member", "Only organisations can join a corporate group");
        }
        if (!rows.query("select row_to_json(t)::text from customer_group_member t where t.customer_group_id = ? and t.customer_id = ?",
                groupId, customerId).isEmpty()) {
            throw ApiException.conflict("already_member", "Customer is already a member of this group");
        }
        ObjectNode v = rows.object();
        v.put("customer_group_id", groupId.toString());
        v.put("customer_id", customerId.toString());
        v.put("joined_on", joined.toString());
        if (text(req, "member_role") != null) {
            v.put("member_role", text(req, "member_role"));
        }
        return rows.insert(M, v);
    }

    @Transactional
    public void removeMember(UUID groupId, UUID memberId) {
        ObjectNode group = rows.lock(T, groupId);
        ObjectNode member = rows.find(M, memberId)
                .filter(m -> groupId.toString().equals(text(m, "customer_group_id")))
                .orElseThrow(() -> ApiException.notFound("group member", memberId));
        if (text(member, "customer_id").equals(text(group, "lead_customer_id"))) {
            throw ApiException.conflict("lead_member", "Assign another lead customer before removing the lead");
        }
        rows.execute("delete from customer_group_member where id = ?", memberId);
    }

    @Transactional
    public void delete(UUID groupId) {
        rows.lock(T, groupId);
        int members = rows.jdbc().sql("select count(*) from customer_group_member where customer_group_id = ?")
                .param(groupId).query(Integer.class).single();
        if (members > 0) {
            throw ApiException.conflict("group_not_empty", "Remove all " + members + " members before deleting the group");
        }
        rows.execute("delete from customer_group where id = ?", groupId);
    }

    /**
     * Exposure view of a group from the customer master: members, their risk and tier, and declared income /
     * turnover by currency against the group limit. Product balances and credit exposure live in the account and
     * lending services and are not aggregated here.
     */
    @Transactional(readOnly = true)
    public JsonNode exposure(UUID groupId) {
        ObjectNode group = rows.get(T, groupId);
        List<ObjectNode> members = rows.query("""
                select json_build_object('customer_id', c.id, 'customer_number', c.customer_number,
                    'display_name', c.display_name, 'customer_type', c.customer_type, 'status', c.status,
                    'kyc_tier', c.kyc_tier, 'risk_level', c.risk_level, 'member_role', m.member_role,
                    'annual_income', c.annual_income, 'income_currency', c.income_currency,
                    'annual_turnover', c.annual_turnover)::text
                from customer_group_member m join customer c on c.id = m.customer_id
                where m.customer_group_id = ? and (m.left_on is null or m.left_on >= current_date)
                order by c.customer_number""", groupId);
        Map<String, BigDecimal> declared = new TreeMap<>();
        String highestRisk = null;
        List<String> order = List.of("LOW", "MEDIUM", "HIGH", "PROHIBITED");
        ArrayNode out = json.createArrayNode();
        for (ObjectNode m : members) {
            String ccy = text(m, "income_currency") == null ? "UNSPECIFIED" : text(m, "income_currency").trim();
            for (String f : new String[] {"annual_income", "annual_turnover"}) {
                if (text(m, f) != null) {
                    declared.merge(ccy, new BigDecimal(text(m, f)), BigDecimal::add);
                }
            }
            String risk = text(m, "risk_level");
            if (risk != null && (highestRisk == null || order.indexOf(risk) > order.indexOf(highestRisk))) {
                highestRisk = risk;
            }
            m.remove("annual_income");
            m.remove("annual_turnover");
            m.remove("income_currency");
            out.add(m);
        }
        ObjectNode r = json.createObjectNode();
        r.put("customer_group_id", groupId.toString());
        r.put("group_type", text(group, "group_type"));
        r.put("name", text(group, "name"));
        r.put("exposure_limit", text(group, "exposure_limit") == null ? null
                : new BigDecimal(text(group, "exposure_limit")).stripTrailingZeros().toPlainString());
        r.put("exposure_currency", text(group, "exposure_currency") == null ? null : text(group, "exposure_currency").trim());
        r.put("member_count", members.size());
        r.put("highest_risk_level", highestRisk);
        ObjectNode decl = r.putObject("declared_income_and_turnover");
        declared.forEach((ccy, amt) -> decl.put(ccy, amt.stripTrailingZeros().toPlainString()));
        r.set("members", out);
        r.put("balances_included", false);
        return r;
    }
}
