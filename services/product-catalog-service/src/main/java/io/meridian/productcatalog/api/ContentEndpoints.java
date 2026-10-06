package io.meridian.productcatalog.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.productcatalog.application.VersionContentService;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Shared create/update/delete handling for version content resources (guarded by the version's status). */
@Component
class ContentEndpoints {
    private final VersionContentService content;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final JsonMapper json;

    ContentEndpoints(VersionContentService content, CrudEngine crud, Idempotency idempotency, JsonMapper json) {
        this.content = content;
        this.crud = crud;
        this.idempotency = idempotency;
        this.json = json;
    }

    <T> ResponseEntity<T> create(String table, Class<T> type, String key, Object body) {
        TenantContext.require();
        return idempotency.execute(key, body, type, () -> {
            String row = content.create(table, body);
            return ApiSupport.withEtag(HttpStatus.CREATED, crud.read(row, type), json.readTree(row));
        });
    }

    <T> ResponseEntity<T> update(String table, Class<T> type, UUID id, String ifMatch, Object body) {
        TenantContext.require();
        String row = content.update(table, id, body, ApiSupport.ifMatch(ifMatch));
        return ApiSupport.withEtag(HttpStatus.OK, crud.read(row, type), json.readTree(row));
    }

    ResponseEntity<Void> delete(String table, UUID id) {
        TenantContext.require();
        content.delete(table, id);
        return ResponseEntity.noContent().build();
    }
}
