package io.meridian.fraud.infrastructure;

import io.meridian.fraud.application.ModelRuntime;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Hot reload: deployment and model-version events of ai-platform-service ({@code meridian.reporting.v1}) make every
 * fraud-service instance reload the tenant's models in the background. Each instance consumes with its own group
 * (every replica must reload), from the latest offset (older events are covered by the load on first use).
 */
@Component
@ConditionalOnProperty(name = "meridian.fraud.ml.model-events.enabled", havingValue = "true", matchIfMissing = true)
public class ModelEventsListener {
    private static final Logger log = LoggerFactory.getLogger(ModelEventsListener.class);

    private final ModelRuntime runtime;

    public ModelEventsListener(ModelRuntime runtime) {
        this.runtime = runtime;
    }

    @KafkaListener(id = "fraud-model-events", topics = "${meridian.fraud.ml.model-events-topic:meridian.reporting.v1}",
            groupId = "${meridian.fraud.ml.model-events-group:fraud-service-models}-${random.uuid}",
            properties = {"auto.offset.reset=latest"})
    public void onEvent(ConsumerRecord<String, String> record) {
        String type = FeatureEventsListener.header(record, "ce_type");
        String tenant = FeatureEventsListener.header(record, "ce_tenantid");
        if (type == null || tenant == null || !relevant(type)) {
            return;
        }
        try {
            UUID t = UUID.fromString(tenant);
            if (runtime.loaded(t)) {
                log.info("Reloading fraud models of tenant {} after {}", t, type);
                runtime.refreshAsync(t);
            }
        } catch (IllegalArgumentException e) {
            log.warn("Ignoring model event with tenant {}", tenant);
        }
    }

    static boolean relevant(String type) {
        return type.startsWith("reporting.model_deployment.") || type.startsWith("reporting.ml_model_version.");
    }
}
