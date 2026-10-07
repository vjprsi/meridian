package io.meridian.web;

import io.meridian.web.client.ServiceClient;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.crud.TableCatalog;
import io.meridian.web.error.ProblemHandler;
import io.meridian.web.json.MeridianJsonCustomizer;
import io.meridian.web.security.ScopeInterceptor;
import io.meridian.web.idempotency.Idempotency;
import io.meridian.web.tenancy.TenantAwareDataSource;
import io.meridian.web.tenancy.TenantFilter;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.json.JsonMapper;

/** Wires tenancy, security, errors, request ids and idempotency into every Meridian service. */
@AutoConfiguration
@EnableConfigurationProperties({MeridianProperties.class, io.meridian.web.security.SecurityProperties.class})
@Import(ProblemHandler.class)
public class MeridianWebAutoConfiguration {

    /** Wraps the application DataSource so every connection carries app.tenant_id (row-level security). */
    @Bean
    static BeanPostProcessor tenantAwareDataSourcePostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof DataSource ds && !(bean instanceof TenantAwareDataSource)
                        && "dataSource".equals(name)) {
                    return new TenantAwareDataSource(ds);
                }
                return bean;
            }
        };
    }

    /** Fails startup outside dev mode when a declared secret is missing or a committed dev default (SEC-01/02). */
    @Bean
    static io.meridian.web.security.RequiredSecrets meridianRequiredSecrets() {
        return new io.meridian.web.security.RequiredSecrets();
    }

    /** Dev security mode only when chosen explicitly, never inside Kubernetes by accident (SEC-13). */
    @Bean
    static io.meridian.web.security.SecurityModeGuard meridianSecurityModeGuard() {
        return new io.meridian.web.security.SecurityModeGuard();
    }

    /** Outbound policy for tenant-supplied URLs (SEC-08/09): https, public addresses only, pinned resolution. */
    @Bean
    @ConditionalOnMissingBean
    io.meridian.web.outbound.OutboundUrlPolicy outboundUrlPolicy(Environment env) {
        return io.meridian.web.outbound.OutboundUrlPolicy.fromEnvironment(env);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    io.meridian.web.outbound.OutboundHttp outboundHttp(io.meridian.web.outbound.OutboundUrlPolicy policy) {
        return new io.meridian.web.outbound.OutboundHttp(policy);
    }

    /**
     * Keeps the raw body of spec-[public] requests (provider callbacks, up to 1 MiB) readable after JSON binding, so
     * signatures can be checked over exactly what the provider sent ({@code CallbackVerifier.cachedBody()}).
     */
    @Bean
    FilterRegistrationBean<org.springframework.web.filter.OncePerRequestFilter> publicRawBodyFilter(JsonMapper json,
                                                                                                Environment env) {
        var publicRoutes = ScopeInterceptor.routes(publicRoutes(json, env.getProperty("spring.application.name", "")));
        var filter = new org.springframework.web.filter.OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest req,
                                            jakarta.servlet.http.HttpServletResponse res,
                                            jakarta.servlet.FilterChain chain)
                    throws jakarta.servlet.ServletException, java.io.IOException {
                chain.doFilter(publicRoutes.test(req)
                        ? new org.springframework.web.util.ContentCachingRequestWrapper(req, 1 << 20) : req, res);
            }
        };
        FilterRegistrationBean<org.springframework.web.filter.OncePerRequestFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        return reg;
    }

    /** SEC-26: request body cap ({@code meridian.http.max-body-size}, default 16MB). */
    @Bean
    FilterRegistrationBean<BodySizeFilter> bodySizeFilter(Environment env) {
        long max = env.getProperty("meridian.http.max-body-size", org.springframework.util.unit.DataSize.class,
                org.springframework.util.unit.DataSize.ofMegabytes(16)).toBytes();
        FilterRegistrationBean<BodySizeFilter> reg = new FilterRegistrationBean<>(new BodySizeFilter(max));
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return reg;
    }

    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        FilterRegistrationBean<RequestIdFilter> reg = new FilterRegistrationBean<>(new RequestIdFilter());
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return reg;
    }

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    SecurityFilterChain meridianSecurity(HttpSecurity http, MeridianProperties props, JsonMapper json,
                                         org.springframework.core.env.Environment env,
                                         io.meridian.web.security.SecurityProperties security) throws Exception {
        java.util.List<String[]> publicRoutes = publicRoutes(json, env.getProperty("spring.application.name", ""));
        http.csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterAfter(new TenantFilter(props, security), AnonymousAuthenticationFilter.class);
        if (props.devMode()) {
            http.authorizeHttpRequests(a -> a.anyRequest().permitAll());
        } else {
            http.authorizeHttpRequests(a -> {
                for (String[] r : publicRoutes) {
                    a.requestMatchers(HttpMethod.valueOf(r[0]), r[1]).permitAll();
                }
            });
            http.authorizeHttpRequests(a -> a
                    .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                    // GAP-10: the Prometheus scrape carries no token. Services are not routed by the edge gateway
                    // (/v1 only); the chart's NetworkPolicy admits only Meridian pods and the monitoring namespace.
                    .requestMatchers(HttpMethod.GET, "/actuator/prometheus").permitAll()
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> { }));
        }
        return http.build();
    }

    @Bean
    @ConditionalOnMissingBean
    Idempotency idempotency(JdbcClient jdbc, JsonMapper json, TransactionTemplate tx, Environment env) {
        return new Idempotency(jdbc, json, tx,
                env.getProperty("meridian.idempotency.stale-after", java.time.Duration.class, java.time.Duration.ofMinutes(10)),
                env.getProperty("meridian.idempotency.retention", java.time.Duration.class, java.time.Duration.ofDays(30)));
    }

    /** Public provider callbacks: v2 signatures with timestamp, nonce and tenant binding (SEC-17). */
    @Bean
    @ConditionalOnMissingBean
    io.meridian.web.callback.CallbackVerifier callbackVerifier(JdbcClient jdbc, Environment env) {
        return new io.meridian.web.callback.CallbackVerifier(new io.meridian.web.callback.NonceStore.Jdbc(jdbc),
                env.getProperty("meridian.callbacks.max-skew", java.time.Duration.class, java.time.Duration.ofMinutes(5)),
                env.getProperty("meridian.callbacks.accept-legacy-signatures", Boolean.class, true),
                java.time.Clock.systemUTC());
    }

    /** SEC-34: purges completed idempotency keys past their retention (every {@code meridian.idempotency.purge-interval}). */
    @Bean(destroyMethod = "close")
    io.meridian.web.idempotency.IdempotencyJanitor idempotencyJanitor(Idempotency idempotency, Environment env) {
        return new io.meridian.web.idempotency.IdempotencyJanitor(idempotency, env.getProperty(
                "meridian.idempotency.purge-interval", java.time.Duration.class, java.time.Duration.ofHours(6)));
    }

    @Bean
    static MeridianJsonCustomizer meridianJsonCustomizer() {
        return new MeridianJsonCustomizer();
    }

    /** Session advisory locks across replicas (SEC-28). */
    @Bean
    @ConditionalOnMissingBean
    io.meridian.web.tenancy.AdvisoryLocks advisoryLocks(DataSource dataSource) {
        return new io.meridian.web.tenancy.AdvisoryLocks(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean
    TableCatalog tableCatalog(JdbcClient jdbc) {
        return new TableCatalog(jdbc);
    }

    @Bean
    @ConditionalOnMissingBean
    CrudEngine crudEngine(JdbcClient jdbc, JsonMapper json, TableCatalog catalog, Idempotency idempotency) {
        return new CrudEngine(jdbc, json, catalog, idempotency);
    }

    @Bean
    @ConditionalOnMissingBean
    ServiceClient serviceClient(JsonMapper json, Environment env) {
        return new ServiceClient(json, env);
    }

    @Bean
    @ConditionalOnMissingBean
    io.meridian.web.time.BusinessDates businessDates(ServiceClient client, Environment env) {
        return new io.meridian.web.time.BusinessDates(client, env.getProperty("spring.application.name", "unknown"),
                !"clock".equalsIgnoreCase(env.getProperty("meridian.business-date.source", "business-day-service")),
                java.time.Duration.ofSeconds(env.getProperty("meridian.business-date.cache-seconds", Long.class, 30L)));
    }

    /** Per-tenant ledger routing from config-service (namespace {@code ledger}); see {@link io.meridian.web.config.TenantSettings}. */
    @Bean
    @ConditionalOnMissingBean
    io.meridian.web.config.TenantSettings tenantSettings(ServiceClient client, Environment env) {
        return new io.meridian.web.config.TenantSettings(client,
                env.getProperty("meridian.tenant-settings.ttl-ms", Long.class, 30_000L)).install();
    }

    /**
     * GAP-08: generic data-lifecycle operations over the service's own tables (archive extract / confirm / purge, DSAR
     * export / erasure), driven by the spec's personal-data map. {@code meridian.data-lifecycle.enabled=false} turns
     * the internal API off.
     */
    @Bean
    @ConditionalOnMissingBean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "meridian.data-lifecycle.enabled",
            havingValue = "true", matchIfMissing = true)
    io.meridian.web.lifecycle.DataLifecycleService dataLifecycleService(JdbcClient jdbc, TransactionTemplate tx,
                                                                        JsonMapper json, Environment env) {
        String service = env.getProperty("spring.application.name", "unknown");
        java.util.Map<String, String> retain = env instanceof org.springframework.core.env.ConfigurableEnvironment ce
                ? org.springframework.boot.context.properties.bind.Binder.get(ce)
                    .bind("meridian.data-lifecycle.retain-on-erasure",
                            org.springframework.boot.context.properties.bind.Bindable.mapOf(String.class, String.class))
                    .orElse(java.util.Map.of())
                : java.util.Map.of();
        return new io.meridian.web.lifecycle.DataLifecycleService(jdbc, tx, json,
                io.meridian.web.lifecycle.PersonalDataCatalog.forService(json, service), service, retain);
    }

    @Bean
    @ConditionalOnMissingBean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "meridian.data-lifecycle.enabled",
            havingValue = "true", matchIfMissing = true)
    io.meridian.web.lifecycle.DataLifecycleController dataLifecycleController(
            io.meridian.web.lifecycle.DataLifecycleService lifecycle, Environment env) {
        return new io.meridian.web.lifecycle.DataLifecycleController(lifecycle, java.util.Set.of(env.getProperty(
                "meridian.data-lifecycle.callers", String[].class, new String[] {"archive-service", "customer-service"})),
                java.util.Set.of(env.getProperty("meridian.data-lifecycle.move-callers", String[].class,
                        new String[] {"data-mover"})));
    }

    /**
     * MR-GAME-DAY: operator switches (cell role promotion / demotion, outbox replay) and the registry watcher that
     * promotes a standby service when its cell turns ACTIVE. Needs meridian-events (every service has it).
     */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnClass(name = "io.meridian.events.StandbyGate")
    static class CellOpsConfiguration {

        @Bean
        @ConditionalOnMissingBean
        io.meridian.common.cell.CellRole meridianCellRole(Environment env) {
            return new io.meridian.common.cell.CellRole(env.getProperty("meridian.cell.id", ""),
                    io.meridian.common.cell.CellRole.parse(env.getProperty("meridian.cell.role")));
        }

        @Bean
        @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "meridian.ops.enabled",
                havingValue = "true", matchIfMissing = true)
        io.meridian.web.ops.CellOpsController cellOpsController(io.meridian.common.cell.CellRole role,
                org.springframework.beans.factory.ObjectProvider<io.meridian.events.StandbyGate> gate,
                org.springframework.beans.factory.ObjectProvider<io.meridian.events.OutboxReplay> replay,
                org.springframework.beans.factory.ObjectProvider<io.meridian.events.OutboxRelay> relay,
                JsonMapper json, Environment env) {
            return new io.meridian.web.ops.CellOpsController(role, gate, replay, relay, java.util.Set.of(env.getProperty(
                    "meridian.ops.callers", String[].class, new String[] {"platform-ops", "tenant-service"})), json);
        }

        @Bean(destroyMethod = "close")
        @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
                name = "meridian.cell.promotion.watch-registry", havingValue = "true", matchIfMissing = true)
        io.meridian.web.ops.CellPromotionWatcher cellPromotionWatcher(io.meridian.common.cell.CellRole role,
                                                                      ServiceClient client, Environment env) {
            String operator = env.getProperty("meridian.security.platform-operator-tenant", "");
            java.util.UUID operatorTenant = null;
            try {
                operatorTenant = operator.isBlank() ? null : java.util.UUID.fromString(operator.trim());
            } catch (IllegalArgumentException e) {
                operatorTenant = null;
            }
            return new io.meridian.web.ops.CellPromotionWatcher(role,
                    path -> ServiceClient.asService(() -> client.get("tenant-service", path)), operatorTenant,
                    env.getProperty("meridian.cell.promotion.poll-interval", java.time.Duration.class,
                            java.time.Duration.ofSeconds(5))).start();
        }
    }

    @Bean
    WebMvcConfigurer meridianScopeEnforcement(MeridianProperties props,
                                              io.meridian.web.security.SecurityProperties security,
                                              JsonMapper json, Environment env) {
        var publicRoutes = ScopeInterceptor.routes(publicRoutes(json, env.getProperty("spring.application.name", "")));
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new ScopeInterceptor(props, security, publicRoutes)).addPathPatterns("/v1/**");
            }
        };
    }

    /**
     * SEC-14: audience (and optional client allow-list) check, added by Spring Boot to the auto-configured
     * {@code JwtDecoder} next to the issuer / expiry validators.
     */
    @Bean
    org.springframework.security.oauth2.core.OAuth2TokenValidator<org.springframework.security.oauth2.jwt.Jwt>
            meridianAudienceValidator(io.meridian.web.security.SecurityProperties security, Environment env) {
        return new io.meridian.web.security.AudienceValidator(security, env.getProperty("spring.application.name", ""));
    }

    /**
     * Public operations of this service (provider callbacks marked [public] in the spec), as {method, ant pattern}.
     * Read from the generated route table shipped on the classpath.
     */
    static java.util.List<String[]> publicRoutes(JsonMapper json, String service) {
        var res = new org.springframework.core.io.ClassPathResource("meridian/routes.json");
        java.util.List<String[]> out = new java.util.ArrayList<>();
        if (!res.exists()) {
            return out;
        }
        try (var in = res.getInputStream()) {
            for (var r : json.readTree(in)) {
                if (r.path("public").asBoolean(false) && service.equals(r.path("service").asString())) {
                    out.add(new String[] {r.get("method").asString(),
                            r.get("path").asString().replaceAll("\\{[^}/]+}", "*")});
                }
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read route table", e);
        }
        return out;
    }
}
