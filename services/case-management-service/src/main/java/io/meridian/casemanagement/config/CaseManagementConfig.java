package io.meridian.casemanagement.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@link CaseManagementProperties} (meridian.casemanagement.*). */
@Configuration
@EnableConfigurationProperties(CaseManagementProperties.class)
public class CaseManagementConfig {
}
