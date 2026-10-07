package io.meridian.merchantchannel.application;

import static io.meridian.merchantchannel.application.CurrentUser.merchant;
import static io.meridian.merchantchannel.application.CurrentUser.userId;

import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** The app security log: OWNER users see the whole merchant's activity, other users their own. */
@Service
public class ActivityService {
    private final Rows rows;

    public ActivityService(Rows rows) {
        this.rows = rows;
    }

    public JsonNode list(Integer limit, String cursor) {
        int n = Rows.limit(limit);
        UUID after = Rows.cursor(cursor);
        UUID only = CurrentUser.owner() ? null : userId();
        return rows.page(rows.list("select row_to_json(t)::text from merchant_action t where t.merchant_id = ? "
                        + "and (?::uuid is null or t.merchant_user_id = ?::uuid) and (?::uuid is null or t.id < ?::uuid) "
                        + "order by t.id desc limit ?", merchant(), only, only, after, after, n + 1), n);
    }
}
