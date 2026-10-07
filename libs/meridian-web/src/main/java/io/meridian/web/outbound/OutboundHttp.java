package io.meridian.web.outbound;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;

/**
 * HTTP client for tenant-supplied URLs that enforces an {@link OutboundUrlPolicy} at connect time.
 *
 * <p>The connection manager's DNS resolver is the policy itself: the addresses a socket connects to are exactly
 * the addresses that were just validated (no second lookup, so no DNS rebinding between check and connect).
 * Redirects are never followed (a 3xx is returned as is), there are no automatic retries, cookies or proxies,
 * and the exchange is bounded by connect / socket / total timeouts and a response size limit. Errors are reported
 * as coarse categories ({@link Failure}) so callers never echo internal network details back to tenants.
 */
public class OutboundHttp implements AutoCloseable {

    /** Response: status, headers (lower-case names) and at most {@code maxResponseBytes} of the body. */
    public record Response(int status, Map<String, List<String>> headers, byte[] body, boolean truncated) {
        public String header(String name) {
            List<String> v = headers.get(name.toLowerCase(Locale.ROOT));
            return v == null || v.isEmpty() ? null : v.getFirst();
        }
    }

    /** A refused or failed exchange; {@link #code()} is one of the {@code CODE_*} categories. */
    public static final class Failure extends RuntimeException {
        public static final String CODE_BLOCKED = "destination_not_allowed";
        public static final String CODE_TIMEOUT = "timeout";
        public static final String CODE_CONNECT = "connect_failed";
        public static final String CODE_TLS = "tls_error";
        public static final String CODE_IO = "io_error";

        private final String code;

        public Failure(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "outbound-http-deadline");
        t.setDaemon(true);
        return t;
    });

    private final OutboundUrlPolicy policy;
    private final CloseableHttpClient client;

    public OutboundHttp(OutboundUrlPolicy policy) {
        this.policy = policy;
        OutboundUrlPolicy.Settings s = policy.settings();
        DnsResolver resolver = new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                try {
                    return policy.resolveAllowed(host);
                } catch (OutboundUrlPolicy.RejectedException e) {
                    throw new BlockedHostException(e);
                }
            }

            @Override
            public List<InetSocketAddress> resolve(String host, int port) throws UnknownHostException {
                List<InetSocketAddress> out = new ArrayList<>();
                for (InetAddress a : resolve(host)) {
                    out.add(new InetSocketAddress(a, port));
                }
                return out;
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host; // never reverse-resolve
            }
        };
        PoolingHttpClientConnectionManager cm = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(resolver)
                .setMaxConnTotal(200)
                .setMaxConnPerRoute(20)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.of(s.connectTimeout()))
                        .setSocketTimeout(Timeout.of(s.responseTimeout()))
                        .build())
                .build();
        this.client = HttpClients.custom()
                .setConnectionManager(cm)
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableCookieManagement()
                .disableAuthCaching()
                .disableContentCompression()
                .setUserAgent("Meridian-Outbound/1.0")
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setResponseTimeout(Timeout.of(s.responseTimeout()))
                        .setConnectionRequestTimeout(Timeout.of(s.connectTimeout()))
                        .setRedirectsEnabled(false)
                        .build())
                .build();
    }

    public OutboundUrlPolicy policy() {
        return policy;
    }

    public Response get(String url, Map<String, String> headers) {
        return execute("GET", url, headers, null, null);
    }

    public Response post(String url, Map<String, String> headers, byte[] body, String contentType) {
        return execute("POST", url, headers, body, contentType);
    }

    /**
     * Executes one request. Throws {@link OutboundUrlPolicy.RejectedException} for URLs the policy refuses (before
     * any connection) and {@link Failure} for refused destinations at connect time, timeouts and I/O errors.
     */
    public Response execute(String method, String url, Map<String, String> headers, byte[] body, String contentType) {
        return execute(method, url, headers, body, contentType, null);
    }

    /** As {@link #execute(String, String, Map, byte[], String)} with a tighter per-call deadline (e.g. a JIT budget). */
    public Response execute(String method, String url, Map<String, String> headers, byte[] body, String contentType,
                            java.time.Duration timeout) {
        URI uri = policy.check(url);
        java.time.Duration total = timeout == null || timeout.compareTo(policy.settings().totalTimeout()) > 0
                ? policy.settings().totalTimeout() : timeout;
        HttpUriRequestBase req = new HttpUriRequestBase(method.toUpperCase(Locale.ROOT), uri);
        if (headers != null) {
            headers.forEach(req::setHeader);
        }
        if (timeout != null) {
            req.setConfig(RequestConfig.custom().setResponseTimeout(Timeout.of(total))
                    .setConnectionRequestTimeout(Timeout.of(policy.settings().connectTimeout()))
                    .setRedirectsEnabled(false).build());
        }
        if (body != null) {
            req.setEntity(new ByteArrayEntity(body, contentType == null ? ContentType.APPLICATION_OCTET_STREAM
                    : ContentType.parse(contentType)));
        }
        long max = policy.settings().maxResponseBytes();
        ScheduledFuture<?> deadline = DEADLINES.schedule(req::cancel, total.toMillis(), TimeUnit.MILLISECONDS);
        try {
            return client.execute(req, response -> {
                Map<String, List<String>> hs = new LinkedHashMap<>();
                for (Header h : response.getHeaders()) {
                    hs.computeIfAbsent(h.getName().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(h.getValue());
                }
                HttpEntity entity = response.getEntity();
                byte[] data = new byte[0];
                boolean truncated = false;
                if (entity != null) {
                    try (InputStream in = entity.getContent()) {
                        data = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, max + 1));
                    }
                    if (data.length > max) {
                        truncated = true;
                        data = java.util.Arrays.copyOf(data, (int) max);
                    }
                }
                return new Response(response.getCode(), hs, data, truncated);
            });
        } catch (BlockedHostException e) {
            throw new Failure(Failure.CODE_BLOCKED, e.getMessage(), e);
        } catch (SocketTimeoutException e) {
            throw new Failure(Failure.CODE_TIMEOUT, "The remote endpoint did not respond in time", e);
        } catch (ConnectException e) {
            throw new Failure(Failure.CODE_CONNECT, "Cannot connect to the remote endpoint", e);
        } catch (SSLException e) {
            throw new Failure(Failure.CODE_TLS, "TLS handshake with the remote endpoint failed", e);
        } catch (UnknownHostException e) {
            throw new Failure(Failure.CODE_CONNECT, "Host cannot be resolved", e);
        } catch (InterruptedIOException e) {
            throw new Failure(Failure.CODE_TIMEOUT, "The exchange exceeded its time limit", e);
        } catch (IOException e) {
            if (req.isCancelled()) {
                throw new Failure(Failure.CODE_TIMEOUT, "The exchange exceeded its time limit", e);
            }
            throw new Failure(Failure.CODE_IO, "The exchange with the remote endpoint failed", e);
        } finally {
            deadline.cancel(false);
        }
    }

    @Override
    public void close() throws IOException {
        client.close();
    }

    /** Carries a policy refusal through the connection operator (which only declares UnknownHostException). */
    static final class BlockedHostException extends UnknownHostException {
        BlockedHostException(OutboundUrlPolicy.RejectedException cause) {
            super(cause.getMessage());
            initCause(cause);
        }
    }
}
