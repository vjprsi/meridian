package io.meridian.channel.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * USSD dialogue state machine (pure: no Spring, no SQL). Given a menu tree, the session state and the latest
 * subscriber input it produces the next screen ("CON ..." to continue, "END ..." to close) and the new state.
 *
 * <ul>
 *   <li>MENU lists its visible children by option number ("0" goes back, "00" returns to the main menu).</li>
 *   <li>INPUT validates the reply (input type + regex) and stores it under its context key; PIN inputs are never
 *       stored - they are handed straight to an ACTION that follows in the same request.</li>
 *   <li>CONFIRM accepts 1 (yes, continue) or 2 (no, the session ends cancelled).</li>
 *   <li>ACTION runs through the {@link ActionExecutor}; with requires_pin it first asks for the PIN and executes in
 *       the request that carries it. END closes the session.</li>
 *   <li>Three invalid replies in a row end the session; nodes with min_kyc_tier above the subscriber's are hidden;
 *       {placeholders} in texts are filled from the context.</li>
 * </ul>
 */
public final class UssdEngine {
    public static final int MAX_TEXT = 182;
    public static final int MAX_INVALID = 3;
    public static final String MASK = "****";

    private UssdEngine() {}

    public record Node(String key, String parentKey, String type, Integer optionNumber, String label, String prompt,
                       String inputType, String inputValidation, String contextKey, String actionCode,
                       String nextNodeKey, boolean requiresPin, Integer minKycTier, Integer displayOrder) {}

    public record Menu(String rootKey, List<Node> nodes) {
        public Node node(String key) {
            for (Node n : nodes) {
                if (n.key().equals(key)) {
                    return n;
                }
            }
            return null;
        }

        public List<Node> children(String key, Integer kycTier) {
            return nodes.stream()
                    .filter(n -> key.equals(n.parentKey()) && n.optionNumber() != null)
                    .filter(n -> n.minKycTier() == null || kycTier == null || kycTier >= n.minKycTier())
                    .sorted(Comparator.comparing((Node n) -> n.optionNumber())
                            .thenComparing(n -> n.displayOrder() == null ? 0 : n.displayOrder()))
                    .toList();
        }
    }

    /**
     * Session state between requests.
     *
     * @param currentKey   node shown last (null before the first screen)
     * @param context      collected inputs (never PINs)
     * @param stack        menu path for "0" (back)
     * @param invalid      consecutive invalid replies
     * @param awaitingPin  the current ACTION node asked for the PIN
     */
    public record State(String currentKey, Map<String, String> context, List<String> stack, int invalid,
                        boolean awaitingPin) {
        public static State start(Map<String, String> context) {
            return new State(null, new LinkedHashMap<>(context), new ArrayList<>(), 0, false);
        }
    }

    /** Executes an action code; must not throw for business failures (return a friendly message instead). */
    public interface ActionExecutor {
        Result execute(String actionCode, Map<String, String> context, String pin);
    }

    public record Result(boolean ok, String message, UUID walletTransactionId) {}

    /**
     * Outcome of one request.
     *
     * @param text          screen text without the CON/END prefix
     * @param end           the session ends with this screen
     * @param nodeKey       node served
     * @param loggedInput   input as it may be logged (PINs masked)
     * @param endReason     COMPLETED, CANCELLED, INVALID_INPUT, ACTION_FAILED, ... when ended
     */
    public record Screen(String text, boolean end, String nodeKey, String loggedInput, State state, String endReason,
                         Result action) {
        public String response() {
            return (end ? "END " : "CON ") + text;
        }
    }

    /** Processes one request: {@code input} is null/empty on the first (dial) request. */
    public static Screen step(Menu menu, State s, String input, Integer kycTier, ActionExecutor exec) {
        String in = input == null ? "" : input.trim();
        if (s.currentKey() == null) {
            return enter(menu, s, menu.rootKey(), kycTier, exec, null, null, null);
        }
        Node cur = menu.node(s.currentKey());
        if (cur == null) {
            return end(s, menu.rootKey(), "Service error. Please try again.", in, "INVALID_MENU", null);
        }
        if (s.awaitingPin()) {
            if (!in.matches("\\d{4,6}(\\*\\d{4,6})?")) {
                return invalid(menu, s, cur, MASK, kycTier, "Invalid PIN. ");
            }
            return runWith(s, cur, in, MASK, exec);
        }
        if ("00".equals(in) && !"INPUT".equals(cur.type())) {
            State home = new State(null, s.context(), new ArrayList<>(), 0, false);
            return enter(menu, home, menu.rootKey(), kycTier, exec, in, null, null);
        }
        switch (cur.type()) {
            case "MENU" -> {
                if ("0".equals(in)) {
                    List<String> stack = new ArrayList<>(s.stack());
                    String back = stack.isEmpty() ? menu.rootKey() : stack.removeLast();
                    return enter(menu, new State(null, s.context(), stack, 0, false), back, kycTier, exec, in, null, null);
                }
                for (Node child : menu.children(cur.key(), kycTier)) {
                    if (in.equals(String.valueOf(child.optionNumber()))) {
                        List<String> stack = new ArrayList<>(s.stack());
                        stack.add(cur.key());
                        return enter(menu, new State(null, s.context(), stack, 0, false), child.key(), kycTier, exec,
                                in, null, null);
                    }
                }
                return invalid(menu, s, cur, in, kycTier, "Invalid choice. ");
            }
            case "INPUT" -> {
                boolean pin = "PIN".equals(cur.inputType());
                String logged = pin ? MASK : in;
                if (!valid(cur, in)) {
                    return invalid(menu, s, cur, logged, kycTier, "Invalid input. ");
                }
                Map<String, String> ctx = new LinkedHashMap<>(s.context());
                if (!pin) {
                    ctx.put(contextKey(cur), normalise(cur, in));
                }
                State next = new State(null, ctx, s.stack(), 0, false);
                if (cur.nextNodeKey() == null) {
                    return end(next, cur.key(), "Thank you.", logged, "COMPLETED", null);
                }
                return enter(menu, next, cur.nextNodeKey(), kycTier, exec, logged, pin ? in : null, null);
            }
            case "CONFIRM" -> {
                if ("2".equals(in)) {
                    return end(s, cur.key(), "Cancelled. No money was moved.", in, "CANCELLED", null);
                }
                if (!"1".equals(in)) {
                    return invalid(menu, s, cur, in, kycTier, "Reply 1 or 2. ");
                }
                State next = new State(null, s.context(), s.stack(), 0, false);
                if (cur.nextNodeKey() == null) {
                    return end(next, cur.key(), "Thank you.", in, "COMPLETED", null);
                }
                return enter(menu, next, cur.nextNodeKey(), kycTier, exec, in, null, null);
            }
            default -> {
                return end(s, cur.key(), fill(cur.label(), s.context()), in, "COMPLETED", null);
            }
        }
    }

    /** Shows (or, for ACTION / END, runs) a node reached by navigation. */
    private static Screen enter(Menu menu, State s, String key, Integer kycTier, ActionExecutor exec, String logged,
                                String pin, String prefix) {
        Node n = menu.node(key);
        if (n == null) {
            return end(s, key, "Service error. Please try again.", logged, "INVALID_MENU", null);
        }
        if (n.minKycTier() != null && kycTier != null && kycTier < n.minKycTier()) {
            return end(s, key, "This service needs a higher account level. Visit an agent to upgrade.", logged,
                    "KYC_TIER", null);
        }
        State at = new State(key, s.context(), s.stack(), s.invalid(), false);
        String pre = prefix == null ? "" : prefix;
        return switch (n.type()) {
            case "MENU" -> new Screen(cut(pre + renderMenu(menu, n, s.context(), kycTier)), false, key, logged, at, null, null);
            case "INPUT" -> new Screen(cut(pre + fill(n.prompt() != null ? n.prompt() : n.label(), s.context())), false, key,
                    logged, at, null, null);
            case "CONFIRM" -> new Screen(cut(pre + fill(n.label(), s.context()) + "\n1. Yes\n2. No"), false, key, logged, at,
                    null, null);
            case "ACTION" -> {
                if (pin != null) {
                    yield runWith(at, n, pin, logged, exec);
                }
                if (n.requiresPin()) {
                    State waiting = new State(key, s.context(), s.stack(), s.invalid(), true);
                    yield new Screen(cut(pre + fill(n.prompt() != null ? n.prompt() : "Enter your PIN", s.context())),
                            false, key, logged, waiting, null, null);
                }
                yield runWith(at, n, null, logged, exec);
            }
            default -> end(at, key, fill(n.label(), s.context()), logged, "COMPLETED", null);
        };
    }

    private static Screen runWith(State s, Node n, String pin, String logged, ActionExecutor exec) {
        Result r = exec.execute(n.actionCode(), s.context(), pin);
        String text = r.message();
        if (r.ok() && n.label() != null && n.label().contains("{result}")) {
            Map<String, String> ctx = new LinkedHashMap<>(s.context());
            ctx.put("result", r.message());
            text = fill(n.label(), ctx);
        }
        return end(s, n.key(), text, logged, r.ok() ? "COMPLETED" : "ACTION_FAILED", r);
    }

    private static Screen invalid(Menu menu, State s, Node cur, String logged, Integer kycTier, String why) {
        int count = s.invalid() + 1;
        if (count >= MAX_INVALID) {
            return end(s, cur.key(), "Too many invalid entries. Please dial again.", logged, "INVALID_INPUT", null);
        }
        String body;
        if (s.awaitingPin()) {
            body = fill(cur.prompt() != null ? cur.prompt() : "Enter your PIN", s.context());
        } else {
            body = switch (cur.type()) {
                case "MENU" -> renderMenu(menu, cur, s.context(), kycTier);
                case "CONFIRM" -> fill(cur.label(), s.context()) + "\n1. Yes\n2. No";
                default -> fill(cur.prompt() != null ? cur.prompt() : cur.label(), s.context());
            };
        }
        State again = new State(cur.key(), s.context(), s.stack(), count, s.awaitingPin());
        return new Screen(cut(why + body), false, cur.key(), logged, again, null, null);
    }

    private static Screen end(State s, String key, String text, String logged, String reason, Result r) {
        State done = new State(key, s.context(), s.stack(), s.invalid(), false);
        return new Screen(cut(text), true, key, logged, done, reason, r);
    }

    static String renderMenu(Menu menu, Node n, Map<String, String> ctx, Integer kycTier) {
        StringBuilder sb = new StringBuilder(fill(n.label(), ctx));
        for (Node c : menu.children(n.key(), kycTier)) {
            sb.append('\n').append(c.optionNumber()).append(". ").append(fill(c.label(), ctx));
        }
        return sb.toString();
    }

    public static String contextKey(Node n) {
        return n.contextKey() == null || n.contextKey().isBlank() ? n.key() : n.contextKey();
    }

    /** Type check plus the node's regex (full match). */
    public static boolean valid(Node n, String in) {
        if (in.isEmpty()) {
            return false;
        }
        String type = n.inputType() == null ? "TEXT" : n.inputType();
        boolean ok = switch (type) {
            case "NUMBER" -> in.matches("\\d{1,18}");
            case "AMOUNT" -> in.matches("\\d{1,15}(\\.\\d{1,2})?") && new BigDecimal(in).signum() > 0;
            case "MSISDN" -> in.matches("\\+?\\d{7,15}");
            case "PIN" -> in.matches("\\d{4,6}");
            default -> in.length() <= MAX_TEXT;
        };
        if (!ok) {
            return false;
        }
        if (n.inputValidation() != null && !n.inputValidation().isBlank()) {
            try {
                return Pattern.compile(n.inputValidation()).matcher(in).matches();
            } catch (PatternSyntaxException e) {
                return false;
            }
        }
        return true;
    }

    static String normalise(Node n, String in) {
        return "AMOUNT".equals(n.inputType()) ? new BigDecimal(in).stripTrailingZeros().toPlainString() : in;
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z0-9_]+)}");

    public static String fill(String text, Map<String, String> ctx) {
        if (text == null) {
            return "";
        }
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = ctx.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? m.group(0) : v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static String cut(String s) {
        return s.length() <= MAX_TEXT ? s : s.substring(0, MAX_TEXT - 3) + "...";
    }

    /** Validates a menu tree before publication; returns the problems (empty = publishable). */
    public static List<String> validate(Menu menu) {
        List<String> errors = new ArrayList<>();
        if (menu.node(menu.rootKey()) == null) {
            errors.add("root node " + menu.rootKey() + " does not exist");
            return errors;
        }
        for (Node n : menu.nodes()) {
            if ("MENU".equals(n.type()) && menu.children(n.key(), null).isEmpty()) {
                errors.add("menu node " + n.key() + " has no options");
            }
            if (n.nextNodeKey() != null && menu.node(n.nextNodeKey()) == null) {
                errors.add("node " + n.key() + " points to missing next node " + n.nextNodeKey());
            }
            if ("ACTION".equals(n.type()) && (n.actionCode() == null || n.actionCode().isBlank())) {
                errors.add("action node " + n.key() + " has no action_code");
            }
            if ("INPUT".equals(n.type()) && n.nextNodeKey() == null) {
                errors.add("input node " + n.key() + " has no next_node_key");
            }
            if (n.parentKey() != null && menu.node(n.parentKey()) == null) {
                errors.add("node " + n.key() + " has a missing parent " + n.parentKey());
            }
        }
        // reachability from the root via options and next links
        List<String> seen = new ArrayList<>();
        List<String> todo = new ArrayList<>(List.of(menu.rootKey()));
        while (!todo.isEmpty()) {
            String k = todo.removeLast();
            if (seen.contains(k) || menu.node(k) == null) {
                continue;
            }
            seen.add(k);
            Node n = menu.node(k);
            menu.children(k, null).forEach(c -> todo.add(c.key()));
            if (n.nextNodeKey() != null) {
                todo.add(n.nextNodeKey());
            }
        }
        for (Node n : menu.nodes()) {
            if (!seen.contains(n.key())) {
                errors.add("node " + n.key() + " is not reachable from the root");
            }
        }
        return errors;
    }
}
