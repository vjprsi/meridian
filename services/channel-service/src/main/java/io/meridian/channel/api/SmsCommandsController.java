package io.meridian.channel.api;

import io.meridian.channel.api.generated.SmsCommandsApi;
import io.meridian.channel.api.generated.model.SmsCommand;
import io.meridian.channel.api.generated.model.SmsCommandCreate;
import io.meridian.channel.api.generated.model.SmsCommandUpdate;
import io.meridian.channel.infrastructure.Rows;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** SMS keyword commands: keywords are stored upper-case and unique per short code and language. */
@RestController
public class SmsCommandsController implements SmsCommandsApi {
    static final Set<String> ACTIONS = Set.of("BALANCE", "P2P_SEND", "MINI_STATEMENT", "HELP");

    private final CrudEngine crud;
    private final Rows rows;
    private final Actions actions;

    public SmsCommandsController(CrudEngine crud, Rows rows, Actions actions) {
        this.crud = crud;
        this.rows = rows;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<SmsCommand> smsCommandsCreate(String idempotencyKey, SmsCommandCreate body) {
        return actions.create(idempotencyKey, body, SmsCommand.class, () -> {
            ObjectNode v = rows.columns("sms_command", body);
            normalise(v, null);
            return rows.insert("sms_command", v);
        });
    }

    @Override
    public ResponseEntity<SmsCommand> smsCommandsUpdate(UUID id, String ifMatch, SmsCommandUpdate body) {
        TenantContext.require();
        ObjectNode current = rows.get("sms_command", id);
        ObjectNode merged = current.deepCopy();
        actions.tree(body).properties().forEach(e -> {
            if (!e.getValue().isNull()) {
                merged.set(e.getKey(), e.getValue());
            }
        });
        normalise(merged, id);
        ObjectNode patch = (ObjectNode) actions.tree(body);
        if (patch.hasNonNull("keyword")) {
            patch.put("keyword", merged.get("keyword").asString());
        }
        return crud.update("sms_command", SmsCommand.class, patch, ifMatch, Crud.params().with("id", id));
    }

    private void normalise(ObjectNode v, UUID self) {
        String keyword = Rows.str(v, "keyword");
        if (keyword == null || !keyword.trim().matches("[A-Za-z0-9]{1,32}")) {
            throw ApiException.unprocessable("invalid_keyword", "keyword must be one word of letters and digits");
        }
        v.put("keyword", keyword.trim().toUpperCase(Locale.ROOT));
        if (!ACTIONS.contains(Rows.str(v, "action_code"))) {
            throw ApiException.unprocessable("invalid_action_code", "action_code must be one of " + ACTIONS);
        }
        String syntax = Rows.str(v, "syntax");
        if (syntax != null && !syntax.trim().toUpperCase(Locale.ROOT).startsWith(v.get("keyword").asString())) {
            throw ApiException.unprocessable("invalid_syntax", "syntax must start with the keyword");
        }
        JsonNode dup = rows.queryOne("""
                select row_to_json(c)::text from sms_command c where upper(keyword) = ? and short_code = ? and language = ?
                  and (?::uuid is null or id <> ?::uuid)""", v.get("keyword").asString(), Rows.str(v, "short_code"),
                Rows.str(v, "language"), self, self).orElse(null);
        if (dup != null) {
            throw ApiException.conflict("keyword_exists", "The keyword is already used on this short code and language");
        }
    }
}
