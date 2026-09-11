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
    private static final double SENSITIVITY = 0.05;

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
            LocalTrainer.ModelWeights localWeights,
            LocalTrainer.ModelWeights globalWeights,
            double perRoundEpsilon) {

        lastSigma = gaussianSigma(perRoundEpsilon, SENSITIVITY, DELTA);

        // 1. Calculate delta = localWeights - globalWeights
        double[][] deltaW1 = new double[localWeights.W1().length][localWeights.W1()[0].length];
        double[] deltaB1 = new double[localWeights.b1().length];
        double[] deltaW2 = new double[localWeights.W2().length];
        double deltaB2 = localWeights.b2() - globalWeights.b2();

        double normSq = deltaB2 * deltaB2;

        for (int i = 0; i < localWeights.W1().length; i++) {
            for (int j = 0; j < localWeights.W1()[0].length; j++) {
                double diff = localWeights.W1()[i][j] - globalWeights.W1()[i][j];
                deltaW1[i][j] = diff;
                normSq += diff * diff;
            }
        }
        for (int j = 0; j < localWeights.b1().length; j++) {
            double diff = localWeights.b1()[j] - globalWeights.b1()[j];
            deltaB1[j] = diff;
            normSq += diff * diff;
        }
        for (int j = 0; j < localWeights.W2().length; j++) {
            double diff = localWeights.W2()[j] - globalWeights.W2()[j];
            deltaW2[j] = diff;
            normSq += diff * diff;
        }

        double norm = Math.sqrt(normSq);

        // 2. Clip delta to SENSITIVITY
        double clipMultiplier = 1.0;
        if (norm > SENSITIVITY) {
            clipMultiplier = SENSITIVITY / norm;
        }

        // 3. Add noise to clipped delta and add back to global weights
        double[][] noisyW1 = new double[localWeights.W1().length][localWeights.W1()[0].length];
        for (int i = 0; i < localWeights.W1().length; i++) {
            for (int j = 0; j < localWeights.W1()[0].length; j++) {
                double clippedDelta = deltaW1[i][j] * clipMultiplier;
                noisyW1[i][j] = globalWeights.W1()[i][j] + clippedDelta + random.nextGaussian() * lastSigma;
            }
        }

        double[] noisyB1 = new double[localWeights.b1().length];
        for (int j = 0; j < localWeights.b1().length; j++) {
            double clippedDelta = deltaB1[j] * clipMultiplier;
            noisyB1[j] = globalWeights.b1()[j] + clippedDelta + random.nextGaussian() * lastSigma;
        }

        double[] noisyW2 = new double[localWeights.W2().length];
        for (int j = 0; j < localWeights.W2().length; j++) {
            double clippedDelta = deltaW2[j] * clipMultiplier;
            noisyW2[j] = globalWeights.W2()[j] + clippedDelta + random.nextGaussian() * lastSigma;
        }

        double clippedDeltaB2 = deltaB2 * clipMultiplier;
        double noisyB2 = globalWeights.b2() + clippedDeltaB2 + random.nextGaussian() * lastSigma;

        return new LocalTrainer.ModelWeights(
            noisyW1,
            noisyB1,
            noisyW2,
            noisyB2,
            localWeights.sampleCount(),
            localWeights.round(),
            localWeights.clientId()
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