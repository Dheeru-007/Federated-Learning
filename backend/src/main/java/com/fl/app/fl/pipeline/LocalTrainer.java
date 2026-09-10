package com.fl.app.fl.pipeline;

import java.util.Random;

public class LocalTrainer {

    public record ModelWeights(
        double[][] W1,
        double[] b1,
        double[] W2,
        double b2,
        int sampleCount,
        int round,
        String clientId
    ) {}

    private final int hiddenSize = 16;
    private final int featureCount;
    private final double learningRate;
    private final int batchSize;
    
    private double[][] W1;
    private double[] b1;
    private double[] W2;
    private double b2;

    private static final double L2 = 0.001;
    private static final double CLIP_NORM = 1.0;
    private final Random random = new Random(42);

    public LocalTrainer(int featureCount) {
        this.featureCount = featureCount;
        this.learningRate = 0.05;
        this.batchSize = 32;
        
        this.W1 = new double[featureCount][hiddenSize];
        this.b1 = new double[hiddenSize];
        this.W2 = new double[hiddenSize];
        this.b2 = 0.0;
        initWeights();
    }

    private void initWeights() {
        double scale1 = Math.sqrt(2.0 / featureCount);
        for (int i = 0; i < featureCount; i++) {
            for (int j = 0; j < hiddenSize; j++) {
                W1[i][j] = random.nextGaussian() * scale1;
            }
        }
        double scale2 = Math.sqrt(2.0 / hiddenSize);
        for (int j = 0; j < hiddenSize; j++) {
            W2[j] = random.nextGaussian() * scale2;
        }
    }

    public void loadWeights(ModelWeights mw) {
        for (int i = 0; i < featureCount; i++) {
            System.arraycopy(mw.W1()[i], 0, this.W1[i], 0, hiddenSize);
        }
        System.arraycopy(mw.b1(), 0, this.b1, 0, hiddenSize);
        System.arraycopy(mw.W2(), 0, this.W2, 0, hiddenSize);
        this.b2 = mw.b2();
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
        
        double[][] W1_clone = new double[featureCount][hiddenSize];
        for (int i = 0; i < featureCount; i++) W1_clone[i] = W1[i].clone();
        
        return new ModelWeights(W1_clone, b1.clone(), W2.clone(), b2, n, round, clientId);
    }

    private void updateBatch(double[][] features, int[] labels,
                              int[] indices, int start, int end) {
        double[][] gradW1 = new double[featureCount][hiddenSize];
        double[] gradB1 = new double[hiddenSize];
        double[] gradW2 = new double[hiddenSize];
        double gradB2 = 0.0;
        int count = end - start;

        for (int i = start; i < end; i++) {
            int idx = indices[i];
            double[] x = features[idx];
            
            // Forward
            double[] z1 = new double[hiddenSize];
            double[] a1 = new double[hiddenSize];
            for (int j = 0; j < hiddenSize; j++) {
                double sum = b1[j];
                for (int f = 0; f < featureCount; f++) sum += x[f] * W1[f][j];
                z1[j] = sum;
                a1[j] = Math.max(0, sum); // ReLU
            }
            
            double z2 = b2;
            for (int j = 0; j < hiddenSize; j++) z2 += a1[j] * W2[j];
            double a2 = sigmoid(z2);
            
            // Backward
            double error = a2 - labels[idx]; // Derivative of Binary Cross Entropy + Sigmoid
            gradB2 += error;
            for (int j = 0; j < hiddenSize; j++) {
                gradW2[j] += error * a1[j];
                double dz1 = error * W2[j] * (z1[j] > 0 ? 1 : 0); // ReLU derivative
                gradB1[j] += dz1;
                for (int f = 0; f < featureCount; f++) {
                    gradW1[f][j] += dz1 * x[f];
                }
            }
        }

        // Apply gradients with clipping
        double gradNorm = 0.0;
        for (int j = 0; j < hiddenSize; j++) {
            gradNorm += Math.pow(gradW2[j] / count, 2);
            gradNorm += Math.pow(gradB1[j] / count, 2);
            for (int f = 0; f < featureCount; f++) {
                gradNorm += Math.pow(gradW1[f][j] / count, 2);
            }
        }
        gradNorm += Math.pow(gradB2 / count, 2);
        gradNorm = Math.sqrt(gradNorm);

        double clipMultiplier = 1.0;
        if (gradNorm > CLIP_NORM) {
            clipMultiplier = CLIP_NORM / gradNorm;
        }

        for (int j = 0; j < hiddenSize; j++) {
            gradW2[j] /= count;
            W2[j] -= learningRate * ((gradW2[j] * clipMultiplier) + L2 * W2[j]);
            
            gradB1[j] /= count;
            b1[j] -= learningRate * (gradB1[j] * clipMultiplier);
            
            for (int f = 0; f < featureCount; f++) {
                gradW1[f][j] /= count;
                W1[f][j] -= learningRate * ((gradW1[f][j] * clipMultiplier) + L2 * W1[f][j]);
            }
        }
        b2 -= learningRate * ((gradB2 / count) * clipMultiplier);
    }

    public double evaluate(double[][] features, int[] labels) {
        int correct = 0;
        for (int i = 0; i < features.length; i++) {
            int pred = forwardPass(features[i]) >= 0.5 ? 1 : 0;
            if (pred == labels[i]) correct++;
        }
        return (double) correct / features.length;
    }

    public double computeLoss(double[][] features, int[] labels) {
        double loss = 0.0;
        double eps = 1e-10;
        for (int i = 0; i < features.length; i++) {
            double p = forwardPass(features[i]);
            p = Math.max(eps, Math.min(1 - eps, p));
            loss -= labels[i] * Math.log(p) + (1 - labels[i]) * Math.log(1 - p);
        }
        return loss / features.length;
    }
    
    private double forwardPass(double[] x) {
        double[] a1 = new double[hiddenSize];
        for (int j = 0; j < hiddenSize; j++) {
            double sum = b1[j];
            for (int f = 0; f < featureCount; f++) sum += x[f] * W1[f][j];
            a1[j] = Math.max(0, sum);
        }
        double z2 = b2;
        for (int j = 0; j < hiddenSize; j++) z2 += a1[j] * W2[j];
        return sigmoid(z2);
    }

    private static double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-Math.max(-500, Math.min(500, x))));
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