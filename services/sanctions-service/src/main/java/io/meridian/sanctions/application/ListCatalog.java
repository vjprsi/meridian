package io.meridian.sanctions.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.sanctions.domain.EntryIndex;
import io.meridian.sanctions.domain.NameMatcher;
import io.meridian.sanctions.domain.NameNormalizer;
import io.meridian.sanctions.infrastructure.Db;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Lists visible to a tenant (platform-managed global lists plus the tenant's own) and the in-memory match index of
 * each list. Global lists are indexed once for all tenants. An index is rebuilt when the list's row_version changes
 * (every load, import and entry change bumps it); visibility snapshots expire after a short TTL.
 */
@Component
public class ListCatalog {
    public record ListInfo(UUID id, String code, String source, int version, UUID owner) {}

    private record Visible(long loadedAt, List<ListInfo> lists) {}

    private record Indexed(int version, EntryIndex index) {}

    private final Db db;
    private final long ttlNanos;
    private final Map<UUID, Visible> visible = new ConcurrentHashMap<>();
    private final Map<UUID, Indexed> indexes = new ConcurrentHashMap<>();

    public ListCatalog(Db db, @Value("${meridian.sanctions.list-cache-ttl-ms:5000}") long ttlMs) {
        this.db = db;
        this.ttlNanos = ttlMs * 1_000_000L;
    }

    /** Active lists the current tenant screens against (optionally restricted to the given codes). */
    public List<ListInfo> activeLists(Set<String> codes) {
        UUID tenant = TenantContext.require();
        Visible v = visible.get(tenant);
        if (v == null || System.nanoTime() - v.loadedAt() > ttlNanos) {
            List<ListInfo> lists = db.list("""
                    select json_build_object('id', id, 'code', code, 'source', source, 'row_version', row_version,
                           'owner', owner_tenant_id)::text
                    from sanctions_list where is_active and (owner_tenant_id is null or owner_tenant_id = ?)
                    order by code""", tenant).stream()
                    .map(n -> new ListInfo(UUID.fromString(n.get("id").asString()), n.get("code").asString(),
                            n.get("source").asString(), n.get("row_version").asInt(), Db.uuid(n, "owner")))
                    .toList();
            v = new Visible(System.nanoTime(), lists);
            visible.put(tenant, v);
        }
        if (codes == null || codes.isEmpty()) {
            return v.lists();
        }
        return v.lists().stream().filter(l -> codes.contains(l.code())).toList();
    }

    public EntryIndex index(ListInfo list) {
        Indexed i = indexes.get(list.id());
        if (i == null || i.version() != list.version()) {
            List<NameMatcher.Entry> entries = new ArrayList<>();
            for (JsonNode e : db.list("""
                    select row_to_json(e)::text from sanctions_list_entry e
                    where sanctions_list_id = ? and (delisted_on is null or delisted_on > current_date)""", list.id())) {
                entries.add(entry(e, list));
            }
            i = new Indexed(list.version(), new EntryIndex(entries));
            indexes.put(list.id(), i);
        }
        return i.index();
    }

    /** Forgets the tenant's visibility snapshot (after a list change in this instance). */
    public void invalidate() {
        TenantContext.current().ifPresent(visible::remove);
    }

    /** Loads a list the tenant may see, optionally requiring that the tenant owns it (to change it). */
    public JsonNode requireList(UUID id, boolean mustOwn) {
        UUID tenant = TenantContext.require();
        JsonNode l = db.one("select row_to_json(l)::text from sanctions_list l where id = ? and (owner_tenant_id is null or owner_tenant_id = ?)",
                id, tenant).orElseThrow(() -> ApiException.notFound("sanctions list", id));
        if (mustOwn && !tenant.toString().equals(Db.str(l, "owner_tenant_id"))) {
            throw new ApiException(403, "global_list_read_only", "Platform-managed lists cannot be changed by a tenant");
        }
        return l;
    }

    /** Loads an entry of a list the tenant may see. */
    public JsonNode requireEntry(UUID id) {
        JsonNode e = db.one("""
                select row_to_json(e)::text from sanctions_list_entry e join sanctions_list l on l.id = e.sanctions_list_id
                where e.id = ? and (l.owner_tenant_id is null or l.owner_tenant_id = ?)""", id, TenantContext.require())
                .orElseThrow(() -> ApiException.notFound("sanctions list entry", id));
        return e;
    }

    static NameMatcher.Entry entry(JsonNode e, ListInfo list) {
        List<String> names = new ArrayList<>();
        names.add(e.get("primary_name").asString());
        for (JsonNode a : e.path("aliases")) {
            String n = a.isObject() ? a.path("name").asString(null) : a.asString(null);
            if (n != null && !n.isBlank()) {
                names.add(n);
            }
        }
        List<List<String>> tokens = names.stream().map(NameNormalizer::tokens).toList();
        Set<String> countries = new LinkedHashSet<>();
        if (e.hasNonNull("nationality")) {
            countries.add(e.get("nationality").asString().trim());
        }
        e.path("countries").forEach(c -> countries.add(c.asString().trim().toUpperCase(java.util.Locale.ROOT)));
        Set<String> ids = new LinkedHashSet<>();
        e.path("identifiers").forEach(i -> {
            String v = i.isObject() ? i.path("value").asString(null) : i.asString(null);
            if (v != null && !NameNormalizer.identifier(v).isEmpty()) {
                ids.add(NameNormalizer.identifier(v));
            }
        });
        return new NameMatcher.Entry(java.util.UUID.fromString(e.get("id").asString()), list.id(), list.code(),
                list.source(), e.get("entry_type").asString(), e.get("primary_name").asString(), List.copyOf(names),
                tokens, Db.str(e, "date_of_birth"), Set.copyOf(countries), Set.copyOf(ids), Db.str(e, "pep_level"));
    }
}
