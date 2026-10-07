package io.meridian.search.infrastructure;

import io.meridian.search.domain.FeedMappings;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Loads and validates the indexing feed's event mappings ({@code meridian.search.indexer.mappings}). */
@Configuration(proxyBeanMethods = false)
public class FeedMappingsConfig {

    @Bean
    FeedMappings feedMappings(@Value("${meridian.search.indexer.mappings:classpath:search-mappings.yaml}") Resource resource) {
        return load(resource);
    }

    public static FeedMappings load(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            Map<String, Object> doc = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
            return FeedMappings.from(doc);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read search mappings " + resource, e);
        }
    }
}
