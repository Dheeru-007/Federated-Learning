package com.fl.app.fl.pipeline;

public class DataNormalizer {

    public record NormalizedDataset(
        double[][] features,
        int[] labels,
        double[] min,
        double[] max
    ) {}

    public static NormalizedDataset normalize(double[][] features, int[] labels) {
        int rows = features.length;
        int featureCount = features[0].length;

        double[] min = new double[featureCount];
        double[] max = new double[featureCount];

        // Initialize min/max
        for (int j = 0; j < featureCount; j++) {
            min[j] = Double.MAX_VALUE;
            max[j] = -Double.MAX_VALUE;
        }

        // Find min and max per feature
        for (double[] row : features) {
            for (int j = 0; j < featureCount; j++) {
                if (row[j] < min[j]) min[j] = row[j];
                if (row[j] > max[j]) max[j] = row[j];
            }
        }

        // Apply min-max normalization
        double[][] normalized = new double[rows][featureCount];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < featureCount; j++) {
                double range = max[j] - min[j];
                normalized[i][j] = range == 0 ? 0.0 : (features[i][j] - min[j]) / range;
            }
        }

        return new NormalizedDataset(normalized, labels, min, max);
    }

    // Apply same normalization params to validation/test data
    public static double[][] normalizeWithParams(double[][] features, double[] min, double[] max) {
        int rows = features.length;
        int featureCount = features[0].length;
        double[][] normalized = new double[rows][featureCount];

        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < featureCount; j++) {
                double range = max[j] - min[j];
                normalized[i][j] = range == 0 ? 0.0 : (features[i][j] - min[j]) / range;
            }
        }

        return normalized;
    }
}