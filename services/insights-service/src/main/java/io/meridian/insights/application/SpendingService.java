package io.meridian.insights.application;

import io.meridian.common.error.ApiException;
import io.meridian.insights.infrastructure.Rows;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Spending analytics over the customer's current enrichments (dates are UTC transaction dates, ranges inclusive). */
@Service
public class SpendingService {
    private final Rows rows;

    public SpendingService(Rows rows) {
        this.rows = rows;
    }

    @Transactional(readOnly = true)
    public JsonNode byCategory(UUID customer, LocalDate from, LocalDate to, String currency) {
        range(from, to);
        List<Object> args = new ArrayList<>(List.of(customer, start(from), start(to.plusDays(1))));
        String sql = """
                select t.currency, t.spend_category_id, c.code, c.name, c.is_income,
                       coalesce(sum(case when t.amount < 0 then -t.amount end), 0) spend,
                       coalesce(sum(case when t.amount > 0 then t.amount end), 0) income, count(*) n
                from transaction_enrichment t left join spend_category c on c.id = t.spend_category_id
                where t.customer_id = ? and t.transaction_at >= ? and t.transaction_at < ?
                  and not exists (select 1 from transaction_enrichment s where s.supersedes_id = t.id)""";
        if (currency != null) {
            sql += " and t.currency = ?";
            args.add(currency);
        }
        sql += " group by t.currency, t.spend_category_id, c.code, c.name, c.is_income order by spend desc, income desc";
        ObjectNode out = header(customer, from, to, currency);
        ArrayNode cats = out.putArray("categories");
        Map<String, BigDecimal[]> totals = new LinkedHashMap<>();
        List<ObjectNode> items = new ArrayList<>();
        rows.jdbc().sql(sql).params(args).query((rs, i) -> {
            ObjectNode o = rows.object();
            o.put("currency", rs.getString(1));
            o.put("spend_category_id", rs.getString(2));
            o.put("category_code", rs.getString(3));
            o.put("category_name", rs.getString(4) == null ? "Uncategorised" : rs.getString(4));
            o.put("is_income", rs.getBoolean(5));
            o.put("spend", money(rs.getBigDecimal(6)));
            o.put("income", money(rs.getBigDecimal(7)));
            o.put("transaction_count", rs.getInt(8));
            BigDecimal[] t = totals.computeIfAbsent(rs.getString(1), k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            t[0] = t[0].add(rs.getBigDecimal(6));
            t[1] = t[1].add(rs.getBigDecimal(7));
            items.add(o);
            return 1;
        }).list();
        for (ObjectNode o : items) {
            BigDecimal total = totals.get(o.get("currency").asString())[0];
            BigDecimal spend = new BigDecimal(o.get("spend").asString());
            o.put("share_of_spend", total.signum() == 0 ? "0" : spend.divide(total, 4, RoundingMode.HALF_EVEN).toPlainString());
            cats.add(o);
        }
        totals(out, totals);
        return out;
    }

    @Transactional(readOnly = true)
    public JsonNode byMerchant(UUID customer, LocalDate from, LocalDate to, String currency, Integer limit) {
        range(from, to);
        int n = limit == null ? 10 : limit;
        if (n < 1 || n > 100) {
            throw ApiException.badRequest("invalid_limit", "limit must be between 1 and 100");
        }
        List<Object> args = new ArrayList<>(List.of(customer, start(from), start(to.plusDays(1))));
        String sql = """
                select t.currency, coalesce(t.merchant_clean_name, 'Unknown') m, sum(-t.amount) spend, count(*) n,
                       max(t.merchant_logo_url) logo, max(t.transaction_at) last_at
                from transaction_enrichment t
                where t.customer_id = ? and t.amount < 0 and t.transaction_at >= ? and t.transaction_at < ?
                  and not exists (select 1 from transaction_enrichment s where s.supersedes_id = t.id)""";
        if (currency != null) {
            sql += " and t.currency = ?";
            args.add(currency);
        }
        sql += " group by t.currency, m order by spend desc, m limit ?";
        args.add(n);
        ObjectNode out = header(customer, from, to, currency);
        ArrayNode list = out.putArray("merchants");
        rows.jdbc().sql(sql).params(args).query((rs, i) -> {
            ObjectNode o = list.addObject();
            o.put("currency", rs.getString(1));
            o.put("merchant", rs.getString(2));
            o.put("spend", money(rs.getBigDecimal(3)));
            o.put("transaction_count", rs.getInt(4));
            o.put("merchant_logo_url", rs.getString(5));
            o.put("last_transaction_at", rs.getObject(6, java.time.OffsetDateTime.class).withOffsetSameInstant(ZoneOffset.UTC).toString());
            return 1;
        }).list();
        return out;
    }

    @Transactional(readOnly = true)
    public JsonNode trend(UUID customer, Integer months, String currency) {
        int m = months == null ? 6 : months;
        if (m < 1 || m > 24) {
            throw ApiException.badRequest("invalid_months", "months must be between 1 and 24");
        }
        LocalDate first = rows.today().withDayOfMonth(1).minusMonths(m - 1L);
        List<Object> args = new ArrayList<>(List.of(customer, start(first)));
        String sql = """
                select t.currency, to_char(t.transaction_at at time zone 'UTC', 'YYYY-MM') mo,
                       coalesce(sum(case when t.amount > 0 then t.amount end), 0) income,
                       coalesce(sum(case when t.amount < 0 then -t.amount end), 0) spend
                from transaction_enrichment t
                where t.customer_id = ? and t.transaction_at >= ?
                  and not exists (select 1 from transaction_enrichment s where s.supersedes_id = t.id)""";
        if (currency != null) {
            sql += " and t.currency = ?";
            args.add(currency);
        }
        sql += " group by t.currency, mo";
        Map<String, Map<String, BigDecimal[]>> byCurrency = new LinkedHashMap<>();
        rows.jdbc().sql(sql).params(args).query((rs, i) -> {
            byCurrency.computeIfAbsent(rs.getString(1), k -> new LinkedHashMap<>())
                    .put(rs.getString(2), new BigDecimal[] {rs.getBigDecimal(3), rs.getBigDecimal(4)});
            return 1;
        }).list();
        ObjectNode out = rows.object();
        out.put("customer_id", customer.toString());
        out.put("months", m);
        out.put("from", first.toString());
        if (currency != null) {
            out.put("currency", currency);
        }
        ArrayNode series = out.putArray("series");
        byCurrency.forEach((cur, monthly) -> {
            ObjectNode s = series.addObject();
            s.put("currency", cur);
            ArrayNode points = s.putArray("months");
            for (int i = 0; i < m; i++) {
                String key = first.plusMonths(i).toString().substring(0, 7);
                BigDecimal[] v = monthly.getOrDefault(key, new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
                ObjectNode p = points.addObject();
                p.put("month", key);
                p.put("income", money(v[0]));
                p.put("spend", money(v[1]));
                p.put("net", money(v[0].subtract(v[1])));
            }
        });
        return out;
    }

    private ObjectNode header(UUID customer, LocalDate from, LocalDate to, String currency) {
        ObjectNode out = rows.object();
        out.put("customer_id", customer.toString());
        out.put("from", from.toString());
        out.put("to", to.toString());
        if (currency != null) {
            out.put("currency", currency);
        }
        return out;
    }

    private void totals(ObjectNode out, Map<String, BigDecimal[]> totals) {
        ArrayNode t = out.putArray("totals");
        totals.forEach((cur, v) -> {
            ObjectNode o = t.addObject();
            o.put("currency", cur);
            o.put("spend", money(v[0]));
            o.put("income", money(v[1]));
            o.put("net", money(v[1].subtract(v[0])));
        });
    }

    private static void range(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw ApiException.badRequest("invalid_range", "from must not be after to");
        }
        if (ChronoUnit.DAYS.between(from, to) > 1100) {
            throw ApiException.badRequest("invalid_range", "The range may not exceed 3 years");
        }
    }

    private static java.time.OffsetDateTime start(LocalDate d) {
        return d.atStartOfDay().atOffset(ZoneOffset.UTC);
    }

    private static String money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }
}
