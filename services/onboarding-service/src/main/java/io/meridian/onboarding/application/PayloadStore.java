package io.meridian.onboarding.application;

import java.util.Optional;
import tools.jackson.databind.JsonNode;

/** Port: fetches and decrypts an offline batch payload from its URI; empty when the location is not reachable. */
public interface PayloadStore {
    Optional<JsonNode> load(String uri);
}
