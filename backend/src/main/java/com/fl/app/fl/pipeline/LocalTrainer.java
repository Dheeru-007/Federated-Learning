package com.fl.app.fl.pipeline;

import java.util.Random;

public class LocalTrainer {

    public record ModelWeights(
        double[] weights,
        double bias,
        int sampleCount,
        int round,
        String clientId
    ) {}

    private final int featureCount;
    private final double learningRate;
    private final int batchSize;
    private double[] weights;
    private double bias;
    private static final double L2 = 0.001;
    private static final double CLIP_NORM = 1.0;
    private final Random random = new Random(42);

    public LocalTrainer(int featureCount) {
        this.featureCount = featureCount;
        this.learningRate = 0.01;
        this.batchSize = 32;
        this.weights = new double[featureCount];
        this.bias = 0.0;
        initWeights();
    }

    private void initWeights() {
        double scale = Math.sqrt(2.0 / featureCount);
        for (int i = 0; i < featureCount; i++) {
            weights[i] = random.nextGaussian() * scale;
        }
    }

    public void loadWeights(ModelWeights mw) {
        System.arraycopy(mw.weights(), 0, this.weights, 0,
                Math.min(mw.weights().length, this.weights.length));
        this.bias = mw.bias();
    }

    public ModelWeights train(double[][] features, int[] labels,
                               int epochs, String clientId, int round) {
        int n = features.length;
        for (int epoch = 0; epoch < epochs; epoch++) {
            int[] indices = shuffledIndices(n);
            for (int start = 0; start < n; start += batchSize) {
                int end = Math.min(start + batchSize, n);
                updateBatch(features, labels, indices, start, end);
            }
        }
        return new ModelWeights(weights.clone(), bias, n, round, clientId);
    }

    private void updateBatch(double[][] features, int[] labels,
                              int[] indices, int start, int end) {
        double[] gradW = new double[featureCount];
        double gradB = 0.0;
        int count = end - start;

        for (int i = start; i < end; i++) {
            int idx = indices[i];
            double pred = sigmoid(dot(weights, features[idx]) + bias);
            double error = pred - labels[idx];
            for (int j = 0; j < featureCount; j++) {
                gradW[j] += error * features[idx][j];
            }
            gradB += error;
        }

        double norm = 0.0;
        for (int j = 0; j < featureCount; j++) {
            gradW[j] /= count;
            norm += gradW[j] * gradW[j];
        }
        norm = Math.sqrt(norm);

        if (norm > CLIP_NORM) {
            double scale = CLIP_NORM / norm;
            for (int j = 0; j < featureCount; j++) gradW[j] *= scale;
        }

        for (int j = 0; j < featureCount; j++) {
            weights[j] -= learningRate * (gradW[j] + L2 * weights[j]);
        }
        bias -= learningRate * (gradB / count);
    }

    public double evaluate(double[][] features, int[] labels) {
        int correct = 0;
        for (int i = 0; i < features.length; i++) {
            int pred = sigmoid(dot(weights, features[i]) + bias) >= 0.5 ? 1 : 0;
            if (pred == labels[i]) correct++;
        }
        return (double) correct / features.length;
    }

    public double computeLoss(double[][] features, int[] labels) {
        double loss = 0.0;
        double eps = 1e-10;
        for (int i = 0; i < features.length; i++) {
            double p = sigmoid(dot(weights, features[i]) + bias);
            p = Math.max(eps, Math.min(1 - eps, p));
            loss -= labels[i] * Math.log(p) + (1 - labels[i]) * Math.log(1 - p);
        }
        return loss / features.length;
    }

    private static double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-Math.max(-500, Math.min(500, x))));
    }

    private static double dot(double[] a, double[] b) {
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
}