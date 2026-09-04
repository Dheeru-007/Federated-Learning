package com.fl.app.fl.pipeline;

import java.util.ArrayList;
import java.util.List;

public class Aggregator {

    public record AggregationResult(
            LocalTrainer.ModelWeights globalWeights,
            int acceptedClients,
            int rejectedClients,
            List<String> rejectedClientIds) {
    }

    public static AggregationResult aggregate(
            List<SecurityLayer.SecuredUpdate> updates,
            int round) {

        List<LocalTrainer.ModelWeights> verified = new ArrayList<>();
        List<String> rejectedClientIds = new ArrayList<>();

        for (SecurityLayer.SecuredUpdate update : updates) {
            try {
                LocalTrainer.ModelWeights weights = SecurityLayer.verify(update);
                verified.add(weights);
                System.out.println("[Aggregator] ✓ Client " + update.clientId() + " verified");
            } catch (Exception e) {
                rejectedClientIds.add(update.clientId());
                System.out.println("[Aggregator] ✗ Client " + update.clientId() + " REJECTED — " + e.getMessage());
            }
        }

        if (verified.isEmpty()) {
            throw new IllegalStateException("No valid updates to aggregate — all clients rejected");
        }

        int featureCount = verified.get(0).weights().length;
        long totalSamples = verified.stream().mapToLong(LocalTrainer.ModelWeights::sampleCount).sum();

        double[] globalWeights = new double[featureCount];
        double globalBias = 0.0;

        for (LocalTrainer.ModelWeights w : verified) {
            double clientWeight = (double) w.sampleCount() / totalSamples;
            for (int i = 0; i < featureCount; i++) {
                globalWeights[i] += clientWeight * w.weights()[i];
            }
            globalBias += clientWeight * w.bias();
        }

        System.out.printf("[Aggregator] Round %d — %d/%d clients accepted%n",
                round, verified.size(), updates.size());

        return new AggregationResult(
                new LocalTrainer.ModelWeights(globalWeights, globalBias, (int) totalSamples, round, "Global"),
                verified.size(),
                rejectedClientIds.size(),
                rejectedClientIds);
    }
}