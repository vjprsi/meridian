package io.meridian.biller.infrastructure.connector;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * Hermetic {@link ConnectorTransport} for connector tests: it never touches the network. Responses and failures are
 * scripted in call order and every request is recorded so tests can assert on URL, headers (idempotency key) and body.
 */
class StubTransport implements ConnectorTransport {

    record Call(String method, String url, Map<String, String> headers, String body) {}

    final List<Call> calls = new ArrayList<>();
    private final Deque<Object> script = new ArrayDeque<>();

    StubTransport respond(int status, String body) {
        script.add(new Response(status, body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8), false));
        return this;
    }

    StubTransport fail(boolean retryable) {
        script.add(new Failure("stub-failure", retryable, null));
        return this;
    }

    @Override
    public Response send(String method, String url, Map<String, String> headers, byte[] body, String contentType,
                         Duration timeout) {
        calls.add(new Call(method, url, Map.copyOf(headers), body == null ? null : new String(body, StandardCharsets.UTF_8)));
        Object next = script.poll();
        if (next == null) {
            throw new AssertionError("StubTransport received an unscripted request to " + url);
        }
        if (next instanceof Failure f) {
            throw f;
        }
        return (Response) next;
    }
}
