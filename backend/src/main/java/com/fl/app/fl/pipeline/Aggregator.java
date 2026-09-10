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

        int f = verified.get(0).W1().length;
        int h = verified.get(0).W1()[0].length;
        long totalSamples = verified.stream().mapToLong(LocalTrainer.ModelWeights::sampleCount).sum();

        double[][] globalW1 = new double[f][h];
        double[] globalB1 = new double[h];
        double[] globalW2 = new double[h];
        double globalB2 = 0.0;

        for (LocalTrainer.ModelWeights w : verified) {
            double clientWeight = (double) w.sampleCount() / totalSamples;
            
            for (int i = 0; i < f; i++) {
                for (int j = 0; j < h; j++) {
                    globalW1[i][j] += clientWeight * w.W1()[i][j];
                }
            }
            
            for (int j = 0; j < h; j++) {
                globalB1[j] += clientWeight * w.b1()[j];
                globalW2[j] += clientWeight * w.W2()[j];
            }
            
            globalB2 += clientWeight * w.b2();
        }

        log.info("[Aggregator] Round {} — {}/{} clients accepted",
                round, verified.size(), updates.size());

        return new AggregationResult(
                new LocalTrainer.ModelWeights(
                        globalW1, globalB1, globalW2, globalB2, (int) totalSamples, round, "Global"),
                verified.size(),
                rejectedIds.size(),
                rejectedIds);
    }
}