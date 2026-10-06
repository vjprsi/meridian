package io.meridian.filegateway.infrastructure;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Transport to a counterparty endpoint (SFTP, FTPS, S3, ...). Credentials are resolved from the channel's
 * secret reference by the implementation; they never pass through the API or the database.
 */
public interface ChannelConnector {

    record ConnectionResult(boolean success, long latencyMs, String remoteAddress, String message) {}

    record RemoteFile(String name, String path, long size) {}

    /** Thrown on transport failures (retryable). */
    class TransferException extends RuntimeException {
        public TransferException(String message) {
            super(message);
        }
    }

    ConnectionResult test(JsonNode channel);

    List<RemoteFile> list(JsonNode channel);

    byte[] download(JsonNode channel, RemoteFile file);

    void upload(JsonNode channel, String fileName, byte[] content);

    void deleteRemote(JsonNode channel, RemoteFile file);
}
