package io.meridian.customer.application;

import static io.meridian.customer.application.Json.text;

import io.meridian.common.error.ApiException;
import io.meridian.customer.api.generated.model.Consent;
import io.meridian.customer.api.generated.model.Customer;
import io.meridian.customer.api.generated.model.CustomerAddress;
import io.meridian.customer.api.generated.model.CustomerContact;
import io.meridian.customer.api.generated.model.CustomerIdentifier;
import io.meridian.customer.api.generated.model.CustomerRelationship;
import io.meridian.customer.domain.DuplicateScorer;
import io.meridian.customer.domain.KycTierPolicy;
import io.meridian.customer.domain.Normalizer;
import io.meridian.customer.domain.OwnershipTree;
import io.meridian.customer.infrastructure.Crypto;
import io.meridian.customer.infrastructure.Rows;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Read models: search, duplicate detection, single customer view and ownership structure. */
@Service
@Transactional(readOnly = true)
public class CustomerQueryService {
    private final Rows rows;
    private final Crypto crypto;
    private final JsonMapper json;

    public CustomerQueryService(Rows rows, Crypto crypto, JsonMapper json) {
        this.rows = rows;
        this.crypto = crypto;
        this.json = json;
    }

    // ------------------------------------------------------------------ search

    /**
     * Case-insensitive search over customer number, external reference, names, email, phone and identifiers
     * (identifiers match exactly via their keyed hash, never by decrypting).
     */
    public JsonNode search(String q, Integer limit, String cursor) {
        if (q == null || q.trim().length() < 2) {
            throw ApiException.badRequest("query_too_short", "q must be at least 2 characters");
        }
        String term = q.trim();
        String pattern = "%" + Normalizer.likeLiteral(term.toLowerCase(java.util.Locale.ROOT)) + "%";
        String phone = Normalizer.phone(term);
        String hash = crypto.identifierHash(Normalizer.identifier(term));
        String sql = """
                select row_to_json(t)::text from customer t where (
                  lower(t.customer_number) = lower(?) or t.external_ref = ?
                  or lower(t.display_name) like ? or lower(coalesce(t.legal_name, '')) like ?
                  or lower(coalesce(t.trading_name, '')) like ?
                  or lower(concat_ws(' ', t.first_name, t.middle_name, t.last_name)) like ?
                  or lower(concat_ws(' ', t.first_name, t.last_name)) like ?
                  or exists (select 1 from customer_contact k where k.customer_id = t.id
                             and (lower(k.email) = lower(?) or k.phone = ?))
                  or exists (select 1 from customer_identifier i where i.customer_id = t.id and i.value_hash = ?))""";
        return rows.page(sql, List.of(term, term, pattern, pattern, pattern, pattern, pattern, term, phone, hash), limit, cursor);
    }

    // ------------------------------------------------------------------ duplicates

    public JsonNode duplicates(JsonNode req, Integer limit) {
        String first = blankToNull(Normalizer.name(text(req, "first_name")));
        String last = blankToNull(Normalizer.name(text(req, "last_name")));
        String dob = text(req, "date_of_birth");
        String email = Normalizer.email(text(req, "email"));
        String phone = Normalizer.phone(text(req, "phone"));
        String identifier = text(req, "identifier_value");
        String idHash = identifier == null || identifier.isBlank() ? null : crypto.identifierHash(Normalizer.identifier(identifier));
        String reg = text(req, "registration_number") == null ? null : Normalizer.identifier(text(req, "registration_number"));
        if (first == null && last == null && dob == null && email == null && phone == null && idHash == null && reg == null) {
            throw ApiException.unprocessable("no_criteria", "Provide at least one attribute to check");
        }
        List<ObjectNode> candidates = rows.query("""
                select row_to_json(t)::text from customer t
                where t.merged_into_customer_id is null and (
                  (cast(? as text) is not null and lower(t.last_name) = cast(? as text))
                  or (cast(? as date) is not null and t.date_of_birth = cast(? as date))
                  or (cast(? as text) is not null and upper(regexp_replace(coalesce(t.registration_number, ''), '[\\s./-]', '', 'g')) = cast(? as text))
                  or t.id in (select customer_id from customer_contact where lower(email) = cast(? as text) or phone = cast(? as text))
                  or t.id in (select customer_id from customer_identifier where value_hash = cast(? as text)))
                order by t.id desc limit 500""", last, last, dob, dob, reg, reg, email, phone, idHash);
        Map<UUID, Set<String>> phones = new HashMap<>();
        Map<UUID, Set<String>> emails = new HashMap<>();
        Map<UUID, Set<String>> hashes = new HashMap<>();
        if (!candidates.isEmpty()) {
            UUID[] ids = candidates.stream().map(c -> UUID.fromString(c.get("id").asString())).toArray(UUID[]::new);
            rows.jdbc().sql("select customer_id, phone, lower(email) from customer_contact where customer_id = any(?)")
                    .param(ids).query((rs, i) -> {
                        UUID id = rs.getObject(1, UUID.class);
                        if (rs.getString(2) != null) {
                            phones.computeIfAbsent(id, k -> new HashSet<>()).add(rs.getString(2));
                        }
                        if (rs.getString(3) != null) {
                            emails.computeIfAbsent(id, k -> new HashSet<>()).add(rs.getString(3));
                        }
                        return id;
                    }).list();
            rows.jdbc().sql("select customer_id, value_hash from customer_identifier where customer_id = any(?)")
                    .param(ids).query((rs, i) -> hashes.computeIfAbsent(rs.getObject(1, UUID.class), k -> new HashSet<>())
                            .add(rs.getString(2))).list();
        }
        DuplicateScorer.Probe probe = new DuplicateScorer.Probe(first, last, dob, phone, email, idHash, reg);
        List<ObjectNode> matches = new ArrayList<>();
        for (ObjectNode c : candidates) {
            UUID id = UUID.fromString(c.get("id").asString());
            DuplicateScorer.Candidate cand = new DuplicateScorer.Candidate(
                    blankToNull(Normalizer.name(text(c, "first_name"))), blankToNull(Normalizer.name(text(c, "last_name"))),
                    Normalizer.name(text(c, "display_name")), text(c, "date_of_birth"),
                    text(c, "registration_number") == null ? null : Normalizer.identifier(text(c, "registration_number")),
                    phones.getOrDefault(id, Set.of()), emails.getOrDefault(id, Set.of()), hashes.getOrDefault(id, Set.of()));
            DuplicateScorer.Score s = DuplicateScorer.score(probe, cand);
            if (s.score() >= DuplicateScorer.THRESHOLD) {
                ObjectNode item = (ObjectNode) json.valueToTree(rows.read(c, Customer.class));
                item.put("match_score", s.score());
                item.set("match_reasons", json.valueToTree(s.reasons()));
                matches.add(item);
            }
        }
        matches.sort((a, b) -> Integer.compare(b.get("match_score").asInt(), a.get("match_score").asInt()));
        int n = io.meridian.common.page.Cursor.limit(limit);
        ObjectNode page = json.createObjectNode();
        page.set("data", json.valueToTree(matches.size() > n ? matches.subList(0, n) : matches));
        page.put("has_more", matches.size() > n);
        page.putNull("next_cursor");
        return page;
    }

    // ------------------------------------------------------------------ single customer view

    public JsonNode view(UUID id) {
        ObjectNode c = rows.get("customer", id);
        ObjectNode view = json.createObjectNode();
        view.set("customer", json.valueToTree(rows.read(c, Customer.class)));
        view.set("addresses", models("customer_address", "customer_id", id, CustomerAddress.class, "valid_to is null or valid_to >= current_date"));
        view.set("contacts", models("customer_contact", "customer_id", id, CustomerContact.class, "true"));
        ArrayNode identifiers = json.createArrayNode();
        for (ObjectNode i : rows.query("select row_to_json(t)::text from customer_identifier t where t.customer_id = ? order by t.id", id)) {
            ObjectNode m = (ObjectNode) json.valueToTree(rows.read(i, CustomerIdentifier.class));
            m.put("identifier_value", mask(text(i, "identifier_value")));
            identifiers.add(m);
        }
        view.set("identifiers", identifiers);
        ArrayNode relationships = json.createArrayNode();
        for (ObjectNode r : rows.query("""
                select (row_to_json(t)::jsonb || jsonb_build_object(
                    'counterparty', jsonb_build_object('id', o.id, 'customer_number', o.customer_number,
                        'display_name', o.display_name, 'customer_type', o.customer_type, 'status', o.status),
                    'direction', case when t.customer_id = ? then 'SUBJECT' else 'RELATED' end))::text
                from customer_relationship t
                join customer o on o.id = case when t.customer_id = ? then t.related_customer_id else t.customer_id end
                where (t.customer_id = ? or t.related_customer_id = ?) and (t.valid_to is null or t.valid_to >= current_date)
                order by t.id""", id, id, id, id)) {
            ObjectNode m = (ObjectNode) json.valueToTree(rows.read(r, CustomerRelationship.class));
            m.set("counterparty", r.get("counterparty"));
            m.set("direction", r.get("direction"));
            relationships.add(m);
        }
        view.set("relationships", relationships);
        view.set("groups", json.valueToTree(rows.query("""
                select json_build_object('customer_group_id', g.id, 'name', g.name, 'group_type', g.group_type,
                    'member_role', m.member_role, 'joined_on', m.joined_on, 'is_lead', g.lead_customer_id = m.customer_id)::text
                from customer_group_member m join customer_group g on g.id = m.customer_group_id
                where m.customer_id = ? and (m.left_on is null or m.left_on >= current_date) order by g.name""", id)));
        view.set("segments", json.valueToTree(rows.query("""
                select json_build_object('customer_segment_id', s.id, 'code', s.code, 'name', s.name,
                    'assigned_by_rule', a.assigned_by_rule, 'valid_from', a.valid_from)::text
                from customer_segment_assignment a join customer_segment s on s.id = a.customer_segment_id
                where a.customer_id = ? and (a.valid_to is null or a.valid_to >= current_date) order by s.priority""", id)));
        view.set("consents", models("consent", "customer_id", id, Consent.class, "status = 'GIVEN'"));
        view.set("devices", json.valueToTree(rows.query("""
                select json_build_object('id', t.id, 'platform', t.platform, 'model', t.model, 'name', t.name,
                    'status', t.status, 'last_seen_at', t.last_seen_at, 'bound_at', t.bound_at)::text
                from customer_device t where t.customer_id = ? and t.status <> 'REVOKED' order by t.id""", id)));
        view.set("credentials", json.valueToTree(rows.query("""
                select json_build_object('credential_type', credential_type, 'status', status, 'count', count(*))::text
                from customer_credential where customer_id = ? and status <> 'REVOKED'
                group by credential_type, status""", id)));
        ObjectNode kyc = view.putObject("kyc");
        String tier = text(c, "kyc_tier");
        kyc.put("kyc_tier", tier);
        kyc.set("tier_limits", json.valueToTree(KycTierPolicy.limits(tier).asMap()));
        kyc.set("risk_level", c.get("risk_level"));
        kyc.set("is_pep", c.get("is_pep"));
        kyc.set("pending_tier", c.get("pending_tier"));
        kyc.set("tier_approval_request_id", c.get("tier_approval_request_id"));
        kyc.set("last_tier_change", rows.query("""
                select row_to_json(t)::text from customer_kyc_tier_history t where t.customer_id = ?
                order by t.effective_at desc limit 1""", id).stream().findFirst().map(JsonNode.class::cast)
                .orElse(tools.jackson.databind.node.JsonNodeFactory.instance.nullNode()));
        ObjectNode privacy = view.putObject("privacy");
        privacy.put("open_requests", rows.jdbc().sql("""
                select count(*) from privacy_request where customer_id = ?
                and status in ('RECEIVED', 'VERIFYING_IDENTITY', 'IN_PROGRESS')""").param(id).query(Integer.class).single());
        return view;
    }

    private ArrayNode models(String table, String fk, UUID id, Class<?> model, String condition) {
        ArrayNode out = json.createArrayNode();
        for (ObjectNode r : rows.query("select row_to_json(t)::text from " + table + " t where t." + fk + " = ? and ("
                + condition + ") order by t.id", id)) {
            out.add(json.valueToTree(rows.read(r, model)));
        }
        return out;
    }

    static String mask(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 4 ? "****" : "*".repeat(value.length() - 4) + value.substring(value.length() - 4);
    }

    // ------------------------------------------------------------------ relationships and history

    public JsonNode relationships(UUID id, Integer limit, String cursor) {
        rows.get("customer", id);
        return rows.page("select row_to_json(t)::text from customer_relationship t where (t.customer_id = ? or t.related_customer_id = ?)",
                List.of(id, id), limit, cursor);
    }

    public JsonNode tierHistory(UUID id, Integer limit, String cursor) {
        rows.get("customer", id);
        return rows.page("select row_to_json(t)::text from customer_kyc_tier_history t where t.customer_id = ?",
                List.of(id), limit, cursor);
    }

    // ------------------------------------------------------------------ ownership

    public JsonNode ownershipTree(UUID id) {
        rows.get("customer", id);
        Map<UUID, OwnershipTree.Party> parties = new HashMap<>();
        OwnershipTree.Graph graph = new OwnershipTree.Graph() {
            @Override
            public OwnershipTree.Party party(UUID pid) {
                return parties.computeIfAbsent(pid, k -> {
                    ObjectNode c = rows.get("customer", k);
                    return new OwnershipTree.Party(k, text(c, "customer_number"), text(c, "display_name"), text(c, "customer_type"));
                });
            }

            @Override
            public List<OwnershipTree.Edge> edges(UUID subject) {
                return rows.jdbc().sql("""
                        select related_customer_id, relationship_type, ownership_percentage from customer_relationship
                        where customer_id = ? and (valid_to is null or valid_to >= current_date) order by id""")
                        .param(subject)
                        .query((rs, i) -> new OwnershipTree.Edge(rs.getObject(1, UUID.class), rs.getString(2), rs.getBigDecimal(3)))
                        .list();
            }
        };
        OwnershipTree.Result r = OwnershipTree.build(id, graph, OwnershipTree.DEFAULT_THRESHOLD);
        ObjectNode out = json.createObjectNode();
        out.put("customer_id", id.toString());
        out.put("ubo_threshold_percentage", OwnershipTree.DEFAULT_THRESHOLD.toPlainString());
        out.set("tree", node(r.root()));
        ArrayNode ubos = out.putArray("ultimate_beneficial_owners");
        for (OwnershipTree.Ubo u : r.ubos()) {
            ObjectNode n = party(u.party());
            n.put("effective_percentage", u.effectivePercentage().stripTrailingZeros().toPlainString());
            n.set("basis", json.valueToTree(u.basis()));
            ubos.add(n);
        }
        ArrayNode controllers = out.putArray("controllers");
        r.controllers().forEach(p -> controllers.add(party(p)));
        return out;
    }

    private ObjectNode node(OwnershipTree.Node n) {
        ObjectNode o = party(n.party());
        if (n.relationshipType() != null) {
            o.put("relationship_type", n.relationshipType());
            o.put("direct_percentage", plain(n.directPercentage()));
        }
        o.put("effective_percentage", plain(n.effectivePercentage()));
        if (n.cycle()) {
            o.put("cycle", true);
        }
        if (n.truncated()) {
            o.put("truncated", true);
        }
        ArrayNode owners = o.putArray("owners");
        n.owners().forEach(c -> owners.add(node(c)));
        return o;
    }

    private ObjectNode party(OwnershipTree.Party p) {
        ObjectNode o = json.createObjectNode();
        o.put("customer_id", p.id().toString());
        o.put("customer_number", p.customerNumber());
        o.put("display_name", p.displayName());
        o.put("customer_type", p.customerType());
        return o;
    }

    private static String plain(BigDecimal d) {
        return d == null ? null : d.stripTrailingZeros().toPlainString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
