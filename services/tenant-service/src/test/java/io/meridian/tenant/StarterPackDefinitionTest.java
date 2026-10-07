package io.meridian.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.tenant.application.StarterPackCatalog;
import io.meridian.tenant.application.StarterPackService;
import io.meridian.tenant.domain.PackTemplates;
import io.meridian.tenant.domain.StarterPack;
import io.meridian.tenant.domain.StarterPack.Item;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Every shipped starter pack against the generated contracts: each item calls an existing route of the owning
 * service (generated/routes.json), its rendered request bodies satisfy the operation's request schema
 * (generated/openapi: known fields only, required fields present, enum values, formats, lengths), lookups use declared
 * filters, statuses used by actions exist in the resource's status enum, PATCH upgrades target an updatable route,
 * notification rules listen to events that exist (generated/asyncapi) and ledger routing keys name real services.
 * Plus parsing rules: placeholders, ordering, extends merge, parameter types.
 */
class StarterPackDefinitionTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Pattern UUID_RE = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    static final Pattern DECIMAL = Pattern.compile("-?\\d+(\\.\\d+)?");
    /** Untyped json[] columns whose values come from a domain enum (spec/domains). */
    static final Map<String, String> TYPED_LISTS = Map.of("available_channels", "ProductChannel");

    static List<StarterPack> packs;
    static JsonNode routes;
    static Set<String> services = new HashSet<>();
    static Path root;
    static String asyncapi;
    static Map<String, List<String>> domainEnums = new HashMap<>();
    static final Map<String, Map<String, Object>> specs = new HashMap<>();

    @BeforeAll
    static void load() throws IOException {
        packs = StarterPackCatalog.load(JSON, "classpath*:starter-packs/*.yaml");
        try (InputStream in = StarterPackDefinitionTest.class.getClassLoader().getResourceAsStream("meridian/routes.json")) {
            assertThat(in).as("generated routes.json on the classpath").isNotNull();
            routes = JSON.readTree(in);
        }
        try (InputStream in = StarterPackDefinitionTest.class.getClassLoader().getResourceAsStream("meridian/services.json")) {
            JSON.readTree(in).forEach(s -> services.add(s.get("name").asString()));
        }
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("generated/routes.json"))) {
            p = p.getParent();
        }
        assertThat(p).as("repository root with generated/").isNotNull();
        root = p;
        asyncapi = Files.readString(root.resolve("generated/asyncapi/meridian.asyncapi.yaml"));
        try (var files = Files.list(root.resolve("spec/domains"))) {
            for (Path f : files.filter(x -> x.toString().endsWith(".yaml")).toList()) {
                // only the enums block: some free-text descriptions are not strict YAML 1.1 for SnakeYAML
                String text = Files.readString(f);
                int start = text.indexOf("\nenums:");
                if (start < 0) {
                    continue;
                }
                int end = text.indexOf("\nservices:", start);
                Map<String, Object> doc = new Yaml().load(text.substring(start, end < 0 ? text.length() : end));
                Object enums = doc.get("enums");
                if (enums instanceof Map<?, ?> m) {
                    m.forEach((k, v) -> domainEnums.put(String.valueOf(k), ((List<?>) v).stream().map(String::valueOf).toList()));
                }
            }
        }
        assertThat(domainEnums).containsKey("ProductChannel");
    }

    @Test
    void shipsAGenericAndAMarketPackWithVersionsAndDefaults() {
        Set<String> codes = new LinkedHashSet<>();
        packs.forEach(p -> codes.add(p.code()));
        assertThat(codes).contains("GENERIC", "KE");
        StarterPack generic = pack("GENERIC");
        StarterPack ke = pack("KE");
        assertThat(generic.definition().path("notice").asString()).contains("DEFAULT");
        assertThat(ke.definition().path("notice").asString()).contains("DEFAULT");
        assertThat(ke.parameters().get("currency").defaultValue().asString()).isEqualTo("KES");
        assertThat(ke.parameters().get("timezone").defaultValue().asString()).isEqualTo("Africa/Nairobi");
        // KE inherits every GENERIC step and replaces the wallet tariffs with banded ones
        assertThat(ke.steps().stream().map(StarterPack.Step::code).toList())
                .containsExactlyElementsOf(generic.steps().stream().map(StarterPack.Step::code).toList());
        Item p2p = item(ke, "fee_p2p");
        assertThat(p2p.body().get("basis").asString()).isEqualTo("TIERED");
        assertThat(item(generic, "fee_p2p").body().get("basis").asString()).isEqualTo("PERCENTAGE");
        assertThat(item(ke, "tpl_payment_received_sms_sw")).isNotNull();
        // the pack covers what a working tenant needs (tools/e2e setup S and the guide's ledger routing table)
        Set<String> paths = new HashSet<>();
        generic.items().forEach(i -> paths.add(i.service() + " " + i.path()));
        assertThat(paths).contains("tenant-service /v1/legal-entities", "ledger-service /v1/ledgers",
                "ledger-service /v1/ledger-accounts", "config-service /v1/config/entries", "product-catalog-service /v1/products",
                "product-catalog-service /v1/product-versions", "product-catalog-service /v1/fee-definitions",
                "wallet-service /v1/wallet-fee-rules", "wallet-service /v1/wallet-tier-limits", "limits-service /v1/risk/limits/rules",
                "commission-service /v1/commission-plans", "commission-service /v1/commission-rules",
                "notification-service /v1/notification-providers", "notification-service /v1/notification-templates",
                "notification-service /v1/notification-rules", "business-day-service /v1/operations/eod-jobs/install-standard-pack",
                "business-day-service /v1/operations/eod-trigger-policies", "workflow-service /v1/approval-policies");
        Set<String> routingKeys = new HashSet<>();
        generic.items().stream().filter(i -> i.path().equals("/v1/config/entries"))
                .forEach(i -> routingKeys.add(i.body().get("config_key").asString()));
        assertThat(routingKeys).contains("default", "deposit-service", "payment-service", "wallet-service", "loan-service",
                "card-clearing-service", "card-dispute-service", "agent-network-service", "commission-service");
        Set<String> templates = new HashSet<>();
        generic.items().stream().filter(i -> i.path().equals("/v1/notification-templates"))
                .forEach(i -> templates.add(i.body().get("code").asString() + "/" + i.body().get("channel").asString()));
        assertThat(templates).contains("PAYMENT_SETTLED/EMAIL", "PAYMENT_SETTLED/SMS", "PAYEE_ALIAS_OTP/SMS", "CARD_3DS_OTP/SMS");
    }

    @Test
    void everyItemCallsExistingRoutesWithValidBodies() {
        List<String> problems = new ArrayList<>();
        packs.forEach(p -> problems.addAll(contractProblems(p)));
        assertThat(problems).as("starter pack contract problems").isEmpty();
    }

    @Test
    void contractChecksCatchMistakes() {
        StarterPack bad = StarterPack.parse(JSON.readTree("""
                {"code":"PROBE","version":"2026.1.0","parameters":{"ccy":{"type":"string","default":"USD"}},
                 "steps":[{"code":"s1","items":[
                   {"key":"rule","service":"wallet-service","path":"/v1/wallet-fee-rules","update":"PATCH",
                    "lookup":{"query":{"nope":"x"},"match":{"code":"A","ghost":1}},
                    "body":{"code":"A","txn_type":"TELEPORT","currency":"${param.ccy}","basis":"FLAT","flat_fee":"-1",
                            "valid_from":"tomorrow","colour":"red"}},
                   {"key":"plan","service":"commission-service","path":"/v1/commission-plans",
                    "body":{"code":"P","name":"P","applies_to":"AGENT","currency":"${param.ccy}"},
                    "actions":[{"name":"submit","path":"/{id}/submit","skip_status":["WAITING"]}],"ready_status":["ACTIVE"]},
                   {"key":"wrong","service":"ledger-service","path":"/v1/wallets","body":{}},
                   {"key":"nothing","service":"wallet-service","path":"/v1/teleports","body":{}},
                   {"key":"alert","service":"notification-service","path":"/v1/notification-rules",
                    "body":{"event_type":"payments.payment.teleported","template_code":"X","channels":["SMS"],
                            "recipient_expression":"customer_id","category":"TRANSACTIONAL"}}]}]}"""));
        List<String> problems = contractProblems(bad);
        assertThat(problems)
                .anySatisfy(m -> assertThat(m).contains("'TELEPORT' not in"))
                .anySatisfy(m -> assertThat(m).contains("negative amount -1"))
                .anySatisfy(m -> assertThat(m).contains("'tomorrow' is not a date"))
                .anySatisfy(m -> assertThat(m).contains("unknown field colour"))
                .anySatisfy(m -> assertThat(m).contains("plan create: required field valid_from missing"))
                .anySatisfy(m -> assertThat(m).contains("lookup filter nope"))
                .anySatisfy(m -> assertThat(m).contains("lookup matches unknown field ghost"))
                .anySatisfy(m -> assertThat(m).contains("status WAITING not in"))
                .anySatisfy(m -> assertThat(m).contains("belongs to wallet-service"))
                .anySatisfy(m -> assertThat(m).contains("0 routes for POST /v1/teleports"))
                .anySatisfy(m -> assertThat(m).contains("is not published by any service"))
                .anySatisfy(m -> assertThat(m).contains("recipient_expression must be"));
    }

    static List<String> contractProblems(StarterPack pack) {
        List<String> problems = new ArrayList<>();
        {
            Map<String, JsonNode> params = StarterPackService.resolveParameters(pack, null, null);
            for (Item item : pack.items()) {
                String where = pack.latestKey() + " " + item.key();
                if (!services.contains(item.service())) {
                    problems.add(where + ": unknown service " + item.service());
                    continue;
                }
                PackTemplates.Context ctx = new PackTemplates.Context(params, k -> UUID.randomUUID().toString(),
                        UUID.randomUUID().toString(), "2026-10-06", UUID.randomUUID().toString(), pack.code(), pack.version());
                JsonNode body = PackTemplates.render(item.body(), ctx);
                switch (item.kind()) {
                    case CREATE -> {
                        Map<String, Object> op = op(item.service(), "post", item.path(), where, problems);
                        if (op != null) {
                            validateBody(item.service(), op, body, where + " create", problems);
                        }
                        if (item.update() == StarterPack.Update.PATCH) {
                            Map<String, Object> patch = op(item.service(), "patch", item.path() + "/{id}", where, problems);
                            if (patch != null) {
                                ObjectNode upd = ((ObjectNode) body).deepCopy();
                                item.updateExclude().forEach(upd::remove);
                                validateBody(item.service(), patch, upd, where + " upgrade PATCH", problems);
                            }
                        }
                        if (!item.actions().isEmpty() || !item.readyStatus().isEmpty()) {
                            Map<String, Object> get = op(item.service(), "get", item.path() + "/{id}", where, problems);
                            if (get != null) {
                                checkStatuses(item, get, where, problems);
                            }
                        }
                        for (StarterPack.Action a : item.actions()) {
                            Map<String, Object> aop = op(item.service(), "post", item.path() + a.path(), where + "." + a.name(), problems);
                            if (aop != null && !a.manual()) {
                                validateBody(item.service(), aop, PackTemplates.render(a.body(), ctx), where + "." + a.name(), problems);
                            }
                        }
                    }
                    case CALL -> {
                        Map<String, Object> op = op(item.service(), item.method().toLowerCase(), item.path(), where, problems);
                        if (op != null) {
                            validateBody(item.service(), op, body, where + " call", problems);
                        }
                    }
                    case PATCH_EXISTING -> {
                        Map<String, Object> patch = op(item.service(), "patch", item.path() + "/{id}", where, problems);
                        if (patch != null) {
                            validateBody(item.service(), patch, body, where + " PATCH", problems);
                        }
                    }
                }
                if (item.lookup() != null) {
                    Map<String, Object> list = op(item.service(), "get", item.path(), where + " lookup", problems);
                    if (list != null) {
                        checkLookup(item, list, PackTemplates.render(item.lookup().query(), ctx), where, problems);
                    }
                }
                domainChecks(item, body, where, problems);
            }
        }
        return problems;
    }

    @Test
    void parametersHaveTypedDefaultsAndRequestsAreValidated() {
        StarterPack ke = pack("KE");
        Map<String, JsonNode> p = StarterPackService.resolveParameters(ke,
                JSON.readTree("{\"default_currency\":\"KES\",\"legal_name\":\"Acme Kenya\",\"timezone\":\"Africa/Nairobi\"}"),
                JSON.readTree("{\"savings_rate\":\"0.05\",\"alert_channels\":[\"EMAIL\"]}"));
        assertThat(p.get("legal_name").asString()).isEqualTo("Acme Kenya");
        assertThat(p.get("savings_rate").asString()).isEqualTo("0.05");
        assertThat(p.get("alert_channels").size()).isEqualTo(1);
        assertThatThrownBy(() -> StarterPackService.resolveParameters(ke, null, JSON.readTree("{\"nope\":1}")))
                .hasMessageContaining("no parameter nope");
        assertThatThrownBy(() -> StarterPackService.resolveParameters(ke, null, JSON.readTree("{\"eod_auto_trigger\":\"yes\"}")))
                .hasMessageContaining("must be a boolean");
    }

    @Test
    void checksumsIgnoreReferencesAndDatesButFollowParameters() {
        StarterPack generic = pack("GENERIC");
        Item fee = item(generic, "fee_p2p");
        Map<String, JsonNode> params = StarterPackService.resolveParameters(generic, null, null);
        String c1 = PackTemplates.checksum(fee, params);
        assertThat(PackTemplates.checksum(fee, params)).isEqualTo(c1);
        Map<String, JsonNode> eur = new HashMap<>(params);
        eur.put("currency", JSON.getNodeFactory().stringNode("EUR"));
        assertThat(PackTemplates.checksum(fee, eur)).isNotEqualTo(c1);
        // children are versioned on their own: the parent's checksum does not change with them
        Item book = item(generic, "ledger_book");
        assertThat(book.ownDefinition().has("children")).isFalse();
    }

    @Test
    void renderingKeepsTypesAndReportsUnresolvedReferences() {
        JsonNode t = JSON.readTree("""
                {"n":"${param.count}","list":"${param.channels}","s":"x-${param.count}-${pack.code}","id":"${ref.a}","p":"${parent}"}""");
        Map<String, JsonNode> params = Map.of("count", JSON.getNodeFactory().numberNode(5),
                "channels", JSON.readTree("[\"SMS\"]"));
        JsonNode out = PackTemplates.render(t, new PackTemplates.Context(params, k -> "a".equals(k) ? "id-a" : null, "pid",
                "2026-10-06", "t", "P", "1"));
        assertThat(out.get("n").isIntegralNumber()).isTrue();
        assertThat(out.get("list").isArray()).isTrue();
        assertThat(out.get("s").asString()).isEqualTo("x-5-P");
        assertThat(out.get("id").asString()).isEqualTo("id-a");
        assertThat(out.get("p").asString()).isEqualTo("pid");
        assertThatThrownBy(() -> PackTemplates.render(JSON.readTree("{\"x\":\"${ref.missing}\"}"),
                new PackTemplates.Context(params, k -> null, null, "d", "t", "P", "1")))
                .isInstanceOf(PackTemplates.Unresolved.class);
    }

    @Test
    void invalidPacksAreRejectedWithEveryProblem() {
        JsonNode bad = JSON.readTree("""
                {"code":"bad","version":"1","parameters":{"x":{"type":"string"}},
                 "steps":[{"code":"s1","items":[
                   {"key":"a","service":"wallet-service","path":"/v1/wallets","body":{"c":"${param.y}","d":"${ref.b}"},
                    "actions":[{"name":"go","path":"/{id}/go"}]},
                   {"key":"b","service":"wallet-service","path":"/v1/wallets","body":{"p":"${parent}"}},
                   {"key":"b","service":"","path":"wallets","kind":"CALL","update":"PATCH"}]}]}""");
        assertThatThrownBy(() -> StarterPack.parse(bad))
                .isInstanceOf(StarterPack.InvalidPack.class)
                .satisfies(e -> assertThat(((StarterPack.InvalidPack) e).problems()).anySatisfy(m -> assertThat(m).contains("code must match"))
                        .anySatisfy(m -> assertThat(m).contains("version must be"))
                        .anySatisfy(m -> assertThat(m).contains("needs a default"))
                        .anySatisfy(m -> assertThat(m).contains("skip_status is required"))
                        .anySatisfy(m -> assertThat(m).contains("need ready_status"))
                        .anySatisfy(m -> assertThat(m).contains("duplicate item key b"))
                        .anySatisfy(m -> assertThat(m).contains("service is required"))
                        .anySatisfy(m -> assertThat(m).contains("update applies to CREATE")));
        JsonNode refs = JSON.readTree("""
                {"code":"BAD","version":"2026.1.0","parameters":{},
                 "steps":[{"code":"s1","items":[
                   {"key":"a","service":"wallet-service","path":"/v1/wallets","body":{"c":"${param.y}","d":"${ref.b}","e":"${parent}","f":"${what}"}},
                   {"key":"b","service":"wallet-service","path":"/v1/wallets","body":{}}]}]}""");
        assertThatThrownBy(() -> StarterPack.parse(refs))
                .satisfies(e -> assertThat(((StarterPack.InvalidPack) e).problems())
                        .anySatisfy(m -> assertThat(m).contains("undeclared parameter ${param.y}"))
                        .anySatisfy(m -> assertThat(m).contains("${ref.b} must reference an item processed earlier"))
                        .anySatisfy(m -> assertThat(m).contains("${parent} used outside a child item"))
                        .anySatisfy(m -> assertThat(m).contains("unknown placeholder ${what}")));
    }

    @Test
    void extendsMergesParametersAndItemsByKey() {
        JsonNode base = JSON.readTree("""
                {"code":"BASE","version":"2026.1.0","parameters":{"a":{"type":"string","default":"x","description":"d"}},
                 "steps":[{"code":"s1","items":[{"key":"i1","v":1},{"key":"i2","v":2},{"key":"i3","v":3}]}]}""");
        JsonNode child = JSON.readTree("""
                {"code":"CHILD","version":"2026.2.0","extends":"BASE","parameters":{"a":{"default":"y"}},
                 "steps":[{"code":"s1","remove_items":["i3"],"items":[{"key":"i2","v":20},{"key":"i4","v":4}]},
                          {"code":"s2","items":[{"key":"j","v":5}]}]}""");
        JsonNode m = StarterPack.merge(base, child);
        assertThat(m.get("code").asString()).isEqualTo("CHILD");
        assertThat(m.has("extends")).isFalse();
        assertThat(m.at("/parameters/a/default").asString()).isEqualTo("y");
        assertThat(m.at("/parameters/a/type").asString()).isEqualTo("string");
        assertThat(m.at("/steps/0/items").toString()).isEqualTo("[{\"key\":\"i1\",\"v\":1},{\"key\":\"i2\",\"v\":20},{\"key\":\"i4\",\"v\":4}]");
        assertThat(m.at("/steps/1/code").asString()).isEqualTo("s2");
    }

    // ------------------------------------------------------------------ contract checks

    static void validateBody(String service, Map<String, Object> op, JsonNode body, String where, List<String> problems) {
        Map<String, Object> spec = spec(service);
        Map<String, Object> rb = map(op.get("requestBody"));
        if (rb == null) {
            if (body != null && !body.isEmpty()) {
                problems.add(where + ": operation takes no body but the pack sends " + body);
            }
            return;
        }
        Map<String, Object> schema = deref(spec, map(map(map(rb.get("content")).get("application/json")).get("schema")));
        validate(spec, schema, body, where, problems, true);
    }

    @SuppressWarnings("unchecked")
    static void validate(Map<String, Object> spec, Map<String, Object> schema, JsonNode v, String where, List<String> problems,
                         boolean request) {
        schema = deref(spec, schema);
        if (schema == null || v == null) {
            return;
        }
        if (v.isNull()) {
            return;
        }
        String type = (String) schema.get("type");
        if (type == null) {
            return; // untyped (json*): anything goes
        }
        switch (type) {
            case "object" -> {
                if (!v.isObject()) {
                    problems.add(where + ": expected an object, got " + v);
                    return;
                }
                Map<String, Object> props = map(schema.get("properties"));
                if (props == null) {
                    return; // free-form object (json)
                }
                for (Object r : (List<Object>) schema.getOrDefault("required", List.of())) {
                    if (!v.has((String) r)) {
                        problems.add(where + ": required field " + r + " missing");
                    }
                }
                for (Map.Entry<String, JsonNode> e : v.properties()) {
                    Map<String, Object> p = deref(spec, map(props.get(e.getKey())));
                    if (p == null) {
                        problems.add(where + ": unknown field " + e.getKey());
                    } else if (request && Boolean.TRUE.equals(p.get("readOnly"))) {
                        problems.add(where + ": read-only field " + e.getKey());
                    } else {
                        validate(spec, p, e.getValue(), where + "." + e.getKey(), problems, request);
                        String domainEnum = TYPED_LISTS.get(e.getKey());
                        if (domainEnum != null && e.getValue().isArray()) {
                            e.getValue().forEach(x -> {
                                if (!domainEnums.get(domainEnum).contains(x.asString())) {
                                    problems.add(where + "." + e.getKey() + ": " + x + " is not a " + domainEnum);
                                }
                            });
                        }
                    }
                }
            }
            case "array" -> {
                if (!v.isArray()) {
                    problems.add(where + ": expected an array, got " + v);
                    return;
                }
                Map<String, Object> items = map(schema.get("items"));
                v.forEach(x -> validate(spec, items, x, where + "[]", problems, request));
            }
            case "integer" -> {
                if (!v.isIntegralNumber()) {
                    problems.add(where + ": expected an integer, got " + v);
                }
            }
            case "number" -> {
                if (!v.isNumber()) {
                    problems.add(where + ": expected a number, got " + v);
                }
            }
            case "boolean" -> {
                if (!v.isBoolean()) {
                    problems.add(where + ": expected a boolean, got " + v);
                }
            }
            case "string" -> {
                if (!v.isString()) {
                    problems.add(where + ": expected a string, got " + v);
                    return;
                }
                String s = v.asString();
                List<Object> en = (List<Object>) schema.get("enum");
                if (en != null && !en.contains(s)) {
                    problems.add(where + ": '" + s + "' not in " + en);
                }
                String format = (String) schema.get("format");
                if ("uuid".equals(format) && !UUID_RE.matcher(s).matches()) {
                    problems.add(where + ": '" + s + "' is not a uuid");
                }
                if ("date".equals(format) && !DATE.matcher(s).matches()) {
                    problems.add(where + ": '" + s + "' is not a date");
                }
                if ("decimal".equals(format)) {
                    if (!DECIMAL.matcher(s).matches()) {
                        problems.add(where + ": '" + s + "' is not a decimal");
                    } else if (new BigDecimal(s).signum() < 0) {
                        problems.add(where + ": negative amount " + s);
                    }
                }
                Object max = schema.get("maxLength");
                if (max instanceof Integer m && s.length() > m) {
                    problems.add(where + ": longer than " + m);
                }
                Object pattern = schema.get("pattern");
                if (pattern instanceof String pt && !Pattern.compile(pt).matcher(s).find()) {
                    problems.add(where + ": '" + s + "' does not match " + pt);
                }
            }
            default -> {
            }
        }
    }

    @SuppressWarnings("unchecked")
    static void checkStatuses(Item item, Map<String, Object> get, String where, List<String> problems) {
        Map<String, Object> spec = spec(item.service());
        Map<String, Object> schema = responseSchema(spec, get);
        Map<String, Object> prop = schema == null ? null : deref(spec, map(map(schema.get("properties")).get(item.statusField())));
        if (prop == null || prop.get("enum") == null) {
            problems.add(where + ": resource has no enum field " + item.statusField());
            return;
        }
        List<Object> en = (List<Object>) prop.get("enum");
        Set<String> used = new LinkedHashSet<>(item.readyStatus());
        used.addAll(item.pendingStatus());
        item.actions().forEach(a -> {
            used.addAll(a.skipStatus());
            used.addAll(a.requiresStatus());
        });
        used.stream().filter(s -> !en.contains(s)).forEach(s -> problems.add(where + ": status " + s + " not in " + en));
    }

    @SuppressWarnings("unchecked")
    static void checkLookup(Item item, Map<String, Object> list, JsonNode query, String where, List<String> problems) {
        Map<String, Object> spec = spec(item.service());
        Set<String> declared = new HashSet<>();
        for (Object p : (List<Object>) list.getOrDefault("parameters", List.of())) {
            Map<String, Object> m = map(p);
            if (m.containsKey("$ref")) {
                String name = ((String) m.get("$ref")).substring("#/components/parameters/".length());
                m = map(map(map(spec.get("components")).get("parameters")).get(name));
            }
            if ("query".equals(m.get("in"))) {
                declared.add((String) m.get("name"));
            }
        }
        query.propertyNames().forEach(q -> {
            if (!declared.contains(q)) {
                problems.add(where + ": lookup filter " + q + " is not a query parameter of GET " + item.path() + " " + declared);
            }
        });
        Map<String, Object> page = responseSchema(spec, list);
        Map<String, Object> row = page == null ? null : deref(spec, map(map(map(page.get("properties")).get("data")).get("items")));
        if (row == null) {
            problems.add(where + ": list response has no data items");
            return;
        }
        Map<String, Object> props = map(row.get("properties"));
        item.lookup().match().propertyNames().forEach(f -> {
            if (!props.containsKey(f)) {
                problems.add(where + ": lookup matches unknown field " + f);
            }
        });
    }

    /** Checks the services' own rules the contract cannot express. */
    static void domainChecks(Item item, JsonNode body, String where, List<String> problems) {
        if (item.path().equals("/v1/notification-rules")) {
            String event = body.path("event_type").asString();
            if (!asyncapi.contains(event + ":") && !asyncapi.contains("'" + event + "'") && !asyncapi.contains(" " + event)) {
                problems.add(where + ": event " + event + " is not published by any service (generated/asyncapi)");
            }
            if (!body.path("recipient_expression").asString().matches("(CUSTOMER|STAFF|AGENT|MERCHANT|EXTERNAL):[A-Za-z_][A-Za-z0-9_.]*")) {
                problems.add(where + ": recipient_expression must be TYPE:payload.path");
            }
        }
        if (item.path().equals("/v1/config/entries")) {
            String key = body.path("config_key").asString();
            if (!"default".equals(key) && !services.contains(key)) {
                problems.add(where + ": ledger routing key " + key + " is not a service");
            }
            body.path("value").properties().forEach(e -> {
                if (!e.getValue().isObject() && !e.getValue().isString()) {
                    problems.add(where + ": routing value " + e.getKey() + " must be an id or an object");
                }
            });
        }
        if (body.has("tiers")) {
            BigDecimal prevTo = null;
            boolean open = false;
            for (JsonNode t : body.get("tiers")) {
                if (open) {
                    problems.add(where + ": only the last tier may be open-ended");
                }
                BigDecimal from = new BigDecimal(t.path("from").asString("-1"));
                if (from.signum() < 0) {
                    problems.add(where + ": tier without from");
                }
                if (prevTo != null && from.compareTo(prevTo) <= 0) {
                    problems.add(where + ": tiers must be ascending and not overlap at " + from);
                }
                if (!t.has("flat") && !t.has("percentage")) {
                    problems.add(where + ": tier needs flat or percentage");
                }
                open = !t.has("to");
                prevTo = open ? from : new BigDecimal(t.get("to").asString());
            }
        }
        if (item.path().equals("/v1/approval-policies")) {
            int expected = 1;
            for (JsonNode l : body.path("approval_levels")) {
                if (l.path("level").asInt() != expected++) {
                    problems.add(where + ": approval levels must be numbered 1..n");
                }
            }
            if (body.path("allow_self_approval").asBoolean(false)) {
                problems.add(where + ": starter packs never allow self-approval");
            }
        }
    }

    // ------------------------------------------------------------------ openapi helpers

    static Map<String, Object> op(String service, String method, String path, String where, List<String> problems) {
        List<JsonNode> matches = new ArrayList<>();
        routes.forEach(r -> {
            if (r.get("method").asString().equalsIgnoreCase(method) && pathMatches(r.get("path").asString(), path)) {
                matches.add(r);
            }
        });
        if (matches.size() != 1) {
            problems.add(where + ": " + matches.size() + " routes for " + method.toUpperCase() + " " + path);
            return null;
        }
        if (!matches.getFirst().get("service").asString().equals(service)) {
            problems.add(where + ": " + method.toUpperCase() + " " + path + " belongs to " + matches.getFirst().get("service").asString());
            return null;
        }
        if (matches.getFirst().path("public").asBoolean(false)) {
            problems.add(where + ": " + path + " is a public callback");
        }
        Map<String, Object> paths = map(spec(service).get("paths"));
        for (Map.Entry<String, Object> e : paths.entrySet()) {
            if (pathMatches(e.getKey(), path) && map(e.getValue()).containsKey(method)) {
                return map(map(e.getValue()).get(method));
            }
        }
        problems.add(where + ": no openapi operation " + method + " " + path);
        return null;
    }

    static boolean pathMatches(String route, String pack) {
        String[] a = route.split("/");
        String[] b = pack.split("/");
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            boolean routeVar = a[i].startsWith("{");
            boolean packVar = b[i].startsWith("{");
            if (routeVar != packVar || (!routeVar && !a[i].equals(b[i]))) {
                return false;
            }
        }
        return true;
    }

    static Map<String, Object> responseSchema(Map<String, Object> spec, Map<String, Object> op) {
        Map<String, Object> ok = map(map(op.get("responses")).get("200"));
        if (ok == null || ok.get("content") == null) {
            return null;
        }
        return deref(spec, map(map(map(ok.get("content")).get("application/json")).get("schema")));
    }

    static Map<String, Object> deref(Map<String, Object> spec, Map<String, Object> s) {
        while (s != null && s.containsKey("$ref")) {
            String name = ((String) s.get("$ref")).substring("#/components/schemas/".length());
            s = map(map(map(spec.get("components")).get("schemas")).get(name));
        }
        return s;
    }

    static Map<String, Object> spec(String service) {
        return specs.computeIfAbsent(service, s -> yaml(root.resolve("generated/openapi/services/" + s + ".openapi.yaml")));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    static Map<String, Object> yaml(Path file) {
        LoaderOptions opts = new LoaderOptions();
        opts.setCodePointLimit(64 * 1024 * 1024);
        opts.setMaxAliasesForCollections(100_000);
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return new Yaml(opts).load(r);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + file, e);
        }
    }

    static StarterPack pack(String code) {
        return packs.stream().filter(p -> p.code().equals(code)).reduce((a, b) -> b).orElseThrow();
    }

    static Item item(StarterPack pack, String key) {
        return pack.items().stream().filter(i -> i.key().equals(key)).findFirst().orElse(null);
    }
}
