package io.meridian.gateway;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which inbound headers the gateway forwards, and the client address it reports upstream.
 *
 * <ul>
 *   <li>SEC-13: in {@code jwt} mode (the default) the internal identity headers that services trust only in dev
 *       mode or from an internal service token ({@link #INTERNAL}) are dropped from every external request. In
 *       {@code dev} mode (local launcher, e2e) they pass, because that is how the local stack authenticates.</li>
 *   <li>SEC-16: inbound {@code X-Forwarded-*}, {@code Forwarded} and {@code X-Real-IP} are never copied. The gateway
 *       sets {@code X-Forwarded-For} to exactly one address: the socket peer, or, when the peer is a configured
 *       trusted proxy (load balancer / ingress, {@code meridian.gateway.trusted-proxies}, CIDRs), the right-most
 *       address of the inbound chain that is not itself a trusted proxy.</li>
 * </ul>
 */
public final class HeaderPolicy {
    static final Set<String> HOP_BY_HOP = Set.of("connection", "keep-alive", "proxy-authenticate",
            "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length",
            "expect");
    /** Client-supplied proxy headers: always replaced by the gateway's own values. */
    static final Set<String> FORWARDING = Set.of("x-forwarded-for", "x-forwarded-proto", "x-forwarded-host",
            "x-forwarded-port", "x-forwarded-prefix", "forwarded", "x-real-ip", "x-client-ip", "true-client-ip");
    /** Identity headers that only the local dev stack or Meridian services themselves may send. */
    static final Set<String> INTERNAL = Set.of("x-tenant-id", "x-user", "x-roles", "x-permissions",
            "x-mfa-verified", "x-customer-id", "x-auth-time", "x-acr", "x-on-behalf-of-customer",
            "x-meridian-service", "x-caller-service", "x-agent-id", "x-business-customer-id",
            "x-business-user-id", "x-merchant-id", "x-merchant-user-id");

    private final boolean devMode;
    private final List<Cidr> trustedProxies;

    public HeaderPolicy(String securityMode, List<String> trustedProxies) {
        this.devMode = "dev".equalsIgnoreCase(securityMode == null ? "" : securityMode.trim());
        this.trustedProxies = (trustedProxies == null ? List.<String>of() : trustedProxies).stream()
                .map(String::trim).filter(s -> !s.isEmpty()).map(Cidr::parse).toList();
    }

    public boolean devMode() {
        return devMode;
    }

    /** Whether an inbound request header is copied to the upstream request. */
    public boolean forward(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (HOP_BY_HOP.contains(n) || FORWARDING.contains(n)) {
            return false;
        }
        return devMode || !INTERNAL.contains(n);
    }

    /** True when the socket peer is a configured trusted proxy (its forwarding headers may be believed). */
    public boolean trusted(String peer) {
        InetAddress a = address(peer);
        return a != null && trustedProxies.stream().anyMatch(c -> c.contains(a));
    }

    /**
     * The client address to report: the socket peer unless it is a trusted proxy; then the right-most entry of the
     * inbound {@code X-Forwarded-For} chain that is not a trusted proxy (entries further left are client-chosen).
     */
    public String clientAddress(String peer, List<String> forwardedFor) {
        if (!trusted(peer) || forwardedFor == null || forwardedFor.isEmpty()) {
            return peer;
        }
        List<String> hops = new ArrayList<>();
        for (String h : forwardedFor) {
            Arrays.stream(h.split(",")).map(String::trim).filter(s -> !s.isEmpty()).forEach(hops::add);
        }
        for (int i = hops.size() - 1; i >= 0; i--) {
            String hop = hops.get(i);
            if (address(hop) == null) {
                return peer;            // malformed chain: fall back to what we can see ourselves
            }
            if (!trusted(hop)) {
                return hop;
            }
        }
        return hops.isEmpty() ? peer : hops.get(0);
    }

    private static InetAddress address(String literal) {
        if (literal == null || literal.isBlank()) {
            return null;
        }
        String s = literal.trim();
        if (s.startsWith("[") && s.contains("]")) {
            s = s.substring(1, s.indexOf(']'));
        }
        // Only IP literals: never resolve names (no DNS on the request path, no spoofable host names).
        if (!s.matches("[0-9a-fA-F:.]+") || !(s.contains(".") || s.contains(":"))) {
            return null;
        }
        try {
            return InetAddress.getByName(s);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /** IPv4 / IPv6 network in CIDR notation (a bare address is a /32 or /128). */
    record Cidr(byte[] network, int bits) {
        static Cidr parse(String text) {
            String[] p = text.split("/", 2);
            InetAddress a = address(p[0]);
            if (a == null) {
                throw new IllegalArgumentException("meridian.gateway.trusted-proxies: not an IP/CIDR: " + text);
            }
            int max = a.getAddress().length * 8;
            int bits = p.length == 2 ? Integer.parseInt(p[1].trim()) : max;
            if (bits < 0 || bits > max) {
                throw new IllegalArgumentException("meridian.gateway.trusted-proxies: bad prefix length: " + text);
            }
            return new Cidr(a.getAddress(), bits);
        }

        boolean contains(InetAddress a) {
            byte[] b = a.getAddress();
            if (b.length != network.length) {
                return false;
            }
            int full = bits / 8;
            for (int i = 0; i < full; i++) {
                if (b[i] != network[i]) {
                    return false;
                }
            }
            int rest = bits % 8;
            if (rest == 0) {
                return true;
            }
            int mask = (0xFF << (8 - rest)) & 0xFF;
            return (b[full] & mask) == (network[full] & mask);
        }
    }
}
