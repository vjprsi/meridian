package io.meridian.merchantchannel.application;

import static io.meridian.merchantchannel.application.CurrentUser.merchant;

import io.meridian.merchantchannel.application.Downstream.Query;
import io.meridian.merchantchannel.config.MerchantChannelProperties;
import io.meridian.merchantchannel.domain.RefundCap;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The merchant's wallet transactions (payments received, refunds, fees, settlements) as merchant-service reports them,
 * filtered to the stores of the user's scope: each transaction is attributed to a store (its sale, the QR it paid, or
 * the refunded payment); transactions without a store are shown to all-stores users only. Pages are read whole from
 * merchant-service and filtered here, so a page may hold fewer rows than asked; the cursor is merchant-service's.
 */
@Service
public class TransactionService {
    static final String MERCHANTS = Access.MERCHANTS;

    private final Access access;
    private final Downstream down;
    private final RefundService refunds;
    private final MerchantChannelProperties props;

    public TransactionService(Access access, Downstream down, RefundService refunds, MerchantChannelProperties props) {
        this.access = access;
        this.down = down;
        this.refunds = refunds;
        this.props = props;
    }

    public JsonNode list(OffsetDateTime from, OffsetDateTime to, UUID storeId, String txnType, String status,
                         Integer limit, String cursor) {
        int n = Rows.limit(limit);
        if (storeId != null) {
            access.store(storeId, "merchantAppTransactionsList");
        }
        Set<UUID> scope = access.scopeStoreIds();
        boolean filtered = storeId != null || scope != null || txnType != null || status != null;
        Map<String, Optional<UUID>> cache = new HashMap<>();
        ObjectNode out = down.json().createObjectNode();
        ArrayNode data = out.putArray("data");
        String next = cursor == null || cursor.isBlank() ? null : cursor;
        boolean more;
        int pages = 0;
        do {
            Query q = Query.of("from", from == null ? null : from.toString()).with("to", to == null ? null : to.toString())
                    .paging(filtered ? Downstream.MAX_PAGE : n, next);
            JsonNode page = down.get(MERCHANTS, "/v1/merchants/" + merchant() + "/transactions", q);
            for (JsonNode t : page.path("data")) {
                if (!Access.ofMerchant(t)) {
                    continue;
                }
                if (txnType != null && !txnType.equals(t.path("txn_type").asString(null))) {
                    continue;
                }
                if (status != null && !status.equals(t.path("status").asString(null))) {
                    continue;
                }
                if (storeId != null || scope != null) {
                    UUID store = access.storeOf(t, cache);
                    if (storeId != null && !storeId.equals(store)) {
                        continue;
                    }
                    if (scope != null && (store == null || !scope.contains(store))) {
                        continue;
                    }
                }
                data.add(t);
            }
            more = page.path("has_more").asBoolean(false) && page.hasNonNull("next_cursor");
            next = more ? page.get("next_cursor").asString() : null;
            pages++;
        } while (more && data.size() < n && pages < props.maxTransactionPages());
        if (more) {
            out.put("next_cursor", next);
        } else {
            out.putNull("next_cursor");
        }
        out.put("has_more", more);
        return out;
    }

    public JsonNode get(UUID id) {
        return access.transaction(id, "merchantAppTransactionsGet");
    }

    /** {transaction_id, currency, amount, refunded, pending, refundable} of one payment of the user's stores. */
    public JsonNode refundable(UUID id) {
        JsonNode t = access.transaction(id, "merchantAppTransactionsRefundable");
        ObjectNode out = down.json().createObjectNode();
        out.put("transaction_id", id.toString());
        out.put("currency", t.path("currency").asString(null));
        boolean payment = "MERCHANT_PAYMENT".equals(t.path("txn_type").asString(""))
                && "COMPLETED".equals(t.path("status").asString(""));
        RefundCap cap = refunds.capacity(t, null);
        out.put("amount", cap.amount().toPlainString());
        out.put("refunded", cap.refunded().toPlainString());
        out.put("pending", cap.pending().toPlainString());
        out.put("refundable", payment ? cap.refundable().toPlainString() : "0.00");
        out.put("refundable_payment", payment);
        return out;
    }
}
