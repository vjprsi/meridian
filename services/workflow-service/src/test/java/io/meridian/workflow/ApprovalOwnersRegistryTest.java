package io.meridian.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.common.error.ApiException;
import io.meridian.workflow.application.ApprovalOwners;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * SEC-03: the owner registry ({@code meridian.workflow.approval-owners}) must name the right service for every resource
 * type a service opens approval requests for or handles decisions of; otherwise that service's requests are refused
 * (unregistered type) or could be opened by another service.
 */
class ApprovalOwnersRegistryTest {
    private static final Pattern CONST = Pattern.compile("static final String (\\w+)\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern USE = Pattern.compile(
            "(?:ApprovalSpec\\.of|new ApprovalSpec|requestApproval)\\(\\s*(\"?[\\w.]+\"?)\\s*,"
                    + "|resourceType\\(\\)\\s*\\{\\s*return\\s+([\\w.\"]+);|handler\\(\"(\\w+)\"");

    @Test
    void registryCoversEveryApprovalResourceTypeWithItsService() throws IOException {
        Map<String, String> registry = registry();
        Path services = Path.of("..").toAbsolutePath().normalize();
        Map<String, Set<String>> found = new TreeMap<>();
        try (Stream<Path> dirs = Files.list(services)) {
            for (Path svc : dirs.filter(p -> p.getFileName().toString().endsWith("-service")).toList()) {
                Path src = svc.resolve("src/main/java");
                if (!Files.isDirectory(src)) {
                    continue;
                }
                Map<String, String> sources = new HashMap<>();
                try (Stream<Path> files = Files.walk(src)) {
                    for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                        sources.put(f.toString(), Files.readString(f));
                    }
                }
                Map<String, Set<String>> consts = new HashMap<>();
                sources.values().forEach(s -> {
                    Matcher m = CONST.matcher(s);
                    while (m.find()) {
                        consts.computeIfAbsent(m.group(1), k -> new HashSet<>()).add(m.group(2));
                    }
                });
                for (String s : sources.values()) {
                    if (!s.contains("ApprovalSpec") && !s.contains("ApprovalOutcomeHandler") && !s.contains("requestApproval")) {
                        continue;
                    }
                    Matcher m = USE.matcher(s);
                    while (m.find()) {
                        String tok = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : "\"" + m.group(3) + "\"";
                        Set<String> values = tok.startsWith("\"") ? Set.of(tok.replace("\"", ""))
                                : consts.getOrDefault(tok.substring(tok.lastIndexOf('.') + 1), Set.of());
                        values.forEach(v -> found.computeIfAbsent(v, k -> new HashSet<>()).add(svc.getFileName().toString()));
                    }
                }
            }
        }
        assertThat(found).as("resource types found in the sources").containsKeys("user_role", "payment", "overdraft_facility");
        found.forEach((type, owners) -> {
            assertThat(owners).as("one owning service for " + type).hasSize(1);
            assertThat(registry).as("registry entry for " + type).containsEntry(type, owners.iterator().next());
        });
    }

    @Test
    void authorizeRules() {
        ApprovalOwners owners = new ApprovalOwners(Map.of("user_role", "identity-service"), Set.of("generic_review"));
        assertThat(owners.authorize("user_role", Optional.of("identity-service"))).isEqualTo("identity-service");
        assertThat(owners.authorize("generic_review", Optional.empty())).isEqualTo("workflow-service");
        assertThatThrownBy(() -> owners.authorize("user_role", Optional.empty()))
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "service_owned_resource_type");
        assertThatThrownBy(() -> owners.authorize("user_role", Optional.of("payment-service")))
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "not_resource_owner");
        assertThatThrownBy(() -> owners.authorize("other", Optional.of("payment-service")))
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "unregistered_resource_type");
    }

    private static Map<String, String> registry() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties p = yaml.getObject();
        Map<String, String> out = new HashMap<>();
        String prefix = "meridian.workflow.approval-owners.";
        p.stringPropertyNames().stream().filter(k -> k.startsWith(prefix))
                .forEach(k -> out.put(k.substring(prefix.length()), p.getProperty(k)));
        return out;
    }
}
