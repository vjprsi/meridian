package io.meridian.notification.api;

import io.meridian.notification.api.generated.NotificationRulesApi;
import io.meridian.notification.api.generated.model.NotificationRule;
import io.meridian.notification.api.generated.model.NotificationRuleCreate;
import io.meridian.notification.api.generated.model.NotificationRuleUpdate;
import io.meridian.notification.application.RuleService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/** Event-to-notification rules; CRUD with validation of channels, recipient expression and conditions. */
@RestController
public class NotificationRulesController implements NotificationRulesApi {
    private final CrudEngine crud;
    private final JsonMapper json;

    public NotificationRulesController(CrudEngine crud, JsonMapper json) {
        this.crud = crud;
        this.json = json;
    }

    @Override
    public ResponseEntity<NotificationRule> notificationRulesCreate(String idempotencyKey, NotificationRuleCreate body) {
        RuleService.validate(json.valueToTree(body));
        return crud.create("notification_rule", NotificationRule.class, body, idempotencyKey, Crud.params());
    }

    @Override
    public ResponseEntity<NotificationRule> notificationRulesUpdate(UUID id, String ifMatch, NotificationRuleUpdate body) {
        RuleService.validate(json.valueToTree(body));
        return crud.update("notification_rule", NotificationRule.class, body, ifMatch, Crud.params().with("id", id));
    }
}
