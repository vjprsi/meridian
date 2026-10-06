package io.meridian.web;

import io.meridian.web.crud.CrudEngine;
import io.meridian.web.crud.TableCatalog;
import io.meridian.web.error.ProblemHandler;
import io.meridian.web.json.MeridianJsonCustomizer;
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
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.transaction.support.TransactionTemplate;
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

    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        FilterRegistrationBean<RequestIdFilter> reg = new FilterRegistrationBean<>(new RequestIdFilter());
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return reg;
    }

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    SecurityFilterChain meridianSecurity(HttpSecurity http, MeridianProperties props) throws Exception {
        http.csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterAfter(new TenantFilter(props), AnonymousAuthenticationFilter.class);
        if (props.devMode()) {
            http.authorizeHttpRequests(a -> a.anyRequest().permitAll());
        } else {
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
}
