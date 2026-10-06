package io.meridian.channel.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UssdEngineTest {
    static UssdEngine.Node node(String key, String parent, String type, Integer option, String label) {
        return new UssdEngine.Node(key, parent, type, option, label, null, null, null, null, null, null, false, null, null);
    }

    static final UssdEngine.Menu MENU = new UssdEngine.Menu("main", List.of(
            node("main", null, "MENU", null, "Welcome {name}"),
            new UssdEngine.Node("send", "main", "INPUT", 1, "Send money", "Enter number", "MSISDN", null, "payee_msisdn",
                    null, "amt", false, null, null),
            new UssdEngine.Node("amt", null, "INPUT", null, "Amount", "Enter amount", "AMOUNT", null, "amount", null,
                    "confirm", false, null, null),
            new UssdEngine.Node("confirm", null, "CONFIRM", null, "Send {amount} to {payee_msisdn}?", null, null, null, null,
                    null, "pay", false, null, null),
            new UssdEngine.Node("pay", null, "ACTION", null, "Pay", "Enter PIN", null, null, null, "P2P_SEND", null, true,
                    null, null),
            new UssdEngine.Node("bal", "main", "ACTION", 2, "Balance", null, null, null, null, "BALANCE", null, false, null, null),
            new UssdEngine.Node("more", "main", "MENU", 3, "More", null, null, null, null, null, null, false, null, null),
            node("help", "more", "END", 1, "Call 100"),
            new UssdEngine.Node("loans", "main", "END", 4, "Loans", null, null, null, null, null, null, false, 2, null)));

    final List<String> calls = new ArrayList<>();
    final UssdEngine.ActionExecutor exec = (code, ctx, pin) -> {
        calls.add(code + ":" + ctx.get("amount") + ":" + pin);
        return new UssdEngine.Result(true, "Done " + code, null);
    };

    UssdEngine.Screen go(UssdEngine.State s, String in, Integer tier) {
        return UssdEngine.step(MENU, s, in, tier, exec);
    }

    @Test
    void walksAPaymentAndExecutesWithThePinWithoutStoringIt() {
        UssdEngine.Screen s = go(UssdEngine.State.start(Map.of("name", "Alice")), null, 0);
        assertThat(s.response()).isEqualTo("CON Welcome Alice\n1. Send money\n2. Balance\n3. More");
        s = go(s.state(), "1", 0);
        assertThat(s.response()).isEqualTo("CON Enter number");
        s = go(s.state(), "0712345678", 0);
        s = go(s.state(), "500.50", 0);
        assertThat(s.response()).isEqualTo("CON Send 500.5 to 0712345678?\n1. Yes\n2. No");
        s = go(s.state(), "1", 0);
        assertThat(s.response()).isEqualTo("CON Enter PIN");
        assertThat(s.state().awaitingPin()).isTrue();
        s = go(s.state(), "4826", 0);
        assertThat(s.end()).isTrue();
        assertThat(s.response()).isEqualTo("END Done P2P_SEND");
        assertThat(s.loggedInput()).isEqualTo("****");
        assertThat(s.state().context()).doesNotContainValue("4826");
        assertThat(calls).containsExactly("P2P_SEND:500.5:4826");
    }

    @Test
    void invalidInputRepromptsAndThreeStrikesEnd() {
        UssdEngine.Screen s = go(UssdEngine.State.start(Map.of()), null, 0);
        s = go(s.state(), "9", 0);
        assertThat(s.response()).startsWith("CON Invalid choice. Welcome");
        s = go(s.state(), "x", 0);
        assertThat(s.end()).isFalse();
        s = go(s.state(), "y", 0);
        assertThat(s.end()).isTrue();
        assertThat(s.endReason()).isEqualTo("INVALID_INPUT");
    }

    @Test
    void backMainMenuConfirmNoAndActionsWithoutPin() {
        UssdEngine.Screen s = go(UssdEngine.State.start(Map.of()), null, 0);
        s = go(s.state(), "3", 0);
        assertThat(s.response()).isEqualTo("CON More\n1. Call 100");
        s = go(s.state(), "0", 0);
        assertThat(s.response()).startsWith("CON Welcome");
        s = go(s.state(), "3", 0);
        s = go(s.state(), "00", 0);
        assertThat(s.nodeKey()).isEqualTo("main");
        s = go(s.state(), "2", 0);
        assertThat(s.response()).isEqualTo("END Done BALANCE");

        UssdEngine.Screen c = go(UssdEngine.State.start(Map.of()), null, 0);
        c = go(c.state(), "1", 0);
        c = go(c.state(), "+254712345678", 0);
        c = go(c.state(), "10", 0);
        c = go(c.state(), "2", 0);
        assertThat(c.end()).isTrue();
        assertThat(c.endReason()).isEqualTo("CANCELLED");
    }

    @Test
    void kycTierHidesOptionsAndPlaceholdersAndValidation() {
        assertThat(go(UssdEngine.State.start(Map.of()), null, 2).response()).contains("4. Loans");
        assertThat(go(UssdEngine.State.start(Map.of()), null, 0).response()).doesNotContain("Loans");
        UssdEngine.Screen s = go(UssdEngine.State.start(Map.of()), null, 0);
        assertThat(go(s.state(), "4", 0).response()).startsWith("CON Invalid choice");
        assertThat(UssdEngine.fill("Hi {name}, {missing}", Map.of("name", "Bo"))).isEqualTo("Hi Bo, {missing}");
        UssdEngine.Node amount = MENU.node("amt");
        assertThat(UssdEngine.valid(amount, "10.5")).isTrue();
        assertThat(UssdEngine.valid(amount, "0")).isFalse();
        assertThat(UssdEngine.valid(amount, "1.234")).isFalse();
        UssdEngine.Node regex = new UssdEngine.Node("r", null, "INPUT", null, "x", null, "TEXT", "[A-Z]{3}", null, null,
                "x", false, null, null);
        assertThat(UssdEngine.valid(regex, "ABC")).isTrue();
        assertThat(UssdEngine.valid(regex, "ABCD")).isFalse();
    }

    @Test
    void treeValidationFindsProblems() {
        assertThat(UssdEngine.validate(MENU)).isEmpty();
        UssdEngine.Menu broken = new UssdEngine.Menu("main", List.of(
                node("main", null, "MENU", null, "Main"),
                new UssdEngine.Node("a", "main", "ACTION", 1, "A", null, null, null, null, null, "zzz", false, null, null),
                node("orphan", null, "END", null, "x"),
                node("empty", "main", "MENU", 2, "Empty")));
        assertThat(UssdEngine.validate(broken)).anyMatch(e -> e.contains("no action_code"))
                .anyMatch(e -> e.contains("missing next node zzz"))
                .anyMatch(e -> e.contains("orphan is not reachable"))
                .anyMatch(e -> e.contains("empty has no options"));
        assertThat(UssdEngine.validate(new UssdEngine.Menu("nope", List.of()))).hasSize(1);
    }
}
