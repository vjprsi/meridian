package io.meridian.channel.infrastructure;

import io.meridian.channel.config.ChannelProperties;
import io.meridian.channel.domain.UssdEngine;
import io.meridian.common.error.ApiException;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Runs channel action codes against wallet-service and biller-service through {@link ServiceClient} (tenant and
 * principal propagated). Context keys: {@code _wallet_id}, {@code _currency}, {@code _msisdn} (resolved subscriber),
 * {@code payee_msisdn}/{@code msisdn}, {@code amount}, {@code target_msisdn}, {@code biller_id}. Business failures
 * become short customer messages; internals are never shown on the handset.
 */
@Component
public class WalletActions {
    private static final Logger log = LoggerFactory.getLogger(WalletActions.class);
    static final String WALLET = "wallet-service";

    private final ServiceClient client;
    private final ChannelProperties props;
    private final JsonMapper json;

    public WalletActions(ServiceClient client, ChannelProperties props, JsonMapper json) {
        this.client = client;
        this.props = props;
        this.json = json;
    }

    /** Resolves the subscriber's wallet, or null when the number is not registered. */
    public JsonNode lookup(String msisdn) {
        try {
            return client.get(WALLET, "/v1/wallets/lookup?msisdn=" + java.net.URLEncoder.encode(msisdn,
                    java.nio.charset.StandardCharsets.UTF_8));
        } catch (ApiException e) {
            if (e.status() == 404) {
                return null;
            }
            throw e;
        }
    }

    /** An executor whose money movements use idempotency keys derived from {@code keyPrefix}. */
    public UssdEngine.ActionExecutor executor(String keyPrefix) {
        return (code, ctx, pin) -> run(code, ctx, pin, keyPrefix);
    }

    /** A dry-run executor for the simulator: nothing is called. */
    public static UssdEngine.ActionExecutor dryRun() {
        return (code, ctx, pin) -> new UssdEngine.Result(true, "[simulated " + code + "]", null);
    }

    public UssdEngine.Result run(String code, Map<String, String> ctx, String pin, String key) {
        String wallet = ctx.get("_wallet_id");
        String currency = ctx.get("_currency");
        try {
            return switch (code == null ? "" : code) {
                case "BALANCE" -> {
                    JsonNode b = client.get(WALLET, "/v1/wallets/" + wallet + "/balance");
                    yield ok("Your balance is " + money(b.path("available_balance").asString("0")) + " " + currency + ".", null);
                }
                case "P2P_SEND" -> {
                    String payee = ctx.getOrDefault("payee_msisdn", ctx.get("msisdn"));
                    String amount = ctx.get("amount");
                    if (payee == null || amount == null) {
                        yield fail("Missing recipient or amount.");
                    }
                    ObjectNode body = json.createObjectNode();
                    body.put("payer_wallet_id", wallet);
                    body.put("payee_msisdn", props.e164(payee));
                    body.put("amount", amount);
                    body.put("currency", currency);
                    body.put("channel", ctx.getOrDefault("_channel", "USSD"));
                    if (pin != null) {
                        body.put("pin", pin);
                    }
                    JsonNode t = client.post(WALLET, "/v1/wallet-transactions/p2p", body, key + "-p2p");
                    yield ok("Sent " + money(amount) + " " + currency + " to " + t.path("payee_name").asString(payee)
                            + ". Fee " + money(t.path("fee_amount").asString("0")) + ". Ref " + t.path("reference").asString("")
                            + ".", txn(t));
                }
                case "MINI_STATEMENT" -> {
                    JsonNode page = client.get(WALLET, "/v1/wallets/" + wallet + "/transactions?limit=5");
                    StringBuilder sb = new StringBuilder("Last transactions:");
                    int n = 0;
                    for (JsonNode t : page.path("data")) {
                        boolean out = wallet.equals(t.path("payer_wallet_id").asString(""));
                        sb.append('\n').append(t.path("txn_type").asString()).append(out ? " -" : " +")
                                .append(money(t.path("amount").asString("0")));
                        n++;
                    }
                    yield ok(n == 0 ? "No transactions yet." : sb.toString(), null);
                }
                case "CHANGE_PIN" -> {
                    // the PIN prompt carries "<current>*<new>" so that no PIN is ever kept between requests
                    String[] p = pin == null ? new String[0] : pin.split("\\*");
                    if (p.length != 2) {
                        yield fail("Enter current PIN*new PIN.");
                    }
                    ObjectNode body = json.createObjectNode();
                    body.put("current_pin", p[0]);
                    body.put("new_pin", p[1]);
                    client.post(WALLET, "/v1/wallets/" + wallet + "/set-pin", body, key + "-pin");
                    yield ok("Your PIN was changed.", null);
                }
                case "AIRTIME" -> {
                    String biller = ctx.getOrDefault("biller_id",
                            props.airtimeBillerId() == null ? null : props.airtimeBillerId().toString());
                    String amount = ctx.get("amount");
                    if (biller == null || amount == null) {
                        yield fail("Airtime is not available right now.");
                    }
                    ObjectNode body = json.createObjectNode();
                    body.put("biller_id", biller);
                    body.put("payer_wallet_id", wallet);
                    body.put("target_msisdn", props.e164(ctx.getOrDefault("target_msisdn", ctx.get("_msisdn"))));
                    body.put("amount", amount);
                    body.put("currency", currency);
                    body.put("channel", "USSD");
                    if (pin != null) {
                        body.put("pin", pin);
                    }
                    JsonNode t = client.post("biller-service", "/v1/airtime-topups", body, key + "-airtime");
                    String status = t.path("status").asString("");
                    yield "FAILED".equals(status) ? fail("Airtime purchase failed. You were not charged.")
                            : ok("Airtime of " + money(amount) + " " + currency + " sent to " + body.get("target_msisdn").asString()
                                    + ".", txn(t, "wallet_transaction_id"));
                }
                case "CASH_OUT_CONFIRM" -> {
                    JsonNode page = client.get(WALLET, "/v1/wallets/" + wallet
                            + "/transactions?status=PENDING_CONFIRMATION&txn_type=CASH_OUT&limit=1");
                    JsonNode pending = page.path("data").path(0);
                    if (pending.isMissingNode() || pending.isNull()) {
                        yield fail("No cash-out is waiting for your confirmation.");
                    }
                    ObjectNode body = json.createObjectNode();
                    if (pin != null) {
                        body.put("pin", pin);
                    }
                    JsonNode t = client.post(WALLET, "/v1/wallet-transactions/" + pending.path("id").asString() + "/confirm",
                            body, key + "-cashout");
                    yield ok("Cash-out of " + money(t.path("amount").asString("0")) + " " + currency
                            + " confirmed. Collect your cash. Ref " + t.path("reference").asString("") + ".", txn(t));
                }
                default -> fail("This service is not available.");
            };
        } catch (ApiException e) {
            if (e.status() >= 500) {
                log.warn("action {} failed upstream: {}", code, e.getMessage());
                return fail("Service temporarily unavailable. Please try again later.");
            }
            return fail(friendly(e.code()));
        }
    }

    static String friendly(String code) {
        return switch (code == null ? "" : code) {
            case "invalid_pin" -> "Wrong PIN.";
            case "pin_locked" -> "Your PIN is locked. Dial again later or reset it at an agent.";
            case "pin_not_set" -> "Set a PIN first.";
            case "weak_pin", "invalid_pin_format" -> "That PIN is not allowed. Choose 4-6 digits that are hard to guess.";
            case "insufficient_funds" -> "Insufficient funds.";
            case "limit_exceeded", "amount_below_minimum", "max_balance_exceeded" -> "The amount is outside your limits.";
            case "payee_not_found" -> "The recipient is not registered.";
            case "same_wallet" -> "You cannot send money to yourself.";
            case "wallet_not_active", "payee_not_active" -> "The account is not active.";
            case "fraud_blocked" -> "The transaction was declined.";
            default -> "Transaction failed. Please try again later.";
        };
    }

    private static UssdEngine.Result ok(String message, UUID txn) {
        return new UssdEngine.Result(true, message, txn);
    }

    private static UssdEngine.Result fail(String message) {
        return new UssdEngine.Result(false, message, null);
    }

    private static UUID txn(JsonNode t) {
        return txn(t, "id");
    }

    private static UUID txn(JsonNode t, String field) {
        String id = t.path(field).asString(null);
        return id == null ? null : UUID.fromString(id);
    }

    static String money(String amount) {
        try {
            return new BigDecimal(amount).setScale(2, RoundingMode.HALF_EVEN).toPlainString();
        } catch (NumberFormatException e) {
            return amount;
        }
    }
}
