package io.meridian.web.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.meridian.common.cell.CellRole;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxRelay;
import io.meridian.events.OutboxReplay;
import io.meridian.events.StandbyGate;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** MR-GAME-DAY operator switches: internal identities only; promote / demote are idempotent; registry watcher. */
class CellOpsControllerTest {
    static final JsonMapper JSON = JsonMapper.builder().build();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    private static JsonNode body(String json) {
        return JSON.readTree(json);
    }

    @Test
    void onlyListedInternalCallersMaySwitchAndPromotionIsIdempotent() {
        CellRole role = new CellRole("euw2c01", CellRole.Role.STANDBY);
        OutboxRelay relay = new OutboxRelay(null, null, null, "t", "s", 10, Duration.ofHours(72), true);
        CellOpsController ops = new CellOpsController(role, provider((StandbyGate) null), provider((OutboxReplay) null),
                provider(relay), Set.of("platform-ops", "tenant-service"), JSON);

        TenantContext.set(UUID.randomUUID(), "alice");                      // a user, no calling service
        assertThatThrownBy(() -> ops.promote(body("{\"reason\":\"x\"}")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(403));
        TenantContext.setCallingService("payment-service");                 // a service, but not an operator identity
        assertThatThrownBy(ops::status).isInstanceOf(ApiException.class);

        TenantContext.setCallingService("platform-ops");
        assertThat(ops.status().path("role").asString()).isEqualTo("STANDBY");
        assertThat(ops.status().path("relay_paused").asBoolean()).isTrue();
        assertThatThrownBy(() -> ops.promote(body("{}"))).isInstanceOf(ApiException.class);   // reason required
        assertThatThrownBy(() -> ops.replay(body("{\"from\":\"2026-10-01T00:00:00Z\",\"to\":\"2026-10-02T00:00:00Z\",\"reason\":\"r\"}")))
                .isInstanceOf(ApiException.class);                         // no replay bean

        JsonNode promoted = ops.promote(body("{\"reason\":\"game day 2026-10\"}"));
        assertThat(promoted.path("changed").asBoolean()).isTrue();
        assertThat(promoted.path("role").asString()).isEqualTo("PRIMARY");
        assertThat(promoted.path("last_change").path("actor").asString()).isEqualTo("platform-ops");
        assertThat(ops.promote(body("{\"reason\":\"again\"}")).path("changed").asBoolean()).isFalse();
        assertThat(ops.demote(body("{\"reason\":\"failback\"}")).path("role").asString()).isEqualTo("STANDBY");
    }

    @Test
    void watcherPromotesWhenTheRegistryShowsTheCellActive() {
        CellRole role = new CellRole("euw2c01", CellRole.Role.STANDBY);
        AtomicInteger calls = new AtomicInteger();
        String[] status = {"READ_ONLY"};
        UUID operator = UUID.randomUUID();
        CellPromotionWatcher w = new CellPromotionWatcher(role, path -> {
            calls.incrementAndGet();
            assertThat(path).startsWith("/v1/platform/cells");
            assertThat(TenantContext.current()).contains(operator);
            return body("{\"data\":[{\"code\":\"euw1c01\",\"status\":\"OFFLINE\"},{\"code\":\"euw2c01\",\"status\":\""
                    + status[0] + "\"}],\"has_more\":false}");
        }, operator, Duration.ofHours(1));
        assertThat(w.poll()).isFalse();
        assertThat(role.active()).isFalse();
        status[0] = "ACTIVE";
        assertThat(w.poll()).isTrue();
        assertThat(role.active()).isTrue();
        assertThat(role.lastChange().actor()).isEqualTo("cell-registry");
        int before = calls.get();
        assertThat(w.poll()).isFalse();                                     // primary: no more registry reads
        assertThat(calls.get()).isEqualTo(before);

        CellRole other = new CellRole("euw2c02", CellRole.Role.STANDBY);
        CellPromotionWatcher failing = new CellPromotionWatcher(other, path -> {
            throw new IllegalStateException("registry down");
        }, null, Duration.ofHours(1));
        assertThat(failing.poll()).isFalse();
        assertThat(other.active()).isFalse();
    }
}
