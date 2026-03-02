package com.fl.model;

import java.io.Serializable;
import java.util.Arrays;

/**
 * ModelWeights — Serializable container for logistic regression parameters.
 *
 * Represents the model state exchanged between FL clients and the aggregator server.
 * Contains weight vector (one per feature) and bias term.
 * All exchanges of this object are encrypted via SecureCommunication before transmission.
 *
 * Based on: Ren et al. (2024) — gradient parameter exchange protocol.
 */
public class ModelWeights implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Weight vector: one coefficient per input feature (127 for healthcare dataset). */
    private double[] weights;

    /** Bias / intercept term. */
    private double bias;

    /** Number of training samples this update was derived from (used in FedAvg). */
    private int sampleCount;

    /** Round number this weight update was produced in. */
    private int roundNumber;

    /** Client ID that produced this update (for Byzantine fault tracking). */
    private String clientId;

    /** Privacy budget consumed producing this update (epsilon portion). */
    private double epsilonConsumed;

    // ── Constructors ──────────────────────────────────────────────────────

    public ModelWeights(int featureCount) {
        this.weights = new double[featureCount];
        this.bias = 0.0;
    }

    public ModelWeights(double[] weights, double bias, int sampleCount,
                        int roundNumber, String clientId, double epsilonConsumed) {
        this.weights = Arrays.copyOf(weights, weights.length);
        this.bias = bias;
        this.sampleCount = sampleCount;
        this.roundNumber = roundNumber;
        this.clientId = clientId;
        this.epsilonConsumed = epsilonConsumed;
    }

    /** Deep-copy constructor. */
    public ModelWeights(ModelWeights other) {
        this.weights = Arrays.copyOf(other.weights, other.weights.length);
        this.bias = other.bias;
        this.sampleCount = other.sampleCount;
        this.roundNumber = other.roundNumber;
        this.clientId = other.clientId;
        this.epsilonConsumed = other.epsilonConsumed;
    }

    // ── Arithmetic helpers (used by FedAvg) ──────────────────────────────

    /** Scale all weights and bias by a scalar factor. */
    public ModelWeights scale(double factor) {
        double[] scaled = new double[weights.length];
        for (int i = 0; i < weights.length; i++) {
            scaled[i] = weights[i] * factor;
        }
        return new ModelWeights(scaled, bias * factor,
                sampleCount, roundNumber, clientId, epsilonConsumed);
    }

    /** Add another ModelWeights to this one (element-wise). */
    public ModelWeights add(ModelWeights other) {
        if (other.weights.length != this.weights.length) {
            throw new IllegalArgumentException(
                "Weight dimension mismatch: " + this.weights.length + " vs " + other.weights.length);
        }
        double[] sum = new double[weights.length];
        for (int i = 0; i < weights.length; i++) {
            sum[i] = this.weights[i] + other.weights[i];
        }
        return new ModelWeights(sum, this.bias + other.bias,
                this.sampleCount + other.sampleCount, this.roundNumber, "aggregated", 0.0);
    }

    /** Compute L2 norm of weight vector (used for gradient clipping). */
    public double l2Norm() {
        double sum = 0.0;
        for (double w : weights) sum += w * w;
        return Math.sqrt(sum);
    }

    /** Clip weights to a maximum L2 norm (differential privacy sensitivity control). */
    public ModelWeights clipToNorm(double maxNorm) {
        double norm = l2Norm();
        if (norm <= maxNorm) return new ModelWeights(this);
        double scale = maxNorm / norm;
        return this.scale(scale);
    }

    // ── Getters / Setters ─────────────────────────────────────────────────

    public double[] getWeights() { return Arrays.copyOf(weights, weights.length); }
    public void setWeights(double[] weights) { this.weights = Arrays.copyOf(weights, weights.length); }
    public double getBias() { return bias; }
    public void setBias(double bias) { this.bias = bias; }
    public int getSampleCount() { return sampleCount; }
    public void setSampleCount(int sampleCount) { this.sampleCount = sampleCount; }
    public int getRoundNumber() { return roundNumber; }
    public void setRoundNumber(int roundNumber) { this.roundNumber = roundNumber; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public double getEpsilonConsumed() { return epsilonConsumed; }
    public void setEpsilonConsumed(double e) { this.epsilonConsumed = e; }
    public int getFeatureCount() { return weights.length; }

    @Override
    public String toString() {
        return String.format("ModelWeights[client=%s, round=%d, features=%d, bias=%.4f, " +
                "samples=%d, epsilon=%.4f]",
                clientId, roundNumber, weights.length, bias, sampleCount, epsilonConsumed);
    }
}
