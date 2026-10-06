package io.meridian.gl.api;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
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

/**
 * Keeps the raw JSON request body next to the generated model. Some operations accept fields the generated contract
 * does not declare yet (e.g. journal {@code lines}); controllers read them from here with the same snake_case names.
 */
@ControllerAdvice
public class RawBody extends RequestBodyAdviceAdapter {
    private static final String ATTRIBUTE = RawBody.class.getName();

    @Override
    public boolean supports(MethodParameter methodParameter, Type targetType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage input, MethodParameter parameter, Type targetType,
                                           Class<? extends HttpMessageConverter<?>> converterType) throws IOException {
        byte[] bytes = input.getBody().readAllBytes();
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            attrs.setAttribute(ATTRIBUTE, bytes, RequestAttributes.SCOPE_REQUEST);
        }
        return new HttpInputMessage() {
            @Override
            public InputStream getBody() {
                return new ByteArrayInputStream(bytes);
            }

            @Override
            public HttpHeaders getHeaders() {
                return input.getHeaders();
            }
        };
    }

    /** The current request's JSON body, or an empty object when there is none. */
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
