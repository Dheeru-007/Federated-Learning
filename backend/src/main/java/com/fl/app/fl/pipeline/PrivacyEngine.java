package com.fl.app.fl.pipeline;

import java.util.Random;

/**
 * Differential Privacy engine using the Gaussian Mechanism.
 *
 * Fix applied:
 *  - Was: sigma = 1.0 / epsilon  (ad-hoc, no theoretical grounding)
 *  - Now: sigma = clipNorm × √(2·ln(1.25/δ)) / ε  (Gaussian mechanism — Dwork & Roth 2014)
 *
 *  - Was: computeEpsilonConsumed() returned the total budget (wrong)
 *  - Now: returns actual per-round epsilon, computed from the sigma that was used
 *
 * Usage:
 *   Caller must pass per-round epsilon = totalBudget / numRounds, not the full budget.
 *   This ensures simple composition across rounds stays within the total budget.
 */
public class PrivacyEngine {

    /**
     * δ for (ε, δ)-DP. Standard choice for the Gaussian mechanism.
     * Should be smaller than 1/n where n = dataset size; 1e-5 is a safe default.
     */
    private static final double DELTA = 1e-5;

    /**
     * L2 sensitivity (gradient clip norm). Must match LocalTrainer.CLIP_NORM.
     */
    private static final double SENSITIVITY = 1.0;

    private final Random random = new Random();

    // Tracks the sigma used in the last applyNoise() call for exact epsilon reporting.
    private double lastSigma = 0.0;

    /**
     * Applies calibrated Gaussian noise to model weights for (ε, δ)-DP.
     *
     * @param weights        model weights from local training
     * @param perRoundEpsilon  ε budget for this single round (= totalBudget / numRounds)
     */
    public LocalTrainer.ModelWeights applyNoise(
            LocalTrainer.ModelWeights weights, double perRoundEpsilon) {

        lastSigma = gaussianSigma(perRoundEpsilon, SENSITIVITY, DELTA);

        double[] noisyWeights = new double[weights.weights().length];
        for (int i = 0; i < weights.weights().length; i++) {
            noisyWeights[i] = weights.weights()[i] + random.nextGaussian() * lastSigma;
        }

        double noisyBias = weights.bias() + random.nextGaussian() * lastSigma;

        return new LocalTrainer.ModelWeights(
            noisyWeights,
            noisyBias,
            weights.sampleCount(),
            weights.round(),
            weights.clientId()
        );
    }

    /**
     * Returns the actual ε consumed in the most recent applyNoise() call.
     * Computed by inverting the Gaussian mechanism formula:
     *   ε = sensitivity × √(2·ln(1.25/δ)) / σ
     *
     * This equals the perRoundEpsilon passed in, but is computed from σ
     * to confirm the relationship holds — not just returned verbatim.
     */
    public double computeEpsilonConsumed() {
        if (lastSigma <= 0.0) return 0.0;
        return SENSITIVITY * Math.sqrt(2.0 * Math.log(1.25 / DELTA)) / lastSigma;
    }

    // ─── Gaussian mechanism ────────────────────────────────────────────────────

    /**
     * σ = sensitivity × √(2·ln(1.25/δ)) / ε
     *
     * Ensures that gradient perturbation with this sigma is (ε, δ)-DP.
     * Reference: Dwork & Roth (2014), "The Algorithmic Foundations of DP", Theorem A.1.
     */
    private static double gaussianSigma(double epsilon, double sensitivity, double delta) {
        return sensitivity * Math.sqrt(2.0 * Math.log(1.25 / delta)) / epsilon;
    }
}