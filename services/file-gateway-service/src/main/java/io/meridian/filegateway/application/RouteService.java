package io.meridian.filegateway.application;

import io.meridian.common.error.ApiException;
import io.meridian.filegateway.domain.FileRules;
import io.meridian.filegateway.infrastructure.JsonValues;
import io.meridian.filegateway.infrastructure.Rows;
import io.meridian.filegateway.infrastructure.ServiceCatalog;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** Routing of inbound files to consumer services: rule validation and matching. */
@Service
public class RouteService {
    private final Rows rows;
    private final ServiceCatalog catalog;

    public RouteService(Rows rows, ServiceCatalog catalog) {
        this.rows = rows;
        this.catalog = catalog;
    }

    public void validate(JsonNode v) {
        FileRules.regex("file_name_pattern", JsonValues.text(v, "file_name_pattern") == null ? "" : v.get("file_name_pattern").asString());
        String target = JsonValues.text(v, "target_service");
        if (target == null || !catalog.exists(target)) {
            throw ApiException.unprocessable("unknown_service", "target_service " + target + " is not a Meridian service");
        }
        String topic = JsonValues.text(v, "target_topic");
        if (topic == null || topic.isBlank() || topic.contains("://") || topic.contains("..")) {
            throw ApiException.unprocessable("invalid_target_topic",
                    "target_topic must be a service-relative command path (/v1/...) or a Kafka topic name");
        }
        if (v.hasNonNull("file_channel_id")) {
            JsonNode ch = rows.text("file_channel", UUID.fromString(v.get("file_channel_id").asString()), false)
                    .map(rows::tree).orElseThrow(() -> ApiException.unprocessable("unknown_channel", "file_channel_id does not exist"));
            if (!FileRules.receives(ch.get("direction").asString())) {
                throw ApiException.unprocessable("channel_not_inbound", "Routes apply to inbound channels only");
            }
        }
        if (v.hasNonNull("file_format_definition_id")
                && rows.text("file_format_definition", UUID.fromString(v.get("file_format_definition_id").asString()), false).isEmpty()) {
            throw ApiException.unprocessable("unknown_format", "file_format_definition_id does not exist");
        }
    }

    /**
     * The route for a file: active rules of the channel first, then channel-independent ones, each by ascending
     * priority; the first whose pattern matches the whole file name wins.
     */
    public Optional<JsonNode> match(UUID channelId, String fileName) {
        for (JsonNode r : rows.query("""
                select row_to_json(r)::text from file_route r
                where is_active and (file_channel_id = ? or file_channel_id is null)
                order by (file_channel_id is null), priority, created_at""", channelId)) {
            if (FileRules.matches(r.get("file_name_pattern").asString(), fileName)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }
}
