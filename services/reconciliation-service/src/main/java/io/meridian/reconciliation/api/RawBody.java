package io.meridian.reconciliation.api;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.Map;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Keeps the request's JSON body next to the generated model, for fields the generated contract does not declare
 * (file {@code content} / {@code records}).
 * <p>
 * Contract workaround: json columns are typed {@code object} in the generated models, so list-valued fields
 * ({@code match_fields}, {@code external_record_ids}, {@code internal_entry_ids}) are stored in a canonical object
 * form, {@code {"fields": [...]}} / {@code {"ids": [...]}}. Clients may send either the plain array or the object;
 * arrays are wrapped here before binding.
 */
@ControllerAdvice
public class RawBody extends RequestBodyAdviceAdapter {
    private static final String ATTRIBUTE = RawBody.class.getName();
    /** List-valued json fields and the key of their canonical object form. */
    public static final Map<String, String> WRAPPED = Map.of("match_fields", "fields", "external_record_ids", "ids",
            "internal_entry_ids", "ids");

    private final JsonMapper json;

    public RawBody(JsonMapper json) {
        this.json = json;
    }

    @Override
    public boolean supports(MethodParameter methodParameter, Type targetType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage input, MethodParameter parameter, Type targetType,
                                           Class<? extends HttpMessageConverter<?>> converterType) throws IOException {
        byte[] bytes = input.getBody().readAllBytes();
        bytes = canonical(bytes);
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            attrs.setAttribute(ATTRIBUTE, bytes, RequestAttributes.SCOPE_REQUEST);
        }
        byte[] body = bytes;
        return new HttpInputMessage() {
            @Override
            public InputStream getBody() {
                return new ByteArrayInputStream(body);
            }

            @Override
            public HttpHeaders getHeaders() {
                return input.getHeaders();
            }
        };
    }

    private byte[] canonical(byte[] bytes) {
        if (bytes.length == 0) {
            return bytes;
        }
        JsonNode n;
        try {
            n = json.readTree(bytes);
        } catch (RuntimeException e) {
            return bytes; // let the message converter report the malformed body
        }
        if (!(n instanceof ObjectNode o)) {
            return bytes;
        }
        boolean changed = false;
        for (Map.Entry<String, String> w : WRAPPED.entrySet()) {
            JsonNode v = o.get(w.getKey());
            if (v != null && v.isArray()) {
                ObjectNode wrapped = json.createObjectNode();
                wrapped.set(w.getValue(), v);
                o.set(w.getKey(), wrapped);
                changed = true;
            }
        }
        return changed ? json.writeValueAsBytes(o) : bytes;
    }

    /** The current request's JSON body (canonical form), or an empty object when there is none. */
    public static JsonNode json(JsonMapper mapper) {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        Object raw = attrs == null ? null : attrs.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (raw instanceof byte[] b && b.length > 0) {
            JsonNode n = mapper.readTree(b);
            if (n != null && n.isObject()) {
                return n;
            }
        }
        return mapper.createObjectNode();
    }
}
