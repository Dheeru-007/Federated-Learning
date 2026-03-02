package com.fl.model;

import java.util.List;
import java.util.Random;

/**
 * LogisticRegressionModel — Local model trained on each federated client node.
 *
 * Implements binary logistic regression with mini-batch gradient descent.
 * This is the local learning algorithm used by each hospital participant before
 * sending weight updates to the FL aggregation server.
 *
 * The model is deliberately simple and interpretable so that privacy analysis
 * of gradient updates is tractable — matching the approach in Ren et al. (2024).
 *
 * Features (127 clinical features per patient record):
 *   - Demographics: age, gender, BMI, etc.
 *   - Diagnostics: blood pressure, glucose, HbA1c, etc.
 *   - Treatment: medication codes, procedure flags, etc.
 *   - Outcomes: readmission risk (binary label)
 */
public class LogisticRegressionModel {

    private double[] weights;
    private double bias;
    private final int featureCount;
    private final double learningRate;
    private final int batchSize;
    private final Random random;

    // ── Constants matching base paper config ──────────────────────────────
    private static final double L2_REGULARIZATION = 0.001;
    private static final double GRADIENT_CLIP_NORM = 1.0;  // Sensitivity for DP

    public LogisticRegressionModel(int featureCount, double learningRate, int batchSize) {
        this.featureCount = featureCount;
        this.learningRate = learningRate;
        this.batchSize = batchSize;
        this.random = new Random(42);
        this.weights = new double[featureCount];
        this.bias = 0.0;
        initializeWeights();
    }

    /** Xavier initialization for stable convergence. */
    private void initializeWeights() {
        double scale = Math.sqrt(2.0 / featureCount);
        for (int i = 0; i < featureCount; i++) {
            weights[i] = random.nextGaussian() * scale;
        }
    }

    /** Load weights from a ModelWeights object (sent from aggregator). */
    public void loadWeights(ModelWeights mw) {
        double[] w = mw.getWeights();
        System.arraycopy(w, 0, this.weights, 0, Math.min(w.length, this.weights.length));
        this.bias = mw.getBias();
    }

    /**
     * Train for a specified number of epochs on local data.
     * Returns the updated ModelWeights for transmission to the server.
     *
     * @param features  2D array [samples][features]
     * @param labels    binary labels array [samples]
     * @param epochs    number of local training epochs (base paper: 5-10)
     * @param clientId  identifier for this client node
     * @param round     current FL round number
     * @return updated ModelWeights (before DP noise is applied)
     */
    public ModelWeights train(double[][] features, int[] labels,
                              int epochs, String clientId, int round) {
        int n = features.length;
        for (int epoch = 0; epoch < epochs; epoch++) {
            // Shuffle indices for mini-batch
            int[] indices = shuffledIndices(n);
            for (int start = 0; start < n; start += batchSize) {
                int end = Math.min(start + batchSize, n);
                updateBatch(features, labels, indices, start, end);
            }
        }
        return new ModelWeights(weights, bias, n, round, clientId, 0.0);
    }

    /** Process one mini-batch gradient update with L2 regularization and gradient clipping. */
    private void updateBatch(double[][] features, int[] labels,
                             int[] indices, int start, int end) {
        double[] gradW = new double[featureCount];
        double gradB = 0.0;
        int count = end - start;

        for (int i = start; i < end; i++) {
            int idx = indices[i];
            double pred = sigmoid(dotProduct(weights, features[idx]) + bias);
            double error = pred - labels[idx];
            for (int j = 0; j < featureCount; j++) {
                gradW[j] += error * features[idx][j];
            }
            gradB += error;
        }

        // Average gradients
        double gradNorm = 0.0;
        for (int j = 0; j < featureCount; j++) {
            gradW[j] /= count;
            gradNorm += gradW[j] * gradW[j];
        }
        gradNorm = Math.sqrt(gradNorm);

        // Gradient clipping (required for differential privacy sensitivity bound)
        if (gradNorm > GRADIENT_CLIP_NORM) {
            double scale = GRADIENT_CLIP_NORM / gradNorm;
            for (int j = 0; j < featureCount; j++) gradW[j] *= scale;
        }

        // Weight update with L2 regularization
        for (int j = 0; j < featureCount; j++) {
            weights[j] -= learningRate * (gradW[j] + L2_REGULARIZATION * weights[j]);
        }
        bias -= learningRate * (gradB / count);
    }

    /** Predict probability for a single sample. */
    public double predictProbability(double[] features) {
        return sigmoid(dotProduct(weights, features) + bias);
    }

    /** Predict binary class (0 or 1) with 0.5 threshold. */
    public int predict(double[] features) {
        return predictProbability(features) >= 0.5 ? 1 : 0;
    }

    /**
     * Evaluate accuracy on a test set.
     * @return accuracy as a fraction (0.0 to 1.0)
     */
    public double evaluate(double[][] features, int[] labels) {
        int correct = 0;
        for (int i = 0; i < features.length; i++) {
            if (predict(features[i]) == labels[i]) correct++;
        }
        return (double) correct / features.length;
    }

    /** Binary cross-entropy loss on a dataset. */
    public double computeLoss(double[][] features, int[] labels) {
        double loss = 0.0;
        double eps = 1e-10;
        for (int i = 0; i < features.length; i++) {
            double p = predictProbability(features[i]);
            p = Math.max(eps, Math.min(1 - eps, p));
            loss -= labels[i] * Math.log(p) + (1 - labels[i]) * Math.log(1 - p);
        }
        return loss / features.length;
    }

    // ── Utilities ─────────────────────────────────────────────────────────

    private static double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-Math.max(-500, Math.min(500, x))));
    }

    private static double dotProduct(double[] a, double[] b) {
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) sum += a[i] * b[i];
        return sum;
    }

    private int[] shuffledIndices(int n) {
        int[] idx = new int[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        for (int i = n - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int tmp = idx[i]; idx[i] = idx[j]; idx[j] = tmp;
        }
        return idx;
    }

    public int getFeatureCount() { return featureCount; }
    public double[] getWeights() { return weights.clone(); }
    public double getBias() { return bias; }
}
