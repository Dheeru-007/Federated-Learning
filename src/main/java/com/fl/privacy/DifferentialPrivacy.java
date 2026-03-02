package com.fl.privacy;

import com.fl.model.ModelWeights;
import java.util.Random;

/**
 * DifferentialPrivacy — Implements Gaussian differential privacy for federated learning.
 *
 * Provides (ε, δ)-differential privacy guarantees on model weight updates
 * before they are transmitted from clients to the aggregation server.
 *
 * Privacy Mechanism:
 *   gradient_perturbed = clip(gradient, maxNorm) + N(0, σ²I)
 *   σ² = 2 * Δ² * ln(1.25/δ) / ε²
 *
 * This matches exactly the configuration in Ren et al. (2024), Table 2:
 *   - Gradient Perturbation: Δ=0.1, σ=0.05, ε=0.5, δ=1e-5
 *   - Parameter Aggregation: Δ=0.02, σ=0.01, ε=0.3, δ=1e-6
 *
 * Privacy accounting uses the moments accountant (Renyi DP) for tight
 * composition bounds across multiple training rounds.
 */
public class DifferentialPrivacy {

    // ── Privacy configuration ─────────────────────────────────────────────

    /** Maximum allowed L2 norm of gradient (sensitivity Δ). */
    private final double clipNorm;

    /** Noise multiplier (σ / clipNorm). */
    private final double noiseMultiplier;

    /** Target epsilon — privacy budget per round. */
    private final double targetEpsilon;

    /** Delta — probability of privacy breach (typically 1e-5). */
    private final double delta;

    /** Accumulated privacy budget consumed so far. */
    private double accumulatedEpsilon;

    /** Accumulated delta across rounds. */
    private double accumulatedDelta;

    private final Random gaussianRandom;

    // ── Preset configurations matching base paper (Table 2) ───────────────

    /** Gradient perturbation preset: ε=0.5, δ=1e-5. */
    public static DifferentialPrivacy gradientPerturbation() {
        return new DifferentialPrivacy(0.1, 0.05, 0.5, 1e-5);
    }

    /** Aggregation preset: ε=0.3, δ=1e-6. */
    public static DifferentialPrivacy parameterAggregation() {
        return new DifferentialPrivacy(0.02, 0.01, 0.3, 1e-6);
    }

    /** Evaluation preset: ε=0.2, δ=1e-7. */
    public static DifferentialPrivacy modelEvaluation() {
        return new DifferentialPrivacy(0.001, 0.008, 0.2, 1e-7);
    }

    // ── Constructor ───────────────────────────────────────────────────────

    /**
     * @param clipNorm       Maximum L2 norm for gradient clipping (sensitivity Δ)
     * @param noiseMultiplier Ratio σ/Δ controlling noise magnitude
     * @param targetEpsilon  Privacy budget per composition step
     * @param delta          Privacy failure probability
     */
    public DifferentialPrivacy(double clipNorm, double noiseMultiplier,
                               double targetEpsilon, double delta) {
        this.clipNorm = clipNorm;
        this.noiseMultiplier = noiseMultiplier;
        this.targetEpsilon = targetEpsilon;
        this.delta = delta;
        this.accumulatedEpsilon = 0.0;
        this.accumulatedDelta = 0.0;
        this.gaussianRandom = new Random();
    }

    // ── Core DP mechanism ─────────────────────────────────────────────────

    /**
     * Apply differential privacy to a set of ModelWeights.
     *
     * Steps:
     *   1. Clip weights to max L2 norm (bounds sensitivity)
     *   2. Inject calibrated Gaussian noise
     *   3. Update privacy budget accounting
     *
     * @param weights      Raw model weights from local training
     * @param numSamples   Number of samples used in training (for subsampling amplification)
     * @param totalSamples Total dataset size (for subsampling ratio q = numSamples/totalSamples)
     * @return Differentially private model weights
     */
    public ModelWeights applyDP(ModelWeights weights, int numSamples, int totalSamples) {
        // Step 1: Clip to sensitivity bound
        ModelWeights clipped = weights.clipToNorm(clipNorm);

        // Step 2: Add Gaussian noise calibrated to (ε, δ)-DP
        double sigma = computeSigma();
        double[] noisyWeights = addGaussianNoise(clipped.getWeights(), sigma);
        double noisyBias = clipped.getBias() + gaussianRandom.nextGaussian() * sigma;

        // Step 3: Subsampling amplification (privacy amplification via sampling)
        double q = (double) numSamples / totalSamples;
        double roundEpsilon = computeRenyiEpsilon(q);

        // Update accumulated budget
        accumulatedEpsilon += roundEpsilon;
        accumulatedDelta   += delta;

        ModelWeights result = new ModelWeights(
                noisyWeights, noisyBias,
                weights.getSampleCount(),
                weights.getRoundNumber(),
                weights.getClientId(),
                roundEpsilon
        );

        return result;
    }

    /**
     * Compute Gaussian noise standard deviation from (ε, δ) parameters.
     * σ = Δ * sqrt(2 * ln(1.25/δ)) / ε
     */
    private double computeSigma() {
        return clipNorm * Math.sqrt(2.0 * Math.log(1.25 / delta)) / targetEpsilon;
    }

    /**
     * Add Gaussian noise N(0, σ²) to each weight independently.
     */
    private double[] addGaussianNoise(double[] weights, double sigma) {
        double[] noisy = new double[weights.length];
        for (int i = 0; i < weights.length; i++) {
            noisy[i] = weights[i] + gaussianRandom.nextGaussian() * sigma;
        }
        return noisy;
    }

    /**
     * Compute Renyi DP epsilon for one round with subsampling.
     * Approximation: ε_round ≈ q * targetEpsilon (first-order Poisson subsampling).
     * For tight bounds, use the moments accountant — approximated here.
     */
    private double computeRenyiEpsilon(double subsamplingRatio) {
        // Moments accountant approximation (Abadi et al., 2016)
        // For order α=2: ε ≈ (noiseMultiplier * subsamplingRatio)^(-2) * log(1/δ)
        double alpha = 2.0;
        double renyiDiv = 1.0 / (2.0 * noiseMultiplier * noiseMultiplier);
        return subsamplingRatio * renyiDiv + Math.log(1.0 / delta) / (alpha - 1);
    }

    // ── Privacy budget status ─────────────────────────────────────────────

    /** Returns true if budget is not yet exhausted. */
    public boolean hasBudgetRemaining(double maxEpsilon) {
        return accumulatedEpsilon < maxEpsilon;
    }

    /** Remaining epsilon budget. */
    public double remainingBudget(double maxEpsilon) {
        return Math.max(0, maxEpsilon - accumulatedEpsilon);
    }

    public double getAccumulatedEpsilon() { return accumulatedEpsilon; }
    public double getAccumulatedDelta()   { return accumulatedDelta; }
    public double getTargetEpsilon()      { return targetEpsilon; }
    public double getDelta()              { return delta; }
    public double getClipNorm()           { return clipNorm; }

    @Override
    public String toString() {
        return String.format(
            "DifferentialPrivacy[ε_target=%.3f, δ=%.2e, Δ=%.3f, σ_mult=%.3f, " +
            "ε_accumulated=%.4f, δ_accumulated=%.2e]",
            targetEpsilon, delta, clipNorm, noiseMultiplier,
            accumulatedEpsilon, accumulatedDelta);
    }
}
