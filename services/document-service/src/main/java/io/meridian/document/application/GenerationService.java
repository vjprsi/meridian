package io.meridian.document.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.document.domain.Lifecycles;
import io.meridian.document.infrastructure.DocumentStore;
import io.meridian.events.OutboxWriter;
import io.meridian.web.crud.CrudEngine;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Generates a document from an ACTIVE template version and input data: renders it, stores the output as a
 * new document (owned by the same business object) and records a reproducibility hash of the input.
 */
@Service
public class GenerationService {
    private static final Logger log = LoggerFactory.getLogger(GenerationService.class);

    private final CrudEngine crud;
    private final DocumentStore store;
    private final TemplateService templates;
    private final ObjectStorage storage;
    private final OutboxWriter outbox;
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public GenerationService(CrudEngine crud, DocumentStore store, TemplateService templates, ObjectStorage storage,
                             OutboxWriter outbox, JdbcClient jdbc, JsonMapper json) {
        this.crud = crud;
        this.store = store;
        this.templates = templates;
        this.storage = storage;
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public String generate(Object body) {
        ObjectNode values = crud.toColumns("generated_document", body);
        JsonNode data = values.get("data");
        UUID templateId = UUID.fromString(values.get("document_template_id").asString());
        JsonNode template = json.readTree(jdbc.sql("select row_to_json(t)::text from document_template t where id = ?")
                .param(templateId).query(String.class).optional()
                .orElseThrow(() -> ApiException.unprocessable("template_not_found", "Document template " + templateId + " does not exist")));
        if (!"ACTIVE".equals(template.get("status").asString())) {
            throw ApiException.unprocessable("template_not_active",
                    "Template " + templateId + " is " + template.get("status").asString() + "; only ACTIVE templates generate documents");
        }
        if (data != null && !data.isNull() && !data.isObject()) {
            throw ApiException.unprocessable("invalid_data", "data must be a JSON object");
        }
        ObjectNode context = data == null || data.isNull() ? json.createObjectNode() : (ObjectNode) data.deepCopy();
        context.put("owner_type", values.get("owner_type").asString());
        context.put("owner_id", values.get("owner_id").asString());

        UUID id = Ids.newId();
        values.remove("document_id");
        values.put("id", id.toString());
        values.put("input_hash", inputHash(templateId, data));
        values.put("status", "RUNNING");
        crud.insert("generated_document", values);

        String format = template.get("output_format").asString();
        try {
            if (!template.hasNonNull("body")) {
                throw new GenerationFailure("Template has a binary body document; binary rendering is not supported");
            }
            if (format.equals("DOCX") || format.equals("XLSX")) {
                throw new GenerationFailure("Output format " + format + " is not supported by the text renderer");
            }
            TemplateService.Rendered rendered = templates.render(template, context);
            if (!rendered.missing().isEmpty()) {
                throw new GenerationFailure("Missing variables: " + String.join(", ", rendered.missing()));
            }
            byte[] content = rendered.content().getBytes(StandardCharsets.UTF_8);
            UUID documentId = Ids.newId();
            String extension = switch (rendered.contentType()) {
                case "text/plain" -> "txt";
                case "text/csv" -> "csv";
                default -> "html";
            };
            String fileName = Lifecycles.safeFileName(template.get("code").asString() + "-v" + template.get("version").asInt()
                    + "-" + id + "." + extension);
            String key = TenantContext.require() + "/" + documentId + "/" + fileName;
            storeContent(key, content, rendered.contentType());

            ObjectNode doc = json.createObjectNode();
            doc.put("id", documentId.toString());
            doc.put("owner_type", values.get("owner_type").asString());
            doc.put("owner_id", values.get("owner_id").asString());
            doc.put("document_type", Lifecycles.documentTypeFor(template.get("category").asString()));
            doc.put("file_name", fileName);
            doc.put("content_type", rendered.contentType());
            doc.put("size_bytes", content.length);
            doc.put("storage_uri", storage.uri(key));
            doc.put("checksum", sha256Hex(content));
            doc.put("classification", "CONFIDENTIAL");
            doc.put("status", "AVAILABLE");
            doc.put("virus_scan_result", "CLEAN");
            doc.put("uploaded_via", "SYSTEM");
            ObjectNode meta = doc.putObject("metadata");
            meta.put("generated_document_id", id.toString());
            meta.put("document_template_id", templateId.toString());
            meta.put("template_version", template.get("version").asInt());
            meta.put("requested_format", format);
            crud.insert("document", doc);

            jdbc.sql("""
                    update generated_document set status = 'SUCCEEDED', document_id = ?, generated_at = now(), updated_by = ?
                    where id = ?""").params(documentId, TenantContext.principal(), id).update();
            String row = store.row("generated_document", id);
            outbox.publish("platform.generated_document.completed", "generated_document", id, json.readTree(row));
            return row;
        } catch (GenerationFailure | ApiException e) {
            log.info("Generation {} failed: {}", id, e.getMessage());
            jdbc.sql("update generated_document set status = 'FAILED', error = ?, updated_by = ? where id = ?")
                    .params(e.getMessage(), TenantContext.principal(), id).update();
            String row = store.row("generated_document", id);
            outbox.publish("platform.generated_document.failed", "generated_document", id, json.readTree(row));
            return row;
        }
    }

    private void storeContent(String key, byte[] content, String contentType) {
        try {
            storage.put(key, content, contentType);
        } catch (RuntimeException e) {
            throw new GenerationFailure("Storing the generated document failed: " + e.getMessage());
        }
    }

    private String inputHash(UUID templateId, JsonNode data) {
        Map<String, Object> canonical = new TreeMap<>();
        canonical.put("document_template_id", templateId.toString());
        canonical.put("data", data == null || data.isNull() ? Map.of() : canonicalize(json.convertValue(data, Object.class)));
        return sha256Hex(json.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8));
    }

    private static Object canonicalize(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> sorted = new TreeMap<>();
            m.forEach((k, v) -> sorted.put(String.valueOf(k), canonicalize(v)));
            return sorted;
        }
        if (o instanceof java.util.List<?> l) {
            return l.stream().map(GenerationService::canonicalize).toList();
        }
        return o;
    }

    static String sha256Hex(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class GenerationFailure extends RuntimeException {
        GenerationFailure(String message) {
            super(message);
        }
    }
}
