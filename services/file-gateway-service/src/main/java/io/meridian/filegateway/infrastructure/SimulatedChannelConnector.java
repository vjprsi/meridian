package io.meridian.filegateway.infrastructure;

import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Connector used until protocol adapters (SFTP/FTPS/S3 SDKs) are wired: validates the channel configuration
 * as a real handshake would, treats hosts under the reserved {@code .invalid} TLD as unreachable, accepts
 * uploads, and reports an empty remote folder on polls.
 */
@Component
public class SimulatedChannelConnector implements ChannelConnector {

    @Override
    public ConnectionResult test(JsonNode channel) {
        long start = System.nanoTime();
        String host = channel.path("host").asString("");
        String protocol = channel.path("protocol").asString();
        if (host.isBlank()) {
            return new ConnectionResult(false, 0, null, "No host configured for " + protocol);
        }
        if (unreachable(host)) {
            return new ConnectionResult(false, elapsed(start), host, "Host " + host + " could not be resolved");
        }
        if ("SFTP".equals(protocol) && channel.path("host_key_fingerprint").asString("").isBlank()) {
            return new ConnectionResult(false, elapsed(start), host, "SFTP host key fingerprint is not pinned");
        }
        return new ConnectionResult(true, elapsed(start), host + portSuffix(channel), "Connected and authenticated");
    }

    @Override
    public List<RemoteFile> list(JsonNode channel) {
        requireReachable(channel);
        return List.of();
    }

    @Override
    public byte[] download(JsonNode channel, RemoteFile file) {
        requireReachable(channel);
        throw new TransferException("Remote file " + file.path() + " not found");
    }

    @Override
    public void upload(JsonNode channel, String fileName, byte[] content) {
        requireReachable(channel);
    }

    @Override
    public void deleteRemote(JsonNode channel, RemoteFile file) {
        requireReachable(channel);
    }

    private static void requireReachable(JsonNode channel) {
        String host = channel.path("host").asString("");
        if (host.isBlank() || unreachable(host)) {
            throw new TransferException("Host " + host + " is not reachable");
        }
    }

    private static boolean unreachable(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.endsWith(".invalid") || h.contains(".invalid/") || h.contains(".invalid:");
    }

    private static String portSuffix(JsonNode channel) {
        return channel.hasNonNull("port") ? ":" + channel.get("port").asInt() : "";
    }

    private static long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
