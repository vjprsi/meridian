package io.meridian.channel.api;

import io.meridian.channel.api.generated.UssdMenuNodesApi;
import io.meridian.channel.api.generated.model.UssdMenuNode;
import io.meridian.channel.api.generated.model.UssdMenuNodeCreate;
import io.meridian.channel.api.generated.model.UssdMenuNodeUpdate;
import io.meridian.channel.application.MenuService;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Menu nodes; editable only while their menu is a DRAFT (published versions are immutable). */
@RestController
public class UssdMenuNodesController implements UssdMenuNodesApi {
    private final MenuService menus;
    private final Actions actions;
    private final CrudEngine crud;

    public UssdMenuNodesController(MenuService menus, Actions actions, CrudEngine crud) {
        this.menus = menus;
        this.actions = actions;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<UssdMenuNode> ussdMenuNodesCreate(String idempotencyKey, UssdMenuNodeCreate body) {
        return actions.create(idempotencyKey, body, UssdMenuNode.class, () -> menus.createNode(actions.tree(body)));
    }

    @Override
    public ResponseEntity<UssdMenuNode> ussdMenuNodesUpdate(UUID id, String ifMatch, UssdMenuNodeUpdate body) {
        TenantContext.require();
        menus.validateNodeUpdate(id, actions.tree(body));
        return crud.update("ussd_menu_node", UssdMenuNode.class, body, ifMatch, Crud.params().with("id", id));
    }

    @Override
    public ResponseEntity<Void> ussdMenuNodesDelete(UUID id) {
        TenantContext.require();
        menus.requireNodeDeletable(id);
        return crud.delete("ussd_menu_node", Crud.params().with("id", id));
    }
}
