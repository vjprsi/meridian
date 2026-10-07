package io.meridian.fraud.infrastructure;

import io.meridian.web.client.ServiceClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * ai-platform-service over ServiceClient, always with this service's own identity (the model registry is read and
 * the feature catalogue / monitoring written by fraud-service, whoever triggered the call). Pages of up to 200
 * rows; a tenant with more fraud models or deployments than that sees the newest 200.
 */
@Component
public class AiPlatformClient {
    static final String AI = "ai-platform-service";

    private final ServiceClient client;

    public AiPlatformClient(ServiceClient client) {
        this.client = client;
    }

    public List<JsonNode> fraudModels() {
        return data(ServiceClient.asService(() -> client.get(AI, "/v1/ai/models?model_type=FRAUD&limit=200")));
    }

    public List<JsonNode> activeDeployments(String environment) {
        return data(ServiceClient.asService(() -> client.get(AI,
                "/v1/ai/deployments?environment=" + environment + "&status=ACTIVE&limit=200")));
    }

    public JsonNode version(UUID versionId) {
        return ServiceClient.asService(() -> client.get(AI, "/v1/ai/model-versions/" + versionId));
    }

    public JsonNode artifact(UUID versionId) {
        return ServiceClient.asService(() -> client.get(AI, "/v1/ai/model-versions/" + versionId + "/artifact"));
    }

    public List<JsonNode> features(String featureSet) {
        List<JsonNode> out = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 10; page++) {
            String path = "/v1/ai/features?feature_set=" + featureSet + "&limit=200" + (cursor == null ? "" : "&cursor=" + cursor);
            JsonNode r = ServiceClient.asService(() -> client.get(AI, path));
            out.addAll(data(r));
            if (r == null || !r.path("has_more").asBoolean(false) || !r.hasNonNull("next_cursor")) {
                break;
            }
            cursor = r.get("next_cursor").asString();
        }
        return out;
    }

    public JsonNode createFeature(Map<String, Object> body, String key) {
        return ServiceClient.asService(() -> client.post(AI, "/v1/ai/features", body, key));
    }

    public JsonNode updateFeature(UUID id, Map<String, Object> body, int rowVersion) {
        return ServiceClient.asService(() -> client.patch(AI, "/v1/ai/features/" + id, body, "\"" + rowVersion + "\""));
    }

    public JsonNode drift(UUID deploymentId, Map<String, Object> body, String key) {
        return ServiceClient.asService(() -> client.post(AI, "/v1/ai/deployments/" + deploymentId + "/drift", body, key));
    }

    public JsonNode metric(Map<String, Object> body, String key) {
        return ServiceClient.asService(() -> client.post(AI, "/v1/ai/model-metrics", body, key));
    }

    private static List<JsonNode> data(JsonNode page) {
        List<JsonNode> out = new ArrayList<>();
        if (page != null && page.path("data").isArray()) {
            page.get("data").forEach(out::add);
        }
        return out;
    }
}
