package io.meridian.web.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class StructuredLoggingDefaultsTest {
    final StructuredLoggingDefaults defaults = new StructuredLoggingDefaults();

    @Test
    void deployedServicesLogEcsJsonWithServiceAndCell() {
        MockEnvironment env = new MockEnvironment().withProperty("spring.application.name", "ledger-service")
                .withProperty("meridian.cell.id", "aws-euw1-c01");
        defaults.postProcessEnvironment(env, null);
        assertThat(env.getProperty("logging.structured.format.console")).isEqualTo("ecs");
        assertThat(env.getProperty("logging.structured.ecs.service.name")).isEqualTo("ledger-service");
        assertThat(env.getProperty("logging.structured.ecs.service.node-name")).isEqualTo("aws-euw1-c01");
    }

    @Test
    void devModeAndExplicitChoicesStayAsTheyAre() {
        MockEnvironment dev = new MockEnvironment().withProperty("meridian.security-mode", "dev");
        defaults.postProcessEnvironment(dev, null);
        assertThat(dev.getProperty("logging.structured.format.console")).isNull();

        MockEnvironment devJson = new MockEnvironment().withProperty("meridian.security-mode", "dev")
                .withProperty("meridian.logging.format", "json");
        defaults.postProcessEnvironment(devJson, null);
        assertThat(devJson.getProperty("logging.structured.format.console")).isEqualTo("ecs");

        MockEnvironment text = new MockEnvironment().withProperty("meridian.logging.format", "text");
        defaults.postProcessEnvironment(text, null);
        assertThat(text.getProperty("logging.structured.format.console")).isNull();

        MockEnvironment logstash = new MockEnvironment().withProperty("logging.structured.format.console", "logstash");
        defaults.postProcessEnvironment(logstash, null);
        assertThat(logstash.getProperty("logging.structured.format.console")).isEqualTo("logstash");
    }
}
