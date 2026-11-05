package io.meridian.biller.infrastructure.connector;

import io.meridian.web.outbound.OutboundHttp;
import io.meridian.web.outbound.OutboundUrlPolicy;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * Default {@link ConnectorTransport}: the shared, policy-checked outbound HTTP client. The biller's
 * {@code connection_config.base_url} is tenant-supplied, so every call goes through {@link OutboundUrlPolicy}
 * (SEC-18): https only, public addresses pinned at connect time, no redirects, bounded response size. A per-call
 * timeout (from the connection config) narrows the response budget further. Failures are translated to
 * {@link Failure} with a retryable flag so the resilient connector base can decide whether to retry.
 */
@Component
public class OutboundTransport implements ConnectorTransport, DisposableBean {
    private final OutboundHttp http;

    public OutboundTransport(OutboundUrlPolicy policy) {
        this.http = new OutboundHttp(policy.with(policy.settings()
                .withTimeouts(Duration.ofSeconds(3), Duration.ofSeconds(20), Duration.ofSeconds(20))
                .withMaxResponseBytes(256 * 1024)));
    }

    @Override
    public Response send(String method, String url, Map<String, String> headers, byte[] body, String contentType,
                         Duration timeout) {
        try {
            OutboundHttp.Response r = http.execute(method, url, headers, body, contentType, timeout);
            return new Response(r.status(), r.body(), r.truncated());
        } catch (OutboundHttp.Failure e) {
            boolean retryable = OutboundHttp.Failure.CODE_TIMEOUT.equals(e.code())
                    || OutboundHttp.Failure.CODE_CONNECT.equals(e.code());
            throw new Failure(e.code(), retryable, e);
        } catch (OutboundUrlPolicy.RejectedException e) {
            throw new Failure("destination_not_allowed", false, e);
        }
    }

    @Override
    public void destroy() throws Exception {
        http.close();
    }
}
