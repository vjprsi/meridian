package io.meridian.cardvault.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.cardvault.application.MasterKeyProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

/** SEC-01: the master key never comes from committed configuration outside dev mode. */
class MasterKeyProvidersTest {
    /** The value that used to be committed in application.yml. */
    static final String FORMER_COMMITTED = "bWVyaWRpYW4tZGV2LXZhdWx0LW1hc3Rlci1rZXktMDE=";

    static String randomKey() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return Base64.getEncoder().encodeToString(k);
    }

    @Test
    void jwtModeWithoutAKeyRefusesToStart() {
        MockEnvironment env = new MockEnvironment().withProperty("meridian.security-mode", "jwt");
        assertThatThrownBy(() -> MasterKeyProviders.fromEnvironment(env))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("No card-vault master key");
        // no security mode at all means jwt
        assertThatThrownBy(() -> MasterKeyProviders.fromEnvironment(new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void jwtModeRejectsTheDevelopmentKeyAndWrongLengths() {
        MockEnvironment env = new MockEnvironment().withProperty("meridian.security-mode", "jwt")
                .withProperty(MasterKeyProviders.KEY_PROPERTY, FORMER_COMMITTED);
        assertThatThrownBy(() -> MasterKeyProviders.fromEnvironment(env))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("development card-vault master key");
        env.setProperty(MasterKeyProviders.KEY_PROPERTY, Base64.getEncoder().encodeToString(new byte[16]));
        assertThatThrownBy(() -> MasterKeyProviders.fromEnvironment(env)).hasMessageContaining("32 bytes");
    }

    @Test
    void jwtModeAcceptsAnInjectedKeyFromTheEnvironmentOrAFile(@TempDir Path dir) throws Exception {
        String key = randomKey();
        MockEnvironment env = new MockEnvironment().withProperty("meridian.security-mode", "jwt")
                .withProperty(MasterKeyProviders.KEY_PROPERTY, key);
        MasterKeyProvider p = MasterKeyProviders.fromEnvironment(env);
        assertThat(p.masterKey()).isEqualTo(Base64.getDecoder().decode(key));
        assertThat(p.source()).doesNotContain(key);

        Path file = dir.resolve("master.key");
        String fileKey = randomKey();
        Files.writeString(file, fileKey + "\n");
        MockEnvironment fromFile = new MockEnvironment().withProperty("meridian.security-mode", "jwt")
                .withProperty(MasterKeyProviders.FILE_PROPERTY, file.toString());
        assertThat(MasterKeyProviders.fromEnvironment(fromFile).masterKey()).isEqualTo(Base64.getDecoder().decode(fileKey));
    }

    @Test
    void devModeFallsBackToTheLabelledLocalKey() {
        MasterKeyProvider p = MasterKeyProviders.fromEnvironment(new MockEnvironment().withProperty("meridian.security-mode", "dev"));
        assertThat(p.source()).contains("LOCAL DEVELOPMENT KEY");
        assertThat(new String(p.masterKey(), StandardCharsets.US_ASCII)).isEqualTo("meridian-dev-vault-master-key-01");
    }

    @Test
    void noKeyMaterialIsCommittedInApplicationYml() throws Exception {
        String yml = new ClassPathResource("application.yml").getContentAsString(StandardCharsets.UTF_8);
        assertThat(yml).doesNotContain(FORMER_COMMITTED).contains("master-key: ${MERIDIAN_CARDS_VAULT_MASTER_KEY:}");
    }
}
