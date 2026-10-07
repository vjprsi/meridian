package io.meridian.mover;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.meridian.common.time.Clocks;
import io.meridian.test.TestRows;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * MR-GAME-DAY per-tenant data mover, end to end against real PostgreSQL 18: two cells (two databases with the
 * generated ledger-service and document-service schemas, row-level security on, services answering through the real
 * data-lifecycle move API of libs/meridian-web), in-memory S3 buckets, a fake tenant-service confirm-copy endpoint.
 *
 * <p>Covers: only the moving tenant's rows and documents are copied; foreign-key order incl. a self-reference to a
 * later id (deferred and retried); append-only tables; exact decimals; document storage URIs rewritten to the target
 * bucket; resume after a transient failure without duplicates; freeze-grace answer of confirm-copy; checkpoint with
 * counts and digest; redelivered request is a no-op; a target that already holds the tenant's rows is refused; a
 * target changed behind the mover's back fails verification.
 */
class TenantMoverIT {
    static final PostgreSQLContainer PG = new PostgreSQLContainer(DockerImageName.parse("postgres:18"))
            .withDatabaseName("meridian").withUsername("meridian").withPassword("meridian");
    static final JsonMapper JSON = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    static final Path ROOT = Path.of("../..");

    static FakeCell cellA;
    static FakeCell cellB;
    static HttpServer registry;
    static final List<JsonNode> confirms = new CopyOnWriteArrayList<>();
    static final AtomicInteger graceAnswers = new AtomicInteger(1);
    static ProgressStore store;
    static JdbcClient moverDb;

    static String url(String db) {
        return PG.getJdbcUrl().replace("/meridian?", "/" + db + "?");
    }

    @BeforeAll
    static void cells() throws Exception {
        PG.start();
        JdbcClient admin = JdbcClient.create(new DriverManagerDataSource(PG.getJdbcUrl(), "meridian", "meridian"));
        admin.sql("CREATE ROLE meridian_app LOGIN PASSWORD 'meridian_app' NOSUPERUSER NOBYPASSRLS").update();
        for (String db : List.of("cell_a", "cell_b", "mover_db")) {
            admin.sql("CREATE DATABASE " + db).update();
        }
        for (String db : List.of("cell_a", "cell_b")) {
            JdbcClient c = JdbcClient.create(new DriverManagerDataSource(url(db), "meridian", "meridian"));
            for (String d : List.of("ledger_db", "document_db")) {
                c.sql(Files.readString(ROOT.resolve("generated/db/" + d + "/V001__init.sql"), StandardCharsets.UTF_8)).update();
            }
        }
        Map<String, String> schemas = Map.of("ledger-service", "ledger", "document-service", "document");
        cellA = new FakeCell(url("cell_a"), schemas, "bucket-a");
        cellB = new FakeCell(url("cell_b"), schemas, "bucket-b");

        registry = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        registry.createContext("/v1/platform/tenant-moves/", ex -> {
            JsonNode body = JSON.readTree(ex.getRequestBody().readAllBytes());
            if (graceAnswers.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                FakeCell.send(ex, 409, "{\"code\":\"freeze_grace_pending\",\"detail\":\"wait\"}");
                return;
            }
            ObjectNode rec = (ObjectNode) body.deepCopy();
            rec.put("path", ex.getRequestURI().getPath());
            rec.put("idempotency_key", ex.getRequestHeaders().getFirst("Idempotency-Key"));
            confirms.add(rec);
            FakeCell.send(ex, 200, "{\"status\":\"COPIED\"}");
        });
        registry.start();

        Flyway.configure().dataSource(url("mover_db"), "meridian", "meridian").locations("classpath:db/migration").load().migrate();
        moverDb = JdbcClient.create(new DriverManagerDataSource(url("mover_db"), "meridian", "meridian"));
        store = new ProgressStore(moverDb, JSON);
    }

    @AfterAll
    static void stop() {
        cellA.close();
        cellB.close();
        registry.stop(0);
        PG.stop();
    }

    static TenantMover mover(int pageSize) {
        MoverProperties props = new MoverProperties("http://127.0.0.1:" + registry.getAddress().getPort(),
                UUID.randomUUID().toString(), null,
                Map.of("cell-a", new MoverProperties.Cell(cellA.servicesUrlTemplate(), cellA.s3()),
                        "cell-b", new MoverProperties.Cell(cellB.servicesUrlTemplate(), cellB.s3())),
                List.of(), List.of("ledger-service", "document-service"), pageSize, 0, 0, null, null, null, true, null);
        MoverHttp http = new MoverHttp(JSON, props.auth());
        return new TenantMover(store, new Cells(props, JSON), http, props, "test-" + UUID.randomUUID());
    }

    static ObjectNode handoff(UUID moveId, UUID tenant) {
        ObjectNode h = JSON.createObjectNode();
        h.put("id", moveId.toString());
        h.put("subject_tenant_id", tenant.toString());
        h.putObject("from_cell").put("code", "cell-a");
        h.putObject("to_cell").put("code", "cell-b");
        h.put("copy_strategy", "LOGICAL_REPLICATION");
        h.put("not_before_checkpoint", Clocks.now().minusSeconds(1).toString());
        return h;
    }

    static TestRows rows(String db, String schema, UUID tenant) {
        return new TestRows(url(db) + "&currentSchema=" + schema, "meridian", "meridian", tenant);
    }

    static JdbcClient owner(String db, String schema) {
        return JdbcClient.create(new DriverManagerDataSource(url(db) + "&currentSchema=" + schema, "meridian", "meridian"));
    }

    /** The tenant's rows of a table as canonical text, for an exact cross-cell comparison. */
    static List<String> dump(String db, String schema, String table, UUID tenant) {
        return owner(db, schema).sql("select row_to_json(t)::text from " + table + " t where tenant_id = ? order by id")
                .param(tenant).query(String.class).list();
    }

    static UUID seed(UUID tenant) {
        try (TestRows ledger = rows("cell_a", "ledger", tenant)) {
            UUID led = ledger.insert("ledger", Map.of());
            UUID parent = UUID.fromString("ffffffff-" + tenant.toString().substring(9));
            UUID child = UUID.fromString("00000000-" + tenant.toString().substring(9));
            ledger.insert("ledger_account", Map.of("id", parent, "ledger_id", led));
            ledger.insert("ledger_account", Map.of("id", child, "ledger_id", led, "parent_id", parent));
            for (int i = 0; i < 4; i++) {
                ledger.insert("ledger_entry", Map.of("ledger_account_id", parent, "amount", "1234567.89",
                        "account_version", i + 1, "line_no", i + 1));
            }
        }
        UUID doc;
        try (TestRows documents = rows("cell_a", "document", tenant)) {
            doc = UUID.randomUUID();
            documents.insert("document", Map.of("id", doc,
                    "storage_uri", "s3://bucket-a/" + tenant + "/" + doc + "/passport.pdf"));
        }
        cellA.objects.put(tenant + "/" + doc + "/passport.pdf", ("pdf of " + tenant).getBytes(StandardCharsets.UTF_8));
        cellA.objects.put(tenant + "/" + doc + "/thumb.png", new byte[] {1, 2, 3});
        return doc;
    }

    @Test
    void copiesOneTenantVerifiesAndConfirmsResumingAfterAFailure() {
        UUID tenant = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID doc = seed(tenant);
        seed(other);
        UUID moveId = UUID.randomUUID();
        graceAnswers.set(1);
        TenantMover mover = mover(3);

        assertThat(mover.register(handoff(moveId, tenant))).isTrue();
        cellB.failImports.set(1);                                   // target cell blips during the copy
        assertThat(mover.runIfDue(moveId)).contains("COPYING");
        assertThat(store.run(moveId).orElseThrow().lastError()).contains("503");

        assertThat(mover.runIfDue(moveId)).contains("CONFIRMING");  // confirm-copy: freeze grace still pending
        moverDb.sql("update move_run set not_before = now() where move_id = ?").param(moveId).update();
        assertThat(mover.runIfDue(moveId)).contains("COMPLETED");

        for (String table : List.of("ledger", "ledger_account", "journal_entry", "ledger_entry")) {
            List<String> a = dump("cell_a", "ledger", table, tenant);
            assertThat(a).as(table).isNotEmpty();
            assertThat(dump("cell_b", "ledger", table, tenant)).as(table).isEqualTo(a);
            assertThat(dump("cell_b", "ledger", table, other)).as(table + " of another tenant").isEmpty();
        }
        assertThat(owner("cell_b", "ledger").sql("select amount::text from ledger_entry where tenant_id = ? limit 1")
                .param(tenant).query(String.class).single()).isEqualTo("1234567.89000000");
        assertThat(owner("cell_b", "document").sql("select storage_uri from document where id = ?").param(doc)
                .query(String.class).single()).isEqualTo("s3://bucket-b/" + tenant + "/" + doc + "/passport.pdf");
        assertThat(cellB.objects.keySet()).containsExactlyInAnyOrder(tenant + "/" + doc + "/passport.pdf",
                tenant + "/" + doc + "/thumb.png");

        JsonNode confirm = confirms.getLast();
        assertThat(confirm.path("path").asString()).isEqualTo("/v1/platform/tenant-moves/" + moveId + "/confirm-copy");
        assertThat(confirm.path("verified").asBoolean()).isTrue();
        assertThat(confirm.path("idempotency_key").asString()).isEqualTo("data-mover:confirm:" + moveId);
        String checkpoint = confirm.path("checkpoint").asString();
        assertThat(checkpoint).startsWith("mover:v1 rows=").contains(" docs=2 ").containsPattern("sha256=[0-9a-f]{64}$");
        assertThat(checkpoint.length()).isLessThanOrEqualTo(255);
        assertThat(store.items(moveId)).allSatisfy(i -> {
            assertThat(i.phase()).isEqualTo("VERIFIED");
            assertThat(i.sourceDigest()).isEqualTo(i.targetDigest());
        });

        int importsBefore = cellB.imports.get();
        assertThat(mover.register(handoff(moveId, tenant))).isFalse();       // redelivered event: no new run
        assertThat(mover.runIfDue(moveId)).contains("COMPLETED");
        assertThat(cellB.imports.get()).isEqualTo(importsBefore);

        UUID again = UUID.randomUUID();                                        // a second move into the same target
        mover.register(handoff(again, tenant));
        assertThat(mover.runIfDue(again)).contains("FAILED");
        assertThat(store.run(again).orElseThrow().lastError()).contains("already holds");
    }

    @Test
    void aTargetChangedBehindTheMoversBackFailsVerification() {
        UUID tenant = UUID.randomUUID();
        seed(tenant);
        UUID moveId = UUID.randomUUID();
        graceAnswers.set(0);
        TenantMover mover = mover(1000);
        mover.register(handoff(moveId, tenant));
        assertThat(mover.runIfDue(moveId)).contains("COMPLETED");

        // the target changes after the copy (e.g. a write that slipped past the freeze): a re-verification must see it
        owner("cell_b", "document").sql("update document set file_name = 'changed.pdf' where tenant_id = ?")
                .param(tenant).update();
        moverDb.sql("update move_item set phase = 'COPIED' where move_id = ?").param(moveId).update();
        moverDb.sql("update move_run set status = 'VERIFYING', completed_at = null where move_id = ?").param(moveId).update();
        assertThat(mover.runIfDue(moveId)).contains("FAILED");
        assertThat(store.run(moveId).orElseThrow().lastError()).contains("verification failed")
                .contains("document-service.document");
    }
}
