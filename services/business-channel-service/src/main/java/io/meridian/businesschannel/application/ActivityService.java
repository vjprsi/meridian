package io.meridian.businesschannel.application;

import static io.meridian.businesschannel.application.CurrentUser.business;
import static io.meridian.businesschannel.application.CurrentUser.userId;

import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** The portal security log: ADMIN users see the whole business's activity, other users their own. */
@Service
public class ActivityService {
    private final Rows rows;

    public ActivityService(Rows rows) {
        this.rows = rows;
    }

    public JsonNode list(Integer limit, String cursor) {
        int n = Rows.limit(limit);
        UUID after = Rows.cursor(cursor);
        UUID only = CurrentUser.admin() ? null : userId();
        return rows.page(rows.list("select row_to_json(t)::text from business_action t where t.business_customer_id = ? "
                        + "and (?::uuid is null or t.business_user_id = ?::uuid) and (?::uuid is null or t.id < ?::uuid) "
                        + "order by t.id desc limit ?", business(), only, only, after, after, n + 1), n);
    }
}
