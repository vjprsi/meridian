package io.meridian.web.callback;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Remembers callback nonces so each is accepted once (SEC-17). */
public interface NonceStore {

    /** True the first time {@code nonce} is seen for {@code provider}; false for a replay. */
    boolean firstUse(String provider, String nonce, Instant at, Duration retention);

    /**
     * Nonces in the service's own {@code callback_nonce} table (local migration
     * {@code V001_50__callback_nonce.sql}, platform-global, no tenant). Old rows are purged now and then.
     */
    final class Jdbc implements NonceStore {
        /** DDL every service using v2 callbacks ships as a local Flyway migration. */
        public static final String DDL = """
                CREATE TABLE IF NOT EXISTS callback_nonce (
                  provider     text        NOT NULL,
                  nonce        text        NOT NULL,
                  received_at  timestamptz NOT NULL DEFAULT now(),
                  PRIMARY KEY (provider, nonce)
                );
                CREATE INDEX IF NOT EXISTS ix_callback_nonce_received ON callback_nonce (received_at);""";

        private final JdbcClient jdbc;
        private final AtomicLong calls = new AtomicLong();

        public Jdbc(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public boolean firstUse(String provider, String nonce, Instant at, Duration retention) {
            if (calls.incrementAndGet() % 200 == 0) {
                jdbc.sql("delete from callback_nonce where received_at < ?")
                        .param(java.sql.Timestamp.from(at.minus(retention))).update();
            }
            return jdbc.sql("insert into callback_nonce (provider, nonce, received_at) values (?, ?, ?) "
                            + "on conflict do nothing")
                    .params(provider, nonce, java.sql.Timestamp.from(at)).update() == 1;
        }
    }
}
