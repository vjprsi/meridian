package io.meridian.channel.api;

import io.meridian.channel.api.generated.UssdMenusApi;
import io.meridian.channel.api.generated.model.UssdMenu;
import io.meridian.channel.api.generated.model.UssdMenuCreate;
import io.meridian.channel.api.generated.model.UssdMenuNodePage;
import io.meridian.channel.api.generated.model.UssdMenuUpdate;
import io.meridian.channel.api.generated.model.UssdMenusPublishRequest;
import io.meridian.channel.api.generated.model.UssdMenusSimulateRequest;
import io.meridian.channel.application.MenuService;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** USSD menu versions: draft editing, maker-checker publication, cloning and the simulator. */
@RestController
public class UssdMenusController implements UssdMenusApi {
    private static final Set<String> PROTECTED = Set.of("short_code", "language");

    private final MenuService menus;
    private final Actions actions;
    private final CrudEngine crud;

    public UssdMenusController(MenuService menus, Actions actions, CrudEngine crud) {
        this.menus = menus;
        this.actions = actions;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<UssdMenu> ussdMenusCreate(String idempotencyKey, UssdMenuCreate body) {
        return actions.create(idempotencyKey, body, UssdMenu.class, () -> menus.create(actions.tree(body)));
    }

    @Override
    public ResponseEntity<UssdMenu> ussdMenusUpdate(UUID id, String ifMatch, UssdMenuUpdate body) {
        TenantContext.require();
        menus.validateMenuPatch(id, actions.tree(body));
        return actions.guardedUpdate(crud, "ussd_menu", UssdMenu.class, id, ifMatch, body, PROTECTED,
                "short code and language identify the menu; create a new menu instead");
    }

    @Override
    public ResponseEntity<UssdMenuNodePage> ussdMenusNodes(UUID id, Integer limit, String cursor) {
        crud.get("ussd_menu", UssdMenu.class, Crud.params().with("id", id));
        return crud.list("ussd_menu_node", UssdMenuNodePage.class,
                Crud.params().with("ussd_menu_id", id).with("limit", limit).with("cursor", cursor));
    }

    @Override
    public ResponseEntity<UssdMenu> ussdMenusPublish(UUID id, String idempotencyKey, UssdMenusPublishRequest body) {
        return actions.action(idempotencyKey, id, "publish", body, UssdMenu.class,
                () -> menus.publish(id, Http.text(actions.tree(body), "comment")));
    }

    @Override
    public ResponseEntity<UssdMenu> ussdMenusClone(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "clone", null, UssdMenu.class, () -> menus.cloneMenu(id));
    }

    @Override
    public ResponseEntity<Map<String, Object>> ussdMenusSimulate(UUID id, String idempotencyKey, UssdMenusSimulateRequest body) {
        JsonNode req = actions.tree(body);
        List<String> inputs = new ArrayList<>();
        req.path("inputs").forEach(x -> inputs.add(x.asString()));
        return actions.objectAction(idempotencyKey, "simulate:" + id, body, () -> menus.simulate(id,
                Http.text(req, "msisdn"), inputs, req.path("execute_actions").asBoolean(false)));
    }
}
