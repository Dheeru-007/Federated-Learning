package com.fl.app.fl.pipeline;

import java.util.Random;

public class PrivacyEngine {

    private final Random random = new Random();

    public LocalTrainer.ModelWeights applyNoise(LocalTrainer.ModelWeights weights, double epsilon) {
        double sigma = 1.0 / epsilon;
        double[] noisyWeights = new double[weights.weights().length];

        for (int i = 0; i < weights.weights().length; i++) {
            noisyWeights[i] = weights.weights()[i] + random.nextGaussian() * sigma;
        }

        double noisyBias = weights.bias() + random.nextGaussian() * sigma;

        return new LocalTrainer.ModelWeights(
            noisyWeights,
            noisyBias,
            weights.sampleCount(),
            weights.round(),
            weights.clientId()
        );
    }

    public double computeEpsilonConsumed(double epsilon) {
        return epsilon;
    }
}