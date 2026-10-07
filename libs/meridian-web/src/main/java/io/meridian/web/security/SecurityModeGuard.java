package io.meridian.web.security;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/**
 * SEC-13: {@code meridian.security-mode} defaults to {@code jwt}; {@code dev} (no tokens, tenant / user / roles from
 * request headers) must be chosen explicitly by the local launcher or a test. As a second line of defence a service
 * refuses to start in dev mode inside a Kubernetes pod ({@code KUBERNETES_SERVICE_HOST} set) unless
 * {@code meridian.security.allow-dev-mode-in-cluster=true} is set as well (e.g. a throw-away preview cluster).
 */
public final class SecurityModeGuard implements BeanFactoryPostProcessor, EnvironmentAware, Ordered {
    private static final Logger log = LoggerFactory.getLogger(SecurityModeGuard.class);
    public static final String ALLOW_IN_CLUSTER = "meridian.security.allow-dev-mode-in-cluster";

    private Environment environment;
    private Map<String, String> systemEnv = System.getenv();

    public SecurityModeGuard() {
    }

    SecurityModeGuard(Map<String, String> systemEnv) {
        this.systemEnv = systemEnv;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        check(environment, systemEnv);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    static void check(Environment env, Map<String, String> systemEnv) {
        if (env == null || !RequiredSecrets.devMode(env)) {
            return;
        }
        String k8s = systemEnv.get("KUBERNETES_SERVICE_HOST");
        if (k8s != null && !k8s.isBlank() && !env.getProperty(ALLOW_IN_CLUSTER, Boolean.class, false)) {
            throw new IllegalStateException("Refusing to start with meridian.security-mode=dev inside Kubernetes: dev "
                    + "mode trusts X-Tenant-Id / X-User / X-Roles headers and performs no authentication. Use jwt, or set "
                    + ALLOW_IN_CLUSTER + "=true for a disposable preview environment.");
        }
        log.warn("meridian.security-mode=dev: NO AUTHENTICATION, tenant and user are taken from request headers. "
                + "Local development and tests only.");
    }
}
