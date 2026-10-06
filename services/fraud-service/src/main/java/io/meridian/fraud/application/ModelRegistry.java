package io.meridian.fraud.application;

import io.meridian.fraud.domain.model.FraudModel;
import io.meridian.fraud.domain.model.LogisticRegressionModel;
import java.util.LinkedHashMap;
import org.springframework.beans.factory.ObjectProvider;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Fraud models available to decision policies, keyed by id. Additional models plug in as Spring beans implementing
 * {@link FraudModel}; a policy without (or with an unknown) model id uses the built-in champion.
 */
@Component
public class ModelRegistry {
    private final Map<UUID, FraudModel> models = new LinkedHashMap<>();
    private final FraudModel champion;
    private final FraudModel challenger;

    public ModelRegistry(ObjectProvider<FraudModel> extra) {
        champion = LogisticRegressionModel.champion();
        challenger = LogisticRegressionModel.challenger();
        models.put(champion.id(), champion);
        models.put(challenger.id(), challenger);
        extra.orderedStream().forEach(m -> models.put(m.id(), m));
    }

    public FraudModel champion(UUID id) {
        return id == null ? champion : models.getOrDefault(id, champion);
    }

    /** Shadow model of a policy: the configured one, else the built-in challenger. */
    public FraudModel shadow(UUID id) {
        return id == null ? challenger : models.getOrDefault(id, challenger);
    }

    public FraudModel byId(UUID id) {
        return models.get(id);
    }
}
