package io.meridian.campaign.application;

import static io.meridian.campaign.application.Json.text;

import io.meridian.campaign.api.generated.model.Campaign;
import io.meridian.campaign.api.generated.model.CampaignAudienceMember;
import io.meridian.campaign.domain.AbTesting;
import io.meridian.campaign.domain.CampaignMath;
import io.meridian.campaign.domain.Eligibility;
import io.meridian.campaign.domain.Lifecycles;
import io.meridian.campaign.infrastructure.ExternalServices;
import io.meridian.campaign.infrastructure.Rows;
import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Audience selection, message dispatch, engagement tracking and performance of campaigns.
 *
 * <p>Selection: each candidate must be in a target segment and in no excluded segment (customer-service), must have
 * a valid marketing consent when the campaign requires one, and must be under the frequency cap; every consent /
 * cap decision is recorded as an immutable {@code marketing_consent_snapshot}. Allocation to control group and
 * variants is a deterministic hash of campaign and customer ({@link AbTesting}), so rebuilding never moves anyone.
 */
@Service
public class AudienceService {
    static final String T = "campaign_audience_member";
    static final int MAX_CANDIDATES = 10_000;
    static final int DISPATCH_BATCH = 1_000;
    static final String DEFAULT_TEMPLATE = "CAMPAIGN_MESSAGE";

    private final Rows rows;
    private final Events events;
    private final ExternalServices external;

    public AudienceService(Rows rows, Events events, ExternalServices external) {
        this.rows = rows;
        this.events = events;
        this.external = external;
    }

    // ------------------------------------------------------------------ selection

    @Transactional
    public ObjectNode build(UUID campaignId, List<UUID> candidates) {
        return select(rows.lock(CampaignService.T, campaignId), candidates, true);
    }

    @Transactional(readOnly = true)
    public ObjectNode preview(UUID campaignId, List<UUID> candidates) {
        return select(rows.get(CampaignService.T, campaignId), candidates, false);
    }

    private ObjectNode select(ObjectNode c, List<UUID> candidateList, boolean persist) {
        UUID campaignId = Json.uuid(c, "id");
        Lifecycles.CAMPAIGN.apply("build-audience", text(c, "status"));
        Set<UUID> candidates = new LinkedHashSet<>(candidateList);
        if (candidates.isEmpty()) {
            throw ApiException.badRequest("validation_failed", "customer_ids must contain at least one customer");
        }
        if (candidates.size() > MAX_CANDIDATES) {
            throw ApiException.unprocessable("too_many_candidates", "At most " + MAX_CANDIDATES + " candidates per call");
        }
        List<String> channels = Json.strings(c.get("channels"));
        Map<String, BigDecimal> shares = new TreeMap<>();
        Map<String, ObjectNode> variantByCode = new HashMap<>();
        List<ObjectNode> variants = rows.query("select row_to_json(t)::text from campaign_variant t where t.campaign_id = ?",
                campaignId);
        for (String ch : channels) {
            for (ObjectNode v : variants) {
                if (ch.equals(text(v, "channel")) && !variantByCode.containsKey(text(v, "code"))) {
                    variantByCode.put(text(v, "code"), v);
                    shares.put(text(v, "code"), Json.decimal(v, "traffic_share"));
                }
            }
        }
        if (variantByCode.isEmpty()) {
            throw ApiException.unprocessable("no_variants", "Add at least one variant on a campaign channel first");
        }
        List<UUID> targets = Json.uuids(c, "segment_ids");
        List<UUID> exclusions = Json.uuids(c, "exclusion_segment_ids");
        BigDecimal control = Json.decimal(c, "control_group_pct");
        boolean consentRequired = Json.bool(c, "requires_marketing_consent");
        Integer cap = c.hasNonNull("frequency_cap") ? c.get("frequency_cap").asInt() : null;
        OffsetDateTime now = rows.now();

        Map<String, Integer> excluded = new TreeMap<>();
        Map<String, Integer> perVariant = new TreeMap<>();
        int selected = 0;
        int existing = 0;
        int controlCount = 0;
        ArrayNode sample = rows.object().arrayNode();
        for (UUID customer : candidates) {
            if (persist && rows.count("select count(*) from campaign_audience_member where campaign_id = ? and customer_id = ?",
                    campaignId, customer) > 0) {
                existing++;
                continue;
            }
            String reason;
            try {
                reason = Eligibility.segments(external.activeSegments(customer, now.toLocalDate()), targets, exclusions);
            } catch (RuntimeException e) {
                reason = "segment_check_failed";
            }
            if (reason != null) {
                excluded.merge(reason, 1, Integer::sum);
                continue;
            }
            AbTesting.Allocation a = AbTesting.allocate(campaignId, customer, control, shares);
            ObjectNode variant = a.control() ? null : variantByCode.get(a.variantCode());
            String channel = variant == null ? channels.getFirst() : text(variant, "channel");
            UUID consentId = null;
            boolean permitted = true;
            String why = null;
            if (consentRequired) {
                ExternalServices.Consent consent = external.marketingConsent(customer, now);
                permitted = consent.permitted();
                consentId = consent.consentId();
                why = consent.reason();
            }
            if (permitted && cap != null && rows.count("""
                    select count(*) from campaign_audience_member where customer_id = ? and sent_at > ?""",
                    customer, now.minusDays(7)) >= cap) {
                permitted = false;
                why = "frequency_cap";
            }
            UUID snapshotId = null;
            if (persist && (consentRequired || cap != null)) {
                ObjectNode s = rows.object();
                s.put("customer_id", customer.toString());
                if (consentId != null) {
                    s.put("consent_id", consentId.toString());
                }
                s.put("campaign_id", campaignId.toString());
                s.put("channel", channel);
                s.put("is_permitted", permitted);
                if (why != null) {
                    s.put("reason", why);
                }
                s.put("checked_at", now.toString());
                snapshotId = Json.uuid(rows.insert("marketing_consent_snapshot", s), "id");
            }
            if (!permitted) {
                excluded.merge(why, 1, Integer::sum);
                continue;
            }
            selected++;
            if (a.control()) {
                controlCount++;
            } else {
                perVariant.merge(a.variantCode(), 1, Integer::sum);
            }
            if (sample.size() < 10) {
                sample.add(customer.toString());
            }
            if (persist) {
                ObjectNode m = rows.object();
                m.put("campaign_id", campaignId.toString());
                m.put("customer_id", customer.toString());
                if (variant != null) {
                    m.put("campaign_variant_id", text(variant, "id"));
                    m.put("variant_code", a.variantCode());
                }
                m.put("channel", channel);
                m.put("is_control", a.control());
                m.put("allocation_bucket", BigDecimal.valueOf(a.bucket()).setScale(10, java.math.RoundingMode.HALF_EVEN).toPlainString());
                if (snapshotId != null) {
                    m.put("marketing_consent_snapshot_id", snapshotId.toString());
                }
                rows.insert(T, m);
            }
        }
        ObjectNode out = rows.object();
        out.put("campaign_id", campaignId.toString());
        out.put("persisted", persist);
        out.put("candidates", candidates.size());
        out.put("selected", selected);
        out.put("already_members", existing);
        out.put("control", controlCount);
        out.put("treated", selected - controlCount);
        ObjectNode pv = out.putObject("variants");
        perVariant.forEach(pv::put);
        ObjectNode ex = out.putObject("excluded");
        excluded.forEach(ex::put);
        out.put("excluded_total", excluded.values().stream().mapToInt(Integer::intValue).sum());
        out.set("sample_customer_ids", sample);
        if (persist) {
            int size = rows.count("select count(*) from campaign_audience_member where campaign_id = ?", campaignId);
            ObjectNode v = rows.object();
            v.put("audience_size", size);
            v.put("audience_built_at", now.toString());
            ObjectNode updated = rows.update(CampaignService.T, campaignId, v);
            events.publish("engagement.campaign.audience_built", CampaignService.T, updated, Campaign.class);
            out.put("audience_size", size);
        }
        return out;
    }

    // ------------------------------------------------------------------ dispatch

    @Transactional
    public ObjectNode dispatch(UUID campaignId) {
        ObjectNode c = rows.lock(CampaignService.T, campaignId);
        Lifecycles.CAMPAIGN.apply("dispatch", text(c, "status"));
        return dispatchLocked(campaignId);
    }

    public boolean budgetAllowsOneMore(ObjectNode c) {
        BigDecimal budget = Json.decimal(c, "budget_amount");
        if (budget == null) {
            return true;
        }
        BigDecimal cpm = Json.decimal(c, "cost_per_message");
        BigDecimal spent = Json.decimal(c, "spent_amount");
        return spent.add(cpm).compareTo(budget) <= 0;
    }

    /**
     * Sends pending messages of a RUNNING campaign whose row the caller has locked: honours the send window and the
     * budget (the campaign pauses with reason budget_exhausted when the next message would exceed it).
     */
    public ObjectNode dispatchLocked(UUID campaignId) {
        ObjectNode c = rows.get(CampaignService.T, campaignId);
        ObjectNode out = rows.object();
        out.put("campaign_id", campaignId.toString());
        int sent = 0;
        int failed = 0;
        int offline = 0;
        int pending = rows.count("""
                select count(*) from campaign_audience_member where campaign_id = ? and not is_control and sent_at is null
                and unsubscribed_at is null""", campaignId);
        JsonNode window = c.get("send_window");
        OffsetDateTime now = rows.now();
        if (window != null && window.isObject() && !window.isEmpty() && !CampaignMath.inSendWindow(
                window.hasNonNull("start_hour") ? window.get("start_hour").asInt() : null,
                window.hasNonNull("end_hour") ? window.get("end_hour").asInt() : null,
                text(window, "time_zone"), now.toInstant())) {
            out.put("sent", 0);
            out.put("deferred", pending);
            out.put("reason", "outside_send_window");
            out.put("status", text(c, "status"));
            return out;
        }
        BigDecimal cpm = Json.decimal(c, "cost_per_message");
        BigDecimal spent = Json.decimal(c, "spent_amount");
        BigDecimal budget = Json.decimal(c, "budget_amount");
        boolean exhausted = false;
        List<ObjectNode> members = rows.query("""
                select row_to_json(t)::text from campaign_audience_member t where t.campaign_id = ? and not t.is_control
                and t.sent_at is null and t.unsubscribed_at is null order by t.id limit ?""", campaignId, DISPATCH_BATCH);
        Map<String, ObjectNode> variants = new HashMap<>();
        for (ObjectNode m : members) {
            if (budget != null && spent.add(cpm).compareTo(budget) > 0) {
                exhausted = true;
                break;
            }
            ObjectNode variant = text(m, "campaign_variant_id") == null ? null
                    : variants.computeIfAbsent(text(m, "campaign_variant_id"),
                            k -> rows.get("campaign_variant", UUID.fromString(k)));
            String channel = text(m, "channel");
            ObjectNode v = rows.object();
            if (!ExternalServices.deliverable(channel)) {
                v.put("sent_at", now.toString());
                offline++;
            } else {
                try {
                    ObjectNode vars = rows.object();
                    vars.put("campaign_code", text(c, "code"));
                    if (variant != null) {
                        putIfPresent(vars, "subject", text(variant, "subject"));
                        putIfPresent(vars, "body", text(variant, "body"));
                        putIfPresent(vars, "deeplink", text(variant, "deeplink"));
                    }
                    ObjectNode data = rows.object();
                    data.put("campaign_id", campaignId.toString());
                    data.put("audience_member_id", text(m, "id"));
                    putIfPresent(data, "variant_code", text(m, "variant_code"));
                    String template = variant != null && text(variant, "notification_template_code") != null
                            ? text(variant, "notification_template_code") : DEFAULT_TEMPLATE;
                    UUID notification = external.sendToCustomer(Json.uuid(m, "customer_id"), channel, template, vars, data,
                            "campaign:" + campaignId + ":" + text(m, "id"));
                    v.put("notification_id", notification.toString());
                    v.put("sent_at", now.toString());
                    v.putNull("send_failure_reason");
                } catch (ApiException e) {
                    rows.update(T, Json.uuid(m, "id"), failure(e));
                    failed++;
                    continue;
                }
            }
            rows.update(T, Json.uuid(m, "id"), v);
            spent = spent.add(cpm);
            sent++;
        }
        ObjectNode cv = rows.object();
        cv.put("spent_amount", spent.toPlainString());
        if (exhausted) {
            cv.put("status", "PAUSED");
            cv.put("status_reason", "budget_exhausted");
        }
        ObjectNode updated = rows.update(CampaignService.T, campaignId, cv);
        if (exhausted) {
            events.publish("engagement.campaign.paused", CampaignService.T, updated, Campaign.class);
        }
        out.put("sent", sent);
        out.put("offline", offline);
        out.put("failed", failed);
        out.put("remaining", rows.count("""
                select count(*) from campaign_audience_member where campaign_id = ? and not is_control and sent_at is null
                and unsubscribed_at is null""", campaignId));
        out.put("spent_amount", spent.toPlainString());
        out.put("budget_exhausted", exhausted);
        out.put("status", text(updated, "status"));
        return out;
    }

    private ObjectNode failure(ApiException e) {
        ObjectNode v = rows.object();
        v.put("send_failure_reason", Json.truncate(e.code() + ": " + e.getMessage(), 255));
        return v;
    }

    private static void putIfPresent(ObjectNode n, String f, String v) {
        if (v != null) {
            n.put(f, v);
        }
    }

    // ------------------------------------------------------------------ engagement

    @Transactional
    public ObjectNode opened(UUID id) {
        ObjectNode m = sentMember(id);
        if (text(m, "opened_at") != null) {
            return m;
        }
        ObjectNode v = rows.object();
        v.put("opened_at", rows.now().toString());
        return rows.update(T, id, v);
    }

    @Transactional
    public ObjectNode clicked(UUID id) {
        ObjectNode m = sentMember(id);
        if (text(m, "clicked_at") != null) {
            return m;
        }
        String now = rows.now().toString();
        ObjectNode v = rows.object();
        v.put("clicked_at", now);
        if (text(m, "opened_at") == null) {
            v.put("opened_at", now);
        }
        return rows.update(T, id, v);
    }

    /** Records a conversion once (treated or control member); later calls return the member unchanged. */
    @Transactional
    public ObjectNode converted(UUID id, BigDecimal revenue) {
        ObjectNode m = rows.lock(T, id);
        if (revenue != null && revenue.signum() < 0) {
            throw ApiException.unprocessable("invalid_revenue", "revenue must be >= 0");
        }
        return markConverted(m, revenue);
    }

    public ObjectNode markConverted(ObjectNode m, BigDecimal revenue) {
        if (text(m, "converted_at") != null) {
            return m;
        }
        ObjectNode v = rows.object();
        v.put("converted_at", rows.now().toString());
        if (revenue != null) {
            v.put("revenue", revenue.toPlainString());
        }
        ObjectNode row = rows.update(T, Json.uuid(m, "id"), v);
        events.publish("engagement.campaign_audience.converted", T, row, CampaignAudienceMember.class);
        return row;
    }

    @Transactional
    public ObjectNode unsubscribe(UUID id) {
        ObjectNode m = rows.lock(T, id);
        if (text(m, "unsubscribed_at") != null) {
            return m;
        }
        ObjectNode v = rows.object();
        v.put("unsubscribed_at", rows.now().toString());
        ObjectNode row = rows.update(T, id, v);
        events.publish("engagement.campaign_audience.unsubscribed", T, row, CampaignAudienceMember.class);
        return row;
    }

    private ObjectNode sentMember(UUID id) {
        ObjectNode m = rows.lock(T, id);
        if (Json.bool(m, "is_control") || text(m, "sent_at") == null) {
            throw ApiException.conflict("message_not_sent", "No message was sent to this audience member");
        }
        return m;
    }

    // ------------------------------------------------------------------ performance

    @Transactional(readOnly = true)
    public ObjectNode performance(UUID campaignId) {
        ObjectNode c = rows.get(CampaignService.T, campaignId);
        Map<String, long[]> groups = new LinkedHashMap<>();
        rows.jdbc().sql("""
                select is_control, coalesce(variant_code, ''), count(*), count(sent_at), count(opened_at), count(clicked_at),
                       count(converted_at), coalesce(sum(revenue), 0)::text, count(unsubscribed_at)
                from campaign_audience_member where campaign_id = ? group by is_control, variant_code
                order by is_control, variant_code""").param(campaignId).query((rs, i) -> {
                    String key = rs.getBoolean(1) ? "CONTROL" : rs.getString(2);
                    groups.put(key, new long[] {rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7),
                            new BigDecimal(rs.getString(8)).movePointRight(8).longValue(), rs.getLong(9)});
                    return 1;
                }).list();
        long[] control = groups.getOrDefault("CONTROL", new long[7]);
        long[] treated = new long[7];
        ObjectNode out = rows.object();
        out.put("campaign_id", campaignId.toString());
        out.put("status", text(c, "status"));
        out.put("spent_amount", text(c, "spent_amount"));
        ArrayNode list = out.putArray("variants");
        for (Map.Entry<String, long[]> e : groups.entrySet()) {
            if (e.getKey().equals("CONTROL")) {
                continue;
            }
            for (int i = 0; i < 7; i++) {
                treated[i] += e.getValue()[i];
            }
            ObjectNode g = group(e.getValue(), false);
            g.put("variant_code", e.getKey());
            putDecimal(g, "uplift_pct", CampaignMath.uplift(e.getValue()[4], e.getValue()[0], control[4], control[0]));
            list.add(g);
        }
        out.set("control", group(control, true));
        ObjectNode total = group(treated, false);
        putDecimal(total, "uplift_pct", CampaignMath.uplift(treated[4], treated[0], control[4], control[0]));
        out.set("treated", total);
        return out;
    }

    private ObjectNode group(long[] g, boolean control) {
        ObjectNode n = rows.object();
        n.put("members", g[0]);
        n.put("converted", g[4]);
        putDecimal(n, "conversion_rate", CampaignMath.rate(g[4], g[0]));
        n.put("revenue", BigDecimal.valueOf(g[5], 8).stripTrailingZeros().toPlainString());
        if (!control) {
            n.put("sent", g[1]);
            n.put("opened", g[2]);
            n.put("clicked", g[3]);
            n.put("unsubscribed", g[6]);
            putDecimal(n, "open_rate", CampaignMath.rate(g[2], g[1]));
            putDecimal(n, "ctr", CampaignMath.rate(g[3], g[1]));
        }
        return n;
    }

    static void putDecimal(ObjectNode n, String field, BigDecimal d) {
        if (d == null) {
            n.putNull(field);
        } else {
            n.put(field, d.stripTrailingZeros().toPlainString());
        }
    }

    /** Re-exported for the offer flow: the audience member of a customer in a campaign, if any. */
    public java.util.Optional<ObjectNode> member(UUID campaignId, UUID customerId) {
        return rows.queryOne("""
                select row_to_json(t)::text from campaign_audience_member t where t.campaign_id = ? and t.customer_id = ?
                for update""", campaignId, customerId);
    }

    static List<UUID> ids(List<String> s) {
        List<UUID> out = new ArrayList<>();
        s.forEach(x -> out.add(UUID.fromString(x)));
        return out;
    }
}
