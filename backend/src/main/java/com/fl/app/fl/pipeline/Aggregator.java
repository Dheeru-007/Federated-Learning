package com.fl.app.fl.pipeline;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("null")
public class Aggregator {

    private static final Logger log = LoggerFactory.getLogger(Aggregator.class);

    public record AggregationResult(
            LocalTrainer.ModelWeights globalWeights,
            int acceptedClients,
            int rejectedClients,
            List<String> rejectedClientIds) {
    }

    /**
     * Verifies, then FedAvg-aggregates all client updates.
     *
     * @param securityLayer injected Spring bean — used for verify() (no longer static)
     */
    public static AggregationResult aggregate(
            List<SecurityLayer.SecuredUpdate> updates,
            int round,
            SecurityLayer securityLayer) {

        List<LocalTrainer.ModelWeights> verified       = new ArrayList<>();
        List<String>                    rejectedIds    = new ArrayList<>();

        for (SecurityLayer.SecuredUpdate update : updates) {
            try {
                LocalTrainer.ModelWeights weights = securityLayer.verify(update);
                verified.add(weights);
                log.info("[Aggregator] ✓ Client {} verified", update.clientId());
            } catch (Exception e) {
                rejectedIds.add(update.clientId());
                log.warn("[Aggregator] ✗ Client {} REJECTED — {}", update.clientId(), e.getMessage());
            }
        }

        if (verified.isEmpty()) {
            throw new IllegalStateException("No valid updates to aggregate — all clients rejected");
        }

        int    featureCount  = verified.get(0).weights().length;
        long   totalSamples  = verified.stream().mapToLong(LocalTrainer.ModelWeights::sampleCount).sum();

        double[] globalWeights = new double[featureCount];
        double   globalBias    = 0.0;

        for (LocalTrainer.ModelWeights w : verified) {
            double clientWeight = (double) w.sampleCount() / totalSamples;
            for (int i = 0; i < featureCount; i++) {
                globalWeights[i] += clientWeight * w.weights()[i];
            }
            globalBias += clientWeight * w.bias();
        }

        log.info("[Aggregator] Round {} — {}/{} clients accepted",
                round, verified.size(), updates.size());

        return new AggregationResult(
                new LocalTrainer.ModelWeights(
                        globalWeights, globalBias, (int) totalSamples, round, "Global"),
                verified.size(),
                rejectedIds.size(),
                rejectedIds);
    }
}