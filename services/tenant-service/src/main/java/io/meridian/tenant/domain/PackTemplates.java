package io.meridian.tenant.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Placeholders in starter pack bodies. A string that is exactly one placeholder takes the placeholder's JSON value
 * (a parameter may be a number, list or object); placeholders inside longer strings are replaced by their text.
 *
 * <ul>
 *   <li>{@code ${param.<name>}} a declared pack parameter</li>
 *   <li>{@code ${ref.<item key>}} the resource id of an item processed earlier</li>
 *   <li>{@code ${parent}} the resource id of the parent item</li>
 *   <li>{@code ${today}} the date of the first attempt (ISO), in the pack's {@code timezone} parameter</li>
 *   <li>{@code ${tenant_id}}, {@code ${pack.code}}, {@code ${pack.version}}</li>
 * </ul>
 */
public final class PackTemplates {
    public static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([a-z_]+(?:\\.[A-Za-z0-9_./-]+)?)}");

    private PackTemplates() {}

    /** Values available while rendering; {@code refs} returns null for an item without a resource id. */
    public record Context(Map<String, JsonNode> params, Function<String, String> refs, String parentId, String today,
                          String tenantId, String packCode, String packVersion) {}

    /** A placeholder that has no value yet (e.g. a reference to an item that was skipped). */
    public static final class Unresolved extends RuntimeException {
        public Unresolved(String placeholder) {
            super("unresolved placeholder ${" + placeholder + "}");
        }
    }

    public static JsonNode render(JsonNode template, Context ctx) {
        return walk(template, s -> resolve(s, ctx));
    }

    /** Substitutes only parameters (stable inputs); used for checksums so references and dates do not count. */
    public static JsonNode renderParams(JsonNode template, Map<String, JsonNode> params) {
        return walk(template, s -> s.startsWith("param.") ? params.get(s.substring(6)) : null);
    }

    /** SHA-256 of the canonical JSON (keys sorted) of the item's own definition with parameters substituted. */
    public static String checksum(StarterPack.Item item, Map<String, JsonNode> params) {
        String canonical = canonical(renderParams(item.ownDefinition(), params));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String canonical(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return "null";
        }
        if (n.isObject()) {
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            n.properties().forEach(e -> sorted.put(e.getKey(), e.getValue()));
            StringBuilder b = new StringBuilder("{");
            sorted.forEach((k, v) -> b.append(b.length() > 1 ? "," : "").append(JsonNodeFactory.instance.stringNode(k))
                    .append(':').append(canonical(v)));
            return b.append('}').toString();
        }
        if (n.isArray()) {
            StringBuilder b = new StringBuilder("[");
            for (JsonNode x : n) {
                b.append(b.length() > 1 ? "," : "").append(canonical(x));
            }
            return b.append(']').toString();
        }
        return n.toString();
    }

    /** Placeholder names used in a template (without ${}). */
    public static Set<String> placeholders(JsonNode template) {
        Set<String> out = new LinkedHashSet<>();
        walk(template, s -> {
            out.add(s);
            return null;
        });
        return out;
    }

    public static boolean matchesType(JsonNode v, String type) {
        if (v == null || v.isNull()) {
            return false;
        }
        return switch (type) {
            case "string" -> v.isString();
            case "integer" -> v.isIntegralNumber();
            case "number" -> v.isNumber() || (v.isString() && v.asString().matches("-?\\d+(\\.\\d+)?"));
            case "boolean" -> v.isBoolean();
            case "array" -> v.isArray();
            case "object" -> v.isObject();
            default -> false;
        };
    }

    /**
     * Static checks of every placeholder of a pack: parameters are declared, references point to items processed
     * earlier (a parent before its children, earlier steps before later ones), {@code ${parent}} only in children.
     */
    static List<String> checkPlaceholders(StarterPack pack) {
        List<String> problems = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (StarterPack.Item item : pack.items()) {
            Set<String> used = new LinkedHashSet<>(placeholders(item.body()));
            if (item.lookup() != null) {
                used.addAll(placeholders(item.lookup().query()));
                used.addAll(placeholders(item.lookup().match()));
            }
            item.actions().forEach(a -> used.addAll(placeholders(a.body())));
            for (String p : used) {
                if (p.startsWith("param.")) {
                    if (!pack.parameters().containsKey(p.substring(6))) {
                        problems.add(item.key() + ": undeclared parameter ${" + p + "}");
                    }
                } else if (p.startsWith("ref.")) {
                    if (!seen.contains(p.substring(4))) {
                        problems.add(item.key() + ": ${" + p + "} must reference an item processed earlier");
                    }
                } else if ("parent".equals(p)) {
                    if (item.parentKey() == null) {
                        problems.add(item.key() + ": ${parent} used outside a child item");
                    }
                } else if (!Set.of("today", "tenant_id", "pack.code", "pack.version").contains(p)) {
                    problems.add(item.key() + ": unknown placeholder ${" + p + "}");
                }
            }
            seen.add(item.key());
        }
        return problems;
    }

    private static JsonNode resolve(String p, Context ctx) {
        JsonNodeFactory f = JsonNodeFactory.instance;
        if (p.startsWith("param.")) {
            JsonNode v = ctx.params().get(p.substring(6));
            if (v == null) {
                throw new Unresolved(p);
            }
            return v;
        }
        if (p.startsWith("ref.")) {
            String id = ctx.refs().apply(p.substring(4));
            if (id == null) {
                throw new Unresolved(p);
            }
            return f.stringNode(id);
        }
        return switch (p) {
            case "parent" -> {
                if (ctx.parentId() == null) {
                    throw new Unresolved(p);
                }
                yield f.stringNode(ctx.parentId());
            }
            case "today" -> f.stringNode(ctx.today());
            case "tenant_id" -> f.stringNode(ctx.tenantId());
            case "pack.code" -> f.stringNode(ctx.packCode());
            case "pack.version" -> f.stringNode(ctx.packVersion());
            default -> throw new Unresolved(p);
        };
    }

    /** Copies the tree, replacing placeholders; {@code fn} returns null to leave a placeholder untouched. */
    private static JsonNode walk(JsonNode n, Function<String, JsonNode> fn) {
        if (n == null) {
            return null;
        }
        if (n.isObject()) {
            ObjectNode o = JsonNodeFactory.instance.objectNode();
            n.properties().forEach(e -> o.set(e.getKey(), walk(e.getValue(), fn)));
            return o;
        }
        if (n.isArray()) {
            ArrayNode a = JsonNodeFactory.instance.arrayNode();
            n.forEach(x -> a.add(walk(x, fn)));
            return a;
        }
        if (!n.isString()) {
            return n;
        }
        String s = n.asString();
        Matcher whole = PLACEHOLDER.matcher(s);
        if (whole.matches()) {
            JsonNode v = fn.apply(whole.group(1));
            return v == null ? n : v;
        }
        Matcher m = PLACEHOLDER.matcher(s);
        StringBuilder b = new StringBuilder();
        boolean changed = false;
        while (m.find()) {
            JsonNode v = fn.apply(m.group(1));
            String text = v == null ? m.group() : (v.isString() ? v.asString() : v.toString());
            changed |= v != null;
            m.appendReplacement(b, Matcher.quoteReplacement(text));
        }
        m.appendTail(b);
        return changed ? JsonNodeFactory.instance.stringNode(b.toString()) : n;
    }
}
