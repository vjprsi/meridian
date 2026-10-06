package io.meridian.filegateway.application;

import io.meridian.common.error.ApiException;
import io.meridian.filegateway.domain.LayoutValidator;
import io.meridian.filegateway.infrastructure.ContentFetcher;
import io.meridian.filegateway.infrastructure.FileStore;
import io.meridian.filegateway.infrastructure.JsonValues;
import io.meridian.filegateway.infrastructure.Rows;
import io.meridian.web.client.ServiceClient;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** File format layouts: definition rules and parsing of samples / files against a layout. */
@Service
public class FormatService {
    private final Rows rows;
    private final ServiceClient services;
    private final ContentFetcher fetcher;
    private final JsonMapper json;

    public FormatService(Rows rows, ServiceClient services, ContentFetcher fetcher, JsonMapper json) {
        this.rows = rows;
        this.services = services;
        this.fetcher = fetcher;
        this.json = json;
    }

    public void validateDefinition(JsonNode v) {
        String kind = JsonValues.text(v, "format_kind");
        Integer recordLength = v.hasNonNull("record_length") ? v.get("record_length").asInt() : null;
        if (recordLength != null && recordLength <= 0) {
            throw ApiException.unprocessable("invalid_record_length", "record_length must be positive");
        }
        try {
            Charset.forName(v.path("encoding").asString("UTF-8"));
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable("invalid_encoding", "Unknown encoding " + v.path("encoding").asString());
        }
        List<String> problems = LayoutValidator.schemaProblems(kind, v.get("schema_definition"), recordLength,
                JsonValues.text(v, "delimiter"));
        if (!problems.isEmpty()) {
            throw ApiException.unprocessable("invalid_layout", String.join("; ", problems));
        }
    }

    /** Parses content against a format definition row. */
    public LayoutValidator.Result validate(JsonNode format, byte[] content) {
        Charset cs;
        try {
            cs = Charset.forName(format.path("encoding").asString("UTF-8"));
        } catch (IllegalArgumentException e) {
            cs = StandardCharsets.UTF_8;
        }
        return LayoutValidator.validate(format.get("format_kind").asString(), format.get("schema_definition"),
                format.hasNonNull("record_length") ? format.get("record_length").asInt() : null,
                JsonValues.text(format, "delimiter"), format.path("has_header").asBoolean(false), new String(content, cs));
    }

    /**
     * Validates a sample against the layout. The sample is taken inline ({@code content} / {@code content_base64}
     * in the body) or, by {@code document_id}, from document-service's pre-signed download URL.
     */
    public JsonNode validateSample(UUID formatId, JsonNode raw) {
        JsonNode format = rows.require("file_format_definition", formatId, false);
        byte[] content = inlineContent(raw);
        if (content == null) {
            String documentId = JsonValues.text(raw, "document_id");
            JsonNode link = services.get("document-service", "/v1/documents/" + documentId + "/download-url");
            String url = link == null ? null : JsonValues.text(link, "url");
            if (url == null) {
                throw ApiException.unprocessable("sample_unavailable", "Document " + documentId + " has no downloadable content");
            }
            content = fetcher.fetch(url);
        }
        LayoutValidator.Result r = validate(format, content);
        ObjectNode out = json.createObjectNode();
        out.put("file_format_definition_id", formatId.toString());
        out.put("code", format.get("code").asString());
        out.put("format_kind", format.get("format_kind").asString());
        out.put("valid", r.valid());
        out.put("record_count", r.recordCount());
        out.put("error_count", r.errorCount());
        out.put("size_bytes", content.length);
        out.put("checksum_sha256", FileStore.sha256(content));
        out.set("errors", errors(r));
        return out;
    }

    public ArrayNode errors(LayoutValidator.Result r) {
        ArrayNode a = json.createArrayNode();
        r.errors().forEach(e -> {
            ObjectNode n = a.addObject();
            n.put("line", e.line());
            n.put("field", e.field());
            n.put("code", e.code());
            n.put("message", e.message());
        });
        return a;
    }

    /** Inline content from a raw request body: {@code content} (text) or {@code content_base64}. */
    public static byte[] inlineContent(JsonNode raw) {
        if (raw.hasNonNull("content_base64")) {
            try {
                return Base64.getDecoder().decode(raw.get("content_base64").asString());
            } catch (IllegalArgumentException e) {
                throw ApiException.unprocessable("invalid_base64", "content_base64 is not valid base64");
            }
        }
        if (raw.hasNonNull("content")) {
            return raw.get("content").asString().getBytes(StandardCharsets.UTF_8);
        }
        return null;
    }
}
