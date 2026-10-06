package io.meridian.regulatoryreporting.application;

/**
 * Port to the regulator's submission channel (central-bank API, SFTP drop, portal upload). Adapters live in
 * infrastructure; the default adapter is a simulator.
 */
public interface RegulatorGateway {

    /** What is transmitted. */
    record Filing(String regulator, String jurisdiction, String channel, String reportCode, String fileName,
                  String contentType, String content, String sha256) {}

    /** Synchronous transmission result: accepted for processing (with a transmission id) or refused. */
    record Receipt(boolean accepted, String transmissionId, String message) {}

    Receipt submit(Filing filing);
}
