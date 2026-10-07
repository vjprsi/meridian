package io.meridian.search.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The declarative domain-event -> search-document mapping ({@code search-mappings.yaml}): which events feed which
 * entity index, how a payload becomes a normalised document, and its lifecycle (upsert / closed / deleted).
 * Documents are built from an allow-list of payload fields only; mappings naming forbidden fields (card secrets,
 * credentials, national or tax identifiers, dates of birth, narratives, device and location data) are refused at
 * load time, and any string that looks like a card number (13-19 digits passing Luhn) is redacted at build time.
 */
public final class FeedMappings {
    /** Payload fields that must never reach the search index, matched on whole {@code _}-separated words. */
    public static final Pattern FORBIDDEN = Pattern.compile(
            "(^|_|\\.)(pan|cvv|cvv2|cvc|cvc2|pin|pins|pinblock|track|track1|track2|password|passcode|secret|otp|token|"
                    + "private|key|fingerprint|dob|birth|ssn|nin|tax|national|passport|narrative|description|details|"
                    + "summary|note|notes|comment|comments|ip|device|geo|lat|lon|latitude|longitude|income|salary)"
                    + "(_|$|\\.)");
    private static final Pattern PATH = Pattern.compile("[a-zA-Z0-9_.]+");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z0-9_.]+)}");
    private static final Pattern CARD_NUMBER = Pattern.compile("(?<!\\d)\\d(?:[ -]?\\d){12,18}(?!\\d)");
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    /** Lifecycle of an indexed document. */
    public enum Operation { UPSERT, CLOSE, DELETE }

    public record Mapping(String docKind, String entityType, String topic, String schema, Set<String> events,
                          String prefix, Set<String> exclude, String id, List<String> reference, List<String> title,
                          List<String> subtitle, List<String> keywords, List<String> identifiers, String last4,
                          String customer, List<String> attributes, Set<String> closedEvents,
                          Set<String> closedStatuses, Set<String> deleteEvents, Set<String> deleteStatuses,
                          List<String> deleteWhenPresent) {

        boolean matches(String eventType) {
            if (events.contains(eventType)) {
                return true;
            }
            return prefix != null && eventType.startsWith(prefix) && !exclude.contains(eventType);
        }

        /** Every payload field path the mapping reads (for validation against the event schemas). */
        public Set<String> fieldPaths() {
            Set<String> out = new LinkedHashSet<>();
            out.add(id);
            for (List<String> l : List.of(reference, title, subtitle, keywords, identifiers, attributes, deleteWhenPresent)) {
                l.forEach(t -> out.addAll(paths(t)));
            }
            if (last4 != null) {
                out.add(last4);
            }
            if (customer != null) {
                out.add(customer);
            }
            out.add("status");
            return out;
        }

        public Operation operation(String eventType, JsonNode payload) {
            String status = text(payload, "status");
            if (deleteEvents.contains(eventType) || (status != null && deleteStatuses.contains(status))
                    || deleteWhenPresent.stream().anyMatch(f -> !text(payload, f, "").isEmpty())) {
                return Operation.DELETE;
            }
            if (closedEvents.contains(eventType) || (status != null && closedStatuses.contains(status))) {
                return Operation.CLOSE;
            }
            return Operation.UPSERT;
        }

        public String entityId(JsonNode payload) {
            return text(payload, id);
        }

        /** The normalised search document of a payload (allow-listed fields only). */
        public ObjectNode document(String eventType, JsonNode payload, Operation op) {
            ObjectNode d = NODES.objectNode();
            d.put("doc_kind", docKind);
            putIfPresent(d, "title", first(payload, title));
            putIfPresent(d, "subtitle", joined(payload, subtitle));
            putIfPresent(d, "reference", first(payload, reference));
            if (last4 != null) {
                String l4 = text(payload, last4, "");
                if (l4.matches("\\d{4}")) {
                    d.put("last4", l4);
                }
            }
            putArray(d, "keywords", payload, keywords);
            putArray(d, "identifiers", payload, identifiers);
            putIfPresent(d, "status", text(payload, "status"));
            d.put("lifecycle", op == Operation.CLOSE ? "CLOSED" : "ACTIVE");
            if (customer != null) {
                putIfPresent(d, "customer_id", text(payload, customer));
            }
            ObjectNode attrs = NODES.objectNode();
            for (String a : attributes) {
                JsonNode v = value(payload, a);
                if (v != null && !v.isNull() && v.isValueNode()) {
                    attrs.set(a.replace('.', '_'), v.isString() ? NODES.stringNode(redact(v.asString())) : v);
                }
            }
            if (!attrs.isEmpty()) {
                d.set("attributes", attrs);
            }
            d.put("source_event_type", eventType);
            return d;
        }
    }

    private final List<Mapping> mappings;
    private final List<String> searchableFields;
    private final List<String> maskedFields;
    private final List<String> neverIndex;

    private FeedMappings(List<Mapping> mappings, List<String> searchableFields, List<String> maskedFields,
                         List<String> neverIndex) {
        this.mappings = List.copyOf(mappings);
        this.searchableFields = List.copyOf(searchableFields);
        this.maskedFields = List.copyOf(maskedFields);
        this.neverIndex = List.copyOf(neverIndex);
    }

    /** Builds and validates the mappings from the parsed YAML document. */
    @SuppressWarnings("unchecked")
    public static FeedMappings from(Map<String, Object> yaml) {
        Map<String, Object> index = (Map<String, Object>) yaml.getOrDefault("index", Map.of());
        List<String> searchable = strings(index.get("searchable_fields"));
        List<String> masked = strings(index.get("masked_fields"));
        List<String> never = strings(yaml.get("never_index"));
        List<Mapping> out = new ArrayList<>();
        Set<String> seenEvents = new LinkedHashSet<>();
        for (Object o : (List<Object>) yaml.getOrDefault("mappings", List.of())) {
            Map<String, Object> m = (Map<String, Object>) o;
            Mapping mapping = new Mapping(req(m, "doc_kind"), IndexRules.entityType(req(m, "entity_type")),
                    req(m, "topic"), (String) m.get("schema"), new LinkedHashSet<>(strings(m.get("events"))),
                    (String) m.get("prefix"), new LinkedHashSet<>(strings(m.get("exclude"))),
                    (String) m.getOrDefault("id", "id"), strings(m.get("reference")), strings(m.get("title")),
                    strings(m.get("subtitle")), strings(m.get("keywords")), strings(m.get("identifiers")),
                    (String) m.get("last4"), (String) m.get("customer"), strings(m.get("attributes")),
                    new LinkedHashSet<>(strings(m.get("closed_events"))), new LinkedHashSet<>(strings(m.get("closed_statuses"))),
                    new LinkedHashSet<>(strings(m.get("delete_events"))), new LinkedHashSet<>(strings(m.get("delete_statuses"))),
                    strings(m.get("delete_when_present")));
            validate(mapping, never);
            for (String e : mapping.events()) {
                if (!seenEvents.add(e)) {
                    throw new IllegalStateException("search mapping: event " + e + " is mapped twice");
                }
            }
            out.add(mapping);
        }
        if (searchable.isEmpty()) {
            throw new IllegalStateException("search mapping: index.searchable_fields is required");
        }
        return new FeedMappings(out, searchable, masked, never);
    }

    private static void validate(Mapping m, List<String> never) {
        String where = "search mapping " + m.docKind() + ": ";
        if (!m.topic().matches("meridian\\.[a-z_-]+\\.v\\d+")) {
            throw new IllegalStateException(where + "topic must look like meridian.<domain>.v1");
        }
        if (m.events().isEmpty() && m.prefix() == null) {
            throw new IllegalStateException(where + "events or prefix is required");
        }
        for (String e : m.events()) {
            if (never.stream().anyMatch(e::startsWith)) {
                throw new IllegalStateException(where + e + " must never be indexed");
            }
        }
        if (m.prefix() != null && never.stream().anyMatch(n -> n.startsWith(m.prefix()) || m.prefix().startsWith(n))) {
            throw new IllegalStateException(where + "prefix " + m.prefix() + " covers events that must never be indexed");
        }
        if (m.title().isEmpty()) {
            throw new IllegalStateException(where + "title is required");
        }
        for (String path : m.fieldPaths()) {
            if (FORBIDDEN.matcher(path.toLowerCase(Locale.ROOT)).find()) {
                throw new IllegalStateException(where + "field " + path + " must never be indexed");
            }
        }
    }

    public List<Mapping> all() {
        return mappings;
    }

    public List<String> searchableFields() {
        return searchableFields;
    }

    public List<String> maskedFields() {
        return maskedFields;
    }

    /** The mapping of an event type; empty when unmapped or never indexable. */
    public Optional<Mapping> resolve(String eventType) {
        if (eventType == null || neverIndex.stream().anyMatch(eventType::startsWith)) {
            return Optional.empty();
        }
        return mappings.stream().filter(m -> m.matches(eventType)).findFirst();
    }

    /** Topics feeding an entity type (index definition's source_topics). */
    public List<String> topics(String entityType) {
        return mappings.stream().filter(m -> m.entityType().equals(entityType)).map(Mapping::topic).distinct().toList();
    }

    /** Index field mapping (properties) of the normalised documents. */
    public static ObjectNode indexMapping() {
        ObjectNode props = NODES.objectNode();
        for (String f : List.of("title", "subtitle", "keywords")) {
            props.putObject(f).put("type", "text");
        }
        for (String f : List.of("doc_kind", "reference", "last4", "identifiers", "status", "lifecycle", "customer_id",
                "source_event_type")) {
            props.putObject(f).put("type", "keyword");
        }
        props.putObject("attributes").put("type", "object");
        ObjectNode mapping = NODES.objectNode();
        mapping.set("properties", props);
        return mapping;
    }

    // ------------------------------------------------------------------ templates

    /**
     * Renders a field path, a {placeholder} template (blank when every placeholder is blank) or a literal text
     * (anything else, e.g. a fallback title "Fraud alert").
     */
    public static String render(JsonNode payload, String template) {
        if (!template.contains("{")) {
            return PATH.matcher(template).matches() ? redact(text(payload, template, "")).trim() : template;
        }
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        boolean any = false;
        while (m.find()) {
            String v = redact(text(payload, m.group(1), "")).trim();
            any |= !v.isEmpty();
            m.appendReplacement(sb, Matcher.quoteReplacement(v));
        }
        m.appendTail(sb);
        return any ? sb.toString().replaceAll("\\s+", " ").trim() : "";
    }

    static List<String> paths(String template) {
        if (!template.contains("{")) {
            return PATH.matcher(template).matches() ? List.of(template) : List.of();
        }
        List<String> out = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(template);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /** Replaces anything that looks like a full card number (13-19 digits, Luhn-valid) by its masked form. */
    public static String redact(String s) {
        if (s == null || s.length() < 13) {
            return s;
        }
        Matcher m = CARD_NUMBER.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String digits = m.group().replaceAll("\\D", "");
            m.appendReplacement(sb, luhn(digits) ? "**** " + digits.substring(digits.length() - 4) : m.group());
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static boolean luhn(String digits) {
        int sum = 0;
        boolean dbl = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (dbl) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            dbl = !dbl;
        }
        return sum % 10 == 0;
    }

    private static String first(JsonNode payload, List<String> templates) {
        return templates.stream().map(t -> render(payload, t)).filter(s -> !s.isEmpty()).findFirst().orElse(null);
    }

    private static String joined(JsonNode payload, List<String> templates) {
        String s = String.join(" · ", templates.stream().map(t -> render(payload, t)).filter(v -> !v.isEmpty()).toList());
        return s.isEmpty() ? null : s;
    }

    private static void putArray(ObjectNode d, String name, JsonNode payload, List<String> paths) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        paths.forEach(p -> {
            String v = render(payload, p);
            if (!v.isEmpty()) {
                values.add(v);
            }
        });
        if (!values.isEmpty()) {
            ArrayNode a = d.putArray(name);
            values.forEach(a::add);
        }
    }

    private static void putIfPresent(ObjectNode d, String name, String v) {
        if (v != null && !v.isBlank()) {
            d.put(name, v);
        }
    }

    static JsonNode value(JsonNode payload, String path) {
        JsonNode n = payload;
        for (String p : path.split("\\.")) {
            if (n == null || !n.isObject()) {
                return null;
            }
            n = n.get(p);
        }
        return n;
    }

    static String text(JsonNode payload, String path) {
        JsonNode v = value(payload, path);
        return v == null || v.isNull() || !v.isValueNode() ? null : v.asString();
    }

    private static String text(JsonNode payload, String path, String dflt) {
        String v = text(payload, path);
        return v == null ? dflt : v;
    }

    private static String req(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new IllegalStateException("search mapping: " + key + " is required in " + m);
        }
        return v.toString();
    }

    private static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            l.forEach(v -> {
                if (v != null && !v.toString().isBlank()) {
                    out.add(v.toString());
                }
            });
        } else if (o != null && !o.toString().isBlank()) {
            out.add(o.toString());
        }
        return out;
    }
}
