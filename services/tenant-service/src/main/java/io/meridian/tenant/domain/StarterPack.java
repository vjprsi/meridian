package io.meridian.tenant.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A tenant starter pack: a declarative, versioned bundle of the defaults a new tenant needs before it can transact
 * (legal entity, ledger book and chart, ledger routing, products, tariffs, limits, commissions, notification
 * templates, EOD, approval policies). Every item is created through the owning service's public API, so the pack
 * holds request bodies with placeholders, not rows.
 *
 * <p>Items form trees per step: a child is created after its parent (it may reference it with {@code ${parent}}), and
 * a parent's follow-up actions (submit, publish) run after all of its children. Item keys of children are
 * {@code parent-key/child-key}.
 *
 * <p>Repetition over a list parameter (e.g. the currencies fx-service deals in):
 * <ul>
 *   <li>an item with {@code for_each: "${param.<list>}"} becomes one item per value, keyed {@code <key>.<value in lower
 *       case>}; inside it {@code ${each}} is the value and {@code ${each.lower}} its lower-case form (also in object
 *       keys and in references, e.g. {@code ${ref.ledger_book/fx_position.${each.lower}}});</li>
 *   <li>an object with a {@code $for_each: "${param.<list>}"} entry becomes one copy of its other entries per value,
 *       merged into one object, so map keys can be data: {@code {$for_each: "${param.fx_currencies}", "${each}": ...}}.</li>
 * </ul>
 * Repetition is expanded before parsing with the run's parameters ({@link #expand}); the catalog validates packs
 * expanded with the parameter defaults. Nested repetition is not supported.
 *
 * @param definition the merged source document (after {@code extends}), returned by the catalog API
 */
public record StarterPack(String code, String version, String title, String market, String description,
                          Map<String, Param> parameters, List<Step> steps, JsonNode definition) {

    public static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");
    public static final Pattern VERSION = Pattern.compile("\\d{4}\\.\\d{1,2}\\.\\d{1,4}");
    static final Pattern KEY = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,59}");
    static final Pattern STEP = Pattern.compile("[a-z][a-z0-9_]{1,63}");
    static final Pattern PARAM = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    static final Set<String> PARAM_TYPES = Set.of("string", "integer", "number", "boolean", "array", "object");
    static final Set<String> METHODS = Set.of("POST", "PUT", "PATCH");

    /** A declared parameter: type, default and the tenant column that supplies it when the tenant is known. */
    public record Param(String name, String type, JsonNode defaultValue, String description, String tenantFact) {}

    public record Step(String code, String title, List<Item> items) {}

    public enum Kind {
        /** POST a resource to its collection; follow-up actions and upgrades apply to it. */
        CREATE,
        /** An idempotent operation without a resource of its own (e.g. install the standard EOD pack). */
        CALL,
        /** PATCH one pre-existing resource found by lookup (e.g. a job created by another pack). */
        PATCH_EXISTING
    }

    public enum Update {
        /** A changed definition is never applied automatically: reported as UPGRADE_SKIPPED. */
        NONE,
        /** A changed definition is applied with PATCH {path}/{id} (If-Match row_version). */
        PATCH,
        /** A changed definition creates a new resource (new version, e.g. a config entry) and re-runs its actions. */
        RECREATE
    }

    /**
     * A follow-up action on the item's resource ({@code path} relative to the resource, e.g. {@code /{id}/submit}).
     *
     * @param skipStatus resource statuses meaning the action already happened (required, so re-runs never repeat it)
     * @param requiresStatus statuses the resource must be in before the action runs (else the item waits)
     * @param approval workflow-service resource type of the approval request the action opens (maker-checker)
     * @param manual the pack never runs it: a different user (checker) must, the run reports it as pending
     * @param after keys of earlier items that must be DONE first (e.g. a wallet is activated only once the ledger
     *              routing it posts with has been approved); otherwise the item waits
     */
    public record Action(String name, String path, JsonNode body, List<String> skipStatus, List<String> requiresStatus,
                         String approval, boolean manual, String description, List<String> after) {}

    /** Optional search for a pre-existing resource (GET {path}?query, first row whose fields equal {@code match}). */
    public record Lookup(JsonNode query, JsonNode match) {}

    public record Item(String key, String parentKey, String stepCode, String title, Kind kind, String service,
                       String method, String path, JsonNode body, Lookup lookup, Update update, List<String> updateExclude,
                       List<String> readyStatus, List<String> pendingStatus, String statusField, List<Action> actions,
                       boolean adoptChildren, List<Item> children, JsonNode source) {

        /** The definition that versions the item: its own source without children (checksums ignore children). */
        public JsonNode ownDefinition() {
            ObjectNode o = ((ObjectNode) source).deepCopy();
            o.remove("children");
            o.remove("title");
            o.remove("description");
            return o;
        }

        public String resourcePath(String id) {
            return path + "/" + id;
        }
    }

    /** All items in processing order (parent before children), depth first. */
    public List<Item> items() {
        List<Item> out = new ArrayList<>();
        for (Step s : steps) {
            s.items().forEach(i -> walk(i, out::add));
        }
        return out;
    }

    public static void walk(Item item, Consumer<Item> visitor) {
        visitor.accept(item);
        item.children().forEach(c -> walk(c, visitor));
    }

    public String latestKey() {
        return code + "@" + version;
    }

    // ------------------------------------------------------------------ parsing

    /**
     * Merges a pack with the pack it extends: parameters are overridden field by field; a step with the same code
     * keeps the parent's items, replaces those with the same key, removes {@code remove_items} and appends new ones.
     */
    public static ObjectNode merge(JsonNode parent, JsonNode child) {
        ObjectNode out = ((ObjectNode) parent).deepCopy();
        for (String f : List.of("code", "version", "title", "market", "description", "notice")) {
            if (child.has(f)) {
                out.set(f, child.get(f));
            }
        }
        out.remove("extends");
        ObjectNode params = out.has("parameters") ? (ObjectNode) out.get("parameters") : out.putObject("parameters");
        child.path("parameters").properties().forEach(e -> {
            if (params.has(e.getKey()) && params.get(e.getKey()).isObject() && e.getValue().isObject()) {
                ((ObjectNode) params.get(e.getKey())).setAll((ObjectNode) e.getValue());
            } else {
                params.set(e.getKey(), e.getValue());
            }
        });
        ArrayNode steps = out.has("steps") ? (ArrayNode) out.get("steps") : out.putArray("steps");
        for (JsonNode cs : child.path("steps")) {
            ObjectNode target = null;
            for (JsonNode ps : steps) {
                if (ps.path("code").asString("").equals(cs.path("code").asString())) {
                    target = (ObjectNode) ps;
                }
            }
            if (target == null) {
                ObjectNode copy = ((ObjectNode) cs).deepCopy();
                copy.remove("remove_items");
                steps.add(copy);
                continue;
            }
            if (cs.has("title")) {
                target.set("title", cs.get("title"));
            }
            ArrayNode items = target.has("items") ? (ArrayNode) target.get("items") : target.putArray("items");
            Set<String> remove = new LinkedHashSet<>();
            cs.path("remove_items").forEach(k -> remove.add(k.asString()));
            ArrayNode merged = JsonNodeFactory.instance.arrayNode();
            Map<String, JsonNode> replacements = new LinkedHashMap<>();
            cs.path("items").forEach(i -> replacements.put(i.path("key").asString(), i));
            for (JsonNode i : items) {
                String k = i.path("key").asString();
                if (remove.contains(k)) {
                    continue;
                }
                merged.add(replacements.containsKey(k) ? replacements.remove(k) : i);
            }
            replacements.values().forEach(merged::add);
            target.set("items", merged);
        }
        return out;
    }

    /**
     * The pack with its {@code for_each} repetitions expanded for the given (resolved) parameters; the pack itself when
     * it repeats nothing. Throws {@link InvalidPack} when a repetition's parameter is not a list of codes.
     */
    public StarterPack expand(Map<String, JsonNode> params) {
        return repeats(definition) ? parse(definition, params) : this;
    }

    /** Parses and validates a (merged) pack document; throws {@link InvalidPack} listing every problem. */
    public static StarterPack parse(JsonNode doc) {
        return parse(doc, null);
    }

    /**
     * Parses and validates a (merged) pack document with its repetitions expanded for {@code values} (null: the
     * parameter defaults); throws {@link InvalidPack} listing every problem.
     */
    public static StarterPack parse(JsonNode doc, Map<String, JsonNode> values) {
        List<String> problems = new ArrayList<>();
        String code = doc.path("code").asString("");
        String version = doc.path("version").asString("");
        if (!CODE.matcher(code).matches()) {
            problems.add("code must match " + CODE.pattern());
        }
        if (!VERSION.matcher(version).matches()) {
            problems.add("version must be YYYY.M.N");
        }
        Map<String, Param> params = new LinkedHashMap<>();
        doc.path("parameters").properties().forEach(e -> {
            JsonNode p = e.getValue();
            String type = p.path("type").asString("string");
            if (!PARAM.matcher(e.getKey()).matches()) {
                problems.add("parameter name " + e.getKey() + " must match " + PARAM.pattern());
            }
            if (!PARAM_TYPES.contains(type)) {
                problems.add("parameter " + e.getKey() + " has unknown type " + type);
            }
            if (!p.has("default")) {
                problems.add("parameter " + e.getKey() + " needs a default (packs must apply without input)");
            } else if (!PackTemplates.matchesType(p.get("default"), type)) {
                problems.add("parameter " + e.getKey() + " default is not a " + type);
            }
            params.put(e.getKey(), new Param(e.getKey(), type, p.get("default"), p.path("description").asString(""),
                    p.path("tenant_fact").asString(null)));
        });
        Map<String, JsonNode> effective = new LinkedHashMap<>();
        params.forEach((k, p) -> effective.put(k, p.defaultValue()));
        if (values != null) {
            effective.putAll(values);
        }
        JsonNode expanded = repeats(doc) ? expand(doc, effective, problems) : doc;
        List<Step> steps = new ArrayList<>();
        Set<String> stepCodes = new LinkedHashSet<>();
        Set<String> keys = new LinkedHashSet<>();
        for (JsonNode s : expanded.path("steps")) {
            String sc = s.path("code").asString("");
            if (!STEP.matcher(sc).matches() || !stepCodes.add(sc)) {
                problems.add("step code '" + sc + "' must be unique and match " + STEP.pattern());
            }
            List<Item> items = new ArrayList<>();
            for (JsonNode i : s.path("items")) {
                items.add(item(i, null, sc, keys, problems));
            }
            if (items.isEmpty()) {
                problems.add("step " + sc + " has no items");
            }
            steps.add(new Step(sc, s.path("title").asString(sc), List.copyOf(items)));
        }
        if (steps.isEmpty()) {
            problems.add("a pack needs at least one step");
        }
        StarterPack pack = new StarterPack(code, version, doc.path("title").asString(code), doc.path("market").asString("GENERIC"),
                doc.path("description").asString(""), Map.copyOf(params), List.copyOf(steps), doc);
        if (problems.isEmpty()) {
            problems.addAll(PackTemplates.checkPlaceholders(pack));
            Set<String> earlier = new LinkedHashSet<>();
            for (Item item : pack.items()) {
                for (Action a : item.actions()) {
                    a.after().stream().filter(k -> !earlier.contains(k))
                            .forEach(k -> problems.add(item.key() + "." + a.name() + ": after " + k + " is not an earlier item"));
                }
                earlier.add(item.key());
            }
        }
        if (!problems.isEmpty()) {
            throw new InvalidPack(code + "@" + version, problems);
        }
        return pack;
    }

    private static Item item(JsonNode i, String parentKey, String step, Set<String> keys, List<String> problems) {
        String local = i.path("key").asString("");
        if (!KEY.matcher(local).matches()) {
            problems.add("item key '" + local + "' must match " + KEY.pattern());
        }
        String key = parentKey == null ? local : parentKey + "/" + local;
        if (!keys.add(key)) {
            problems.add("duplicate item key " + key);
        }
        if (key.length() > 160) {
            problems.add("item key " + key + " is longer than 160 characters");
        }
        Kind kind;
        try {
            kind = Kind.valueOf(i.path("kind").asString("CREATE").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            problems.add(key + ": unknown kind " + i.path("kind").asString());
            kind = Kind.CREATE;
        }
        Update update;
        try {
            update = Update.valueOf(i.path("update").asString("NONE").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            problems.add(key + ": unknown update mode " + i.path("update").asString());
            update = Update.NONE;
        }
        String service = i.path("service").asString("");
        String path = i.path("path").asString("");
        if (service.isBlank()) {
            problems.add(key + ": service is required");
        }
        if (!path.startsWith("/v1/") || path.contains("?")) {
            problems.add(key + ": path must be a /v1/ collection path without query");
        }
        String method = i.path("method").asString(kind == Kind.PATCH_EXISTING ? "PATCH" : "POST").toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method)) {
            problems.add(key + ": method must be one of " + METHODS);
        }
        JsonNode body = i.has("body") ? i.get("body") : JsonNodeFactory.instance.objectNode();
        if (!body.isObject()) {
            problems.add(key + ": body must be an object");
        }
        Lookup lookup = null;
        if (i.has("lookup")) {
            JsonNode l = i.get("lookup");
            lookup = new Lookup(l.has("query") ? l.get("query") : JsonNodeFactory.instance.objectNode(),
                    l.has("match") ? l.get("match") : JsonNodeFactory.instance.objectNode());
            if (!lookup.query().isObject() || !lookup.match().isObject() || lookup.match().isEmpty()) {
                problems.add(key + ": lookup needs a match object (and an optional query object)");
            }
        }
        if (kind == Kind.PATCH_EXISTING && lookup == null) {
            problems.add(key + ": PATCH_EXISTING items need a lookup");
        }
        if (kind != Kind.CREATE && (i.has("actions") || i.has("children"))) {
            problems.add(key + ": only CREATE items have actions or children");
        }
        if (kind != Kind.CREATE && update != Update.NONE) {
            problems.add(key + ": update applies to CREATE items only (CALL and PATCH_EXISTING re-apply on change)");
        }
        List<Action> actions = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode a : i.path("actions")) {
            String name = a.path("name").asString("");
            if (!PARAM.matcher(name).matches() || !names.add(name)) {
                problems.add(key + ": action name '" + name + "' must be unique and match " + PARAM.pattern());
            }
            String ap = a.path("path").asString("");
            if (!ap.startsWith("/{id}/")) {
                problems.add(key + "." + name + ": action path must start with /{id}/");
            }
            List<String> skip = strings(a.path("skip_status"));
            if (skip.isEmpty()) {
                problems.add(key + "." + name + ": skip_status is required (re-runs must recognise a done action)");
            }
            actions.add(new Action(name, ap, a.has("body") ? a.get("body") : JsonNodeFactory.instance.objectNode(), skip,
                    strings(a.path("requires_status")), a.path("approval").asString(null), a.path("manual").asBoolean(false),
                    a.path("description").asString(""), strings(a.path("after"))));
        }
        List<String> ready = strings(i.path("ready_status"));
        if (!actions.isEmpty() && ready.isEmpty()) {
            problems.add(key + ": items with actions need ready_status");
        }
        List<Item> children = new ArrayList<>();
        for (JsonNode c : i.path("children")) {
            children.add(item(c, key, step, keys, problems));
        }
        return new Item(key, parentKey, step, i.path("title").asString(key), kind, service, method, path, body, lookup,
                update, strings(i.path("update_exclude")), ready, strings(i.path("pending_status")),
                i.path("status_field").asString("status"), List.copyOf(actions), i.path("adopt_children").asBoolean(false),
                List.copyOf(children), i);
    }

    // ------------------------------------------------------------------ repetition (for_each)

    static final String FOR_EACH = "for_each";
    static final String MAP_FOR_EACH = "$for_each";
    private static final Pattern EACH_VALUE = Pattern.compile("[A-Za-z0-9_]{1,40}");
    private static final Pattern LIST_PARAM = Pattern.compile("\\$\\{param\\.([a-z][a-z0-9_]{0,63})}");

    static boolean repeats(JsonNode doc) {
        return doc.toString().contains("for_each\"");
    }

    /** A copy of the document's steps with every item and map repetition expanded. */
    static JsonNode expand(JsonNode doc, Map<String, JsonNode> values, List<String> problems) {
        ObjectNode out = ((ObjectNode) doc).deepCopy();
        for (JsonNode s : out.path("steps")) {
            if (s.isObject() && s.has("items")) {
                ((ObjectNode) s).set("items", expandItems(s.get("items"), values, problems));
            }
        }
        return out;
    }

    private static ArrayNode expandItems(JsonNode items, Map<String, JsonNode> values, List<String> problems) {
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        for (JsonNode i : items) {
            if (!i.isObject() || !i.has(FOR_EACH)) {
                out.add(expandItem(i, values, problems));
                continue;
            }
            ObjectNode item = ((ObjectNode) i).deepCopy();
            String key = item.path("key").asString("");
            List<String> each = eachValues(item.remove(FOR_EACH), values, key, problems);
            for (String v : each) {
                ObjectNode copy = (ObjectNode) substitute(item, v);
                copy.put("key", key + "." + v.toLowerCase(Locale.ROOT));
                out.add(expandItem(copy, values, problems));
            }
        }
        return out;
    }

    private static JsonNode expandItem(JsonNode item, Map<String, JsonNode> values, List<String> problems) {
        if (!item.isObject()) {
            return item;
        }
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        item.properties().forEach(e -> out.set(e.getKey(), "children".equals(e.getKey())
                ? expandItems(e.getValue(), values, problems)
                : expandMaps(e.getValue(), values, item.path("key").asString(""), problems)));
        return out;
    }

    private static JsonNode expandMaps(JsonNode n, Map<String, JsonNode> values, String where, List<String> problems) {
        if (n.isArray()) {
            ArrayNode a = JsonNodeFactory.instance.arrayNode();
            n.forEach(x -> a.add(expandMaps(x, values, where, problems)));
            return a;
        }
        if (!n.isObject()) {
            return n;
        }
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        if (n.has(MAP_FOR_EACH)) {
            for (String v : eachValues(n.get(MAP_FOR_EACH), values, where, problems)) {
                n.properties().forEach(e -> {
                    if (!MAP_FOR_EACH.equals(e.getKey())) {
                        out.set(substitute(e.getKey(), v), expandMaps(substitute(e.getValue(), v), values, where, problems));
                    }
                });
            }
            return out;
        }
        n.properties().forEach(e -> out.set(e.getKey(), expandMaps(e.getValue(), values, where, problems)));
        return out;
    }

    /** The values a repetition runs over: {@code "${param.<name>}"} naming a list of codes. */
    private static List<String> eachValues(JsonNode spec, Map<String, JsonNode> values, String where, List<String> problems) {
        java.util.regex.Matcher m = LIST_PARAM.matcher(spec == null ? "" : spec.asString(""));
        if (!m.matches()) {
            problems.add(where + ": for_each must be \"${param.<list parameter>}\"");
            return List.of();
        }
        JsonNode list = values.get(m.group(1));
        if (list == null || !list.isArray()) {
            problems.add(where + ": for_each parameter " + m.group(1) + " must be a declared list (array) parameter");
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode v : list) {
            String s = v.isValueNode() ? v.asString("") : "";
            if (!EACH_VALUE.matcher(s).matches()) {
                problems.add(where + ": for_each value '" + v + "' of " + m.group(1) + " must be a code (letters, digits, _)");
            } else if (out.contains(s)) {
                problems.add(where + ": for_each value " + s + " of " + m.group(1) + " is repeated");
            } else {
                out.add(s);
            }
        }
        return out;
    }

    /** Replaces {@code ${each}} and {@code ${each.lower}} in string values and object keys. */
    private static JsonNode substitute(JsonNode n, String value) {
        if (n.isString()) {
            return JsonNodeFactory.instance.stringNode(substitute(n.asString(), value));
        }
        if (n.isArray()) {
            ArrayNode a = JsonNodeFactory.instance.arrayNode();
            n.forEach(x -> a.add(substitute(x, value)));
            return a;
        }
        if (n.isObject()) {
            ObjectNode o = JsonNodeFactory.instance.objectNode();
            n.properties().forEach(e -> o.set(substitute(e.getKey(), value), substitute(e.getValue(), value)));
            return o;
        }
        return n;
    }

    private static String substitute(String s, String value) {
        return s.replace("${each.lower}", value.toLowerCase(Locale.ROOT)).replace("${each}", value);
    }

    static List<String> strings(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n != null && n.isArray()) {
            n.forEach(x -> out.add(x.asString()));
        }
        return List.copyOf(out);
    }

    /** A pack definition that cannot be used. */
    public static final class InvalidPack extends RuntimeException {
        private final List<String> problems;

        public InvalidPack(String pack, List<String> problems) {
            super("Invalid starter pack " + pack + ": " + String.join("; ", problems));
            this.problems = List.copyOf(problems);
        }

        public List<String> problems() {
            return problems;
        }
    }
}
