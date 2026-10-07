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
@EnableConfigurationProperties(MeridianProperties.class)
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

    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        FilterRegistrationBean<RequestIdFilter> reg = new FilterRegistrationBean<>(new RequestIdFilter());
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return reg;
    }

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    SecurityFilterChain meridianSecurity(HttpSecurity http, MeridianProperties props, JsonMapper json,
                                         org.springframework.core.env.Environment env) throws Exception {
        java.util.List<String[]> publicRoutes = publicRoutes(json, env.getProperty("spring.application.name", ""));
        http.csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterAfter(new TenantFilter(props), AnonymousAuthenticationFilter.class);
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
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> { }));
        }
        return http.build();
    }

    @Bean
    @ConditionalOnMissingBean
    Idempotency idempotency(JdbcClient jdbc, JsonMapper json, TransactionTemplate tx) {
        return new Idempotency(jdbc, json, tx);
    }

    @Bean
    static MeridianJsonCustomizer meridianJsonCustomizer() {
        return new MeridianJsonCustomizer();
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

    @Bean
    WebMvcConfigurer meridianScopeEnforcement(MeridianProperties props) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new ScopeInterceptor(props)).addPathPatterns("/v1/**");
            }
        };
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
