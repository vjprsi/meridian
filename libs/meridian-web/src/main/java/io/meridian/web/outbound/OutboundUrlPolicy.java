package io.meridian.web.outbound;

import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.env.Environment;

/**
 * Shared policy for outbound requests to tenant-supplied URLs (SEC-08 / SEC-09 / SEC-18): webhook endpoints,
 * partner file URLs, provider endpoints.
 *
 * <ul>
 *   <li>https only; plain http only in {@code meridian.security-mode=dev} with {@code meridian.outbound.allow-http}</li>
 *   <li>no credentials in the URL, a host is required, no IP-literal tricks (decimal / octal / hex hosts)</li>
 *   <li>the host is resolved and <b>every</b> address must be public: loopback, unspecified, private (RFC 1918),
 *       CGNAT (100.64/10), link-local incl. cloud metadata (169.254.169.254), benchmarking, documentation, multicast,
 *       reserved, IPv6 ULA (fc00::/7, so fd00::/8 and {@code fd00:ec2::254}), site/link-local IPv6, and IPv4 embedded
 *       in IPv6 (mapped, compatible, 6to4, NAT64, Teredo) are rejected</li>
 *   <li>{@link OutboundHttp} connects only to the addresses validated by this policy for that very connection (the
 *       resolver used by the connection manager is the policy), so a DNS answer cannot change between check and
 *       connect (DNS rebinding); redirects are never followed; connect / response / total time and response size are
 *       bounded</li>
 * </ul>
 *
 * Relaxations for local development ({@code allow-http}, {@code allow-private-networks}) are honoured only in dev
 * mode. Operators may list hosts that are allowed to resolve to private (RFC 1918 / ULA / CGNAT) addresses in
 * {@code meridian.outbound.allowed-private-hosts} (on-premises partners); loopback, link-local and metadata
 * addresses are refused even for those.
 */
public final class OutboundUrlPolicy {

    /** Why a URL or address was refused; {@link #code()} is a stable machine-readable reason. */
    public static final class RejectedException extends RuntimeException {
        private final String code;

        public RejectedException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** Name resolution, replaceable in tests. */
    @FunctionalInterface
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /**
     * @param allowHttp             accept http:// (dev mode only)
     * @param allowPrivateNetworks  accept loopback / private targets (dev mode only)
     * @param allowedPrivateHosts   operator allow-list of host names that may resolve to private (not loopback /
     *                              link-local) addresses
     * @param connectTimeout        TCP + TLS connect timeout
     * @param responseTimeout       socket read timeout
     * @param totalTimeout          deadline for the whole exchange
     * @param maxResponseBytes      responses larger than this are cut (or refused by callers that need the whole body)
     */
    public record Settings(boolean allowHttp, boolean allowPrivateNetworks, Set<String> allowedPrivateHosts,
                           Duration connectTimeout, Duration responseTimeout, Duration totalTimeout,
                           long maxResponseBytes) {
        public Settings {
            allowedPrivateHosts = allowedPrivateHosts == null ? Set.of() : allowedPrivateHosts.stream()
                    .map(h -> h.trim().toLowerCase(Locale.ROOT)).filter(h -> !h.isEmpty()).collect(Collectors.toUnmodifiableSet());
            connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
            responseTimeout = responseTimeout == null ? Duration.ofSeconds(30) : responseTimeout;
            totalTimeout = totalTimeout == null ? Duration.ofSeconds(60) : totalTimeout;
            maxResponseBytes = maxResponseBytes <= 0 ? 10L * 1024 * 1024 : maxResponseBytes;
        }

        /** Strict production defaults: https, public addresses only. */
        public static Settings strict() {
            return new Settings(false, false, Set.of(), null, null, null, 0);
        }

        public Settings withTimeouts(Duration connect, Duration response, Duration total) {
            return new Settings(allowHttp, allowPrivateNetworks, allowedPrivateHosts, connect, response, total, maxResponseBytes);
        }

        public Settings withMaxResponseBytes(long max) {
            return new Settings(allowHttp, allowPrivateNetworks, allowedPrivateHosts, connectTimeout, responseTimeout,
                    totalTimeout, max);
        }
    }

    private final Settings settings;
    private final boolean devMode;
    private final Resolver resolver;

    public OutboundUrlPolicy(Settings settings, boolean devMode, Resolver resolver) {
        this.devMode = devMode;
        this.resolver = resolver == null ? InetAddress::getAllByName : resolver;
        // relaxations never apply outside dev mode
        this.settings = devMode ? settings : new Settings(false, false, settings.allowedPrivateHosts(),
                settings.connectTimeout(), settings.responseTimeout(), settings.totalTimeout(), settings.maxResponseBytes());
    }

    public OutboundUrlPolicy(Settings settings, boolean devMode) {
        this(settings, devMode, null);
    }

    /**
     * Policy from {@code meridian.security-mode} and {@code meridian.outbound.*}: {@code allow-http},
     * {@code allow-private-networks} (dev only), {@code allowed-private-hosts}, {@code connect-timeout-ms},
     * {@code response-timeout-ms}, {@code total-timeout-ms}, {@code max-response-bytes}.
     */
    public static OutboundUrlPolicy fromEnvironment(Environment env) {
        boolean dev = "dev".equalsIgnoreCase(env.getProperty("meridian.security-mode", "jwt").trim());
        String[] hosts = env.getProperty("meridian.outbound.allowed-private-hosts", String[].class, new String[0]);
        Settings s = new Settings(
                env.getProperty("meridian.outbound.allow-http", Boolean.class, false),
                env.getProperty("meridian.outbound.allow-private-networks", Boolean.class, false),
                Set.of(hosts),
                Duration.ofMillis(env.getProperty("meridian.outbound.connect-timeout-ms", Long.class, 5_000L)),
                Duration.ofMillis(env.getProperty("meridian.outbound.response-timeout-ms", Long.class, 30_000L)),
                Duration.ofMillis(env.getProperty("meridian.outbound.total-timeout-ms", Long.class, 60_000L)),
                env.getProperty("meridian.outbound.max-response-bytes", Long.class, 10L * 1024 * 1024));
        return new OutboundUrlPolicy(s, dev);
    }

    /** A copy with other settings (dev relaxations are still ignored outside dev mode). */
    public OutboundUrlPolicy with(Settings other) {
        return new OutboundUrlPolicy(other, devMode, resolver);
    }

    /** A copy that, in dev mode only, also accepts http and loopback / private targets (local receivers in tests). */
    public OutboundUrlPolicy relaxedForDev(boolean allowHttp, boolean allowPrivateNetworks) {
        return with(new Settings(allowHttp, allowPrivateNetworks, settings.allowedPrivateHosts(), settings.connectTimeout(),
                settings.responseTimeout(), settings.totalTimeout(), settings.maxResponseBytes()));
    }

    /** A copy resolving names with the given resolver (tests). */
    public OutboundUrlPolicy withResolver(Resolver r) {
        return new OutboundUrlPolicy(settings, devMode, r);
    }

    public Settings settings() {
        return settings;
    }

    public boolean devMode() {
        return devMode;
    }

    // ------------------------------------------------------------------ checks

    /** Syntax and host-name checks only (no DNS): use when storing a URL; {@link #check} again before connecting. */
    public URI checkSyntax(String url) {
        if (url == null || url.isBlank() || url.length() > 2048) {
            throw new RejectedException("invalid_url", "URL is missing or too long");
        }
        URI u;
        try {
            u = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw new RejectedException("invalid_url", "URL is not a valid URI");
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !("http".equals(scheme) && settings.allowHttp())) {
            throw new RejectedException("https_required", "URL must use https");
        }
        if (u.getRawUserInfo() != null) {
            throw new RejectedException("invalid_url", "URL must not contain credentials");
        }
        String host = u.getHost();
        if (host == null || host.isBlank()) {
            throw new RejectedException("invalid_url", "URL must have a host");
        }
        int port = u.getPort();
        if (port == 0 || port > 65535) {
            throw new RejectedException("invalid_url", "URL port is not valid");
        }
        if (!settings.allowPrivateNetworks() && !isPublicHostLiteral(host) && !privateHostAllowed(host)) {
            throw new RejectedException("destination_not_allowed", "URL must point to a public host");
        }
        return u;
    }

    /** Syntax check plus resolution: every address the host resolves to must be allowed. */
    public URI check(String url) {
        URI u = checkSyntax(url);
        resolveAllowed(u.getHost());
        return u;
    }

    /**
     * Resolves the host and returns its addresses if every one of them is allowed; this is the resolver the
     * {@link OutboundHttp} connection manager uses, so the addresses returned are the ones connected to.
     */
    public InetAddress[] resolveAllowed(String host) {
        String h = stripBrackets(host);
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(h);
        } catch (UnknownHostException e) {
            throw new RejectedException("unresolvable_host", "Host cannot be resolved");
        }
        if (addresses == null || addresses.length == 0) {
            throw new RejectedException("unresolvable_host", "Host cannot be resolved");
        }
        for (InetAddress a : addresses) {
            if (!addressAllowed(h, a)) {
                throw new RejectedException("destination_not_allowed",
                        "Host resolves to a non-public address; outbound calls to internal networks are not allowed");
            }
        }
        return Arrays.copyOf(addresses, addresses.length);
    }

    boolean addressAllowed(String host, InetAddress a) {
        if (settings.allowPrivateNetworks()) {
            return true;
        }
        if (isPublicAddress(a)) {
            return true;
        }
        // operator-listed on-premises hosts may use private ranges, never loopback / link-local / metadata
        return privateHostAllowed(host) && isPrivateRange(a);
    }

    private boolean privateHostAllowed(String host) {
        return settings.allowedPrivateHosts().contains(stripBrackets(host).toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------ address classification

    /**
     * Syntactic host check without DNS: false for {@code localhost}, {@code *.localhost}, {@code *.local},
     * {@code *.internal}, {@code *.svc} / {@code *.cluster.local} (Kubernetes), single-label names, numeric hosts
     * in non-dotted-quad form and IP literals in a blocked range.
     */
    public static boolean isPublicHostLiteral(String host) {
        if (host == null) {
            return false;
        }
        String h = stripBrackets(host.trim()).toLowerCase(Locale.ROOT);
        if (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        if (h.isEmpty() || h.length() > 253) {
            return false;
        }
        if (h.contains(":")) { // IPv6 literal
            InetAddress a = literal(h);
            return a != null && isPublicAddress(a);
        }
        if (h.matches("[0-9.]+")) {
            if (!h.matches("\\d{1,3}(\\.\\d{1,3}){3}") || h.matches("(.*\\.)?0\\d.*")) {
                return false; // 2130706433, 127.1, 0177.0.0.1 ... (alternate / octal IPv4 notations)
            }
            InetAddress a = literal(h);
            return a != null && isPublicAddress(a);
        }
        if (h.contains("0x") && h.matches("[0-9a-fx.]+")) {
            return false; // hex IPv4 notations
        }
        try {
            h = IDN.toASCII(h, IDN.USE_STD3_ASCII_RULES);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (!h.contains(".")) {
            return false; // single-label names resolve through the cluster search path
        }
        return !(h.equals("localhost") || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal")
                || h.endsWith(".svc") || h.endsWith(".cluster.local") || h.endsWith(".home.arpa")
                || h.endsWith(".localdomain") || h.endsWith(".lan") || h.endsWith(".intranet") || h.endsWith(".corp"));
    }

    /** True if the address is globally routable (not loopback, private, link-local, metadata, reserved ...). */
    public static boolean isPublicAddress(InetAddress a) {
        if (a == null || a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                || a.isSiteLocalAddress() || a.isMulticastAddress()) {
            return false;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            return isPublicV4(b);
        }
        if (a instanceof Inet6Address) {
            return isPublicV6(b);
        }
        return false;
    }

    /** RFC 1918, CGNAT and IPv6 ULA ranges (what {@code allowed-private-hosts} may use). */
    static boolean isPrivateRange(InetAddress a) {
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int o0 = b[0] & 0xff;
            int o1 = b[1] & 0xff;
            return o0 == 10 || (o0 == 172 && o1 >= 16 && o1 <= 31) || (o0 == 192 && o1 == 168)
                    || (o0 == 100 && o1 >= 64 && o1 <= 127);
        }
        return b.length == 16 && (b[0] & 0xfe) == 0xfc && !(b[0] == (byte) 0xfd && b[1] == 0x00 && b[2] == 0x0e
                && b[3] == (byte) 0xc2); // fd00:ec2::/32 (AWS metadata) is never allowed
    }

    private static boolean isPublicV4(byte[] b) {
        int o0 = b[0] & 0xff;
        int o1 = b[1] & 0xff;
        int o2 = b[2] & 0xff;
        if (o0 == 0 || o0 == 10 || o0 == 127) {
            return false;                                   // this network, private, loopback
        }
        if (o0 == 100 && o1 >= 64 && o1 <= 127) {
            return false;                                   // CGNAT 100.64/10 (incl. Alibaba metadata 100.100.100.200)
        }
        if (o0 == 169 && o1 == 254) {
            return false;                                   // link-local incl. 169.254.169.254 metadata
        }
        if (o0 == 172 && o1 >= 16 && o1 <= 31) {
            return false;                                   // private
        }
        if (o0 == 192 && o1 == 0 && (o2 == 0 || o2 == 2)) {
            return false;                                   // IETF protocol assignments, TEST-NET-1
        }
        if (o0 == 192 && o1 == 88 && o2 == 99) {
            return false;                                   // 6to4 relay anycast
        }
        if (o0 == 192 && o1 == 168) {
            return false;                                   // private
        }
        if (o0 == 198 && (o1 == 18 || o1 == 19)) {
            return false;                                   // benchmarking 198.18/15
        }
        if (o0 == 198 && o1 == 51 && o2 == 100) {
            return false;                                   // TEST-NET-2
        }
        if (o0 == 203 && o1 == 0 && o2 == 113) {
            return false;                                   // TEST-NET-3
        }
        return o0 < 224;                                    // multicast 224/4, reserved 240/4, broadcast
    }

    private static boolean isPublicV6(byte[] b) {
        // IPv4-mapped ::ffff:a.b.c.d and IPv4-compatible ::a.b.c.d -> judge the embedded IPv4 address
        boolean first10Zero = true;
        for (int i = 0; i < 10; i++) {
            first10Zero &= b[i] == 0;
        }
        if (first10Zero && ((b[10] == (byte) 0xff && b[11] == (byte) 0xff) || (b[10] == 0 && b[11] == 0))) {
            return isPublicV4(Arrays.copyOfRange(b, 12, 16));
        }
        int w0 = ((b[0] & 0xff) << 8) | (b[1] & 0xff);
        int w1 = ((b[2] & 0xff) << 8) | (b[3] & 0xff);
        if ((b[0] & 0xfe) == 0xfc) {
            return false;                                   // unique local fc00::/7 (fd00::/8, fd00:ec2::254)
        }
        if ((w0 & 0xffc0) == 0xfe80 || (w0 & 0xffc0) == 0xfec0) {
            return false;                                   // link-local, deprecated site-local
        }
        if ((b[0] & 0xff) == 0xff) {
            return false;                                   // multicast
        }
        if (w0 == 0x2002) {
            return isPublicV4(Arrays.copyOfRange(b, 2, 6)); // 6to4 embeds an IPv4 address
        }
        if (w0 == 0x0064 && w1 == 0xff9b) {
            return false;                                   // NAT64 64:ff9b::/96 and 64:ff9b:1::/48
        }
        if (w0 == 0x2001 && w1 == 0x0000) {
            return false;                                   // Teredo
        }
        if (w0 == 0x2001 && w1 == 0x0db8) {
            return false;                                   // documentation
        }
        if (w0 == 0x0100 && w1 == 0 && b[4] == 0 && b[5] == 0 && b[6] == 0 && b[7] == 0) {
            return false;                                   // discard-only 100::/64
        }
        return (b[0] & 0xe0) == 0x20;                       // only global unicast 2000::/3
    }

    private static InetAddress literal(String h) {
        try {
            // only literals reach getByName here (no DNS lookup for numeric / IPv6 input)
            return InetAddress.getByName(h);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static String stripBrackets(String host) {
        return host != null && host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }

    /** Short description of the active settings (for startup logs). */
    @Override
    public String toString() {
        return "OutboundUrlPolicy[https" + (settings.allowHttp() ? "+http" : "") + ", "
                + (settings.allowPrivateNetworks() ? "private networks allowed (dev)" : "public addresses only")
                + (settings.allowedPrivateHosts().isEmpty() ? "" : ", private hosts " + List.copyOf(settings.allowedPrivateHosts()))
                + "]";
    }
}
