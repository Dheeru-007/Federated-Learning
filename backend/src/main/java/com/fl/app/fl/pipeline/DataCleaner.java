package com.fl.app.fl.pipeline;

import java.util.ArrayList;
import java.util.List;

public class DataCleaner {

    public record CleanedDataset(
        double[][] features,
        int[] labels,
        int originalRows,
        int removedMissing,
        int removedDuplicates,
        int removedOutliers,
        int finalRows
    ) {}

    public static CleanedDataset clean(double[][] features, int[] labels) {
        int originalRows = features.length;
        int featureCount = features[0].length;

        List<double[]> cleanFeatures = new ArrayList<>();
        List<Integer> cleanLabels = new ArrayList<>();

        // Step 1 — Remove rows with missing values (NaN or Infinity)
        int removedMissing = 0;
        for (int i = 0; i < features.length; i++) {
            boolean valid = true;
            for (double v : features[i]) {
                if (Double.isNaN(v) || Double.isInfinite(v)) {
                    valid = false;
                    break;
                }
            }
            if (valid) {
                cleanFeatures.add(features[i]);
                cleanLabels.add(labels[i]);
            } else {
                removedMissing++;
            }
        }

        // Step 2 — Remove duplicate rows
        int removedDuplicates = 0;
        List<double[]> dedupFeatures = new ArrayList<>();
        List<Integer> dedupLabels = new ArrayList<>();

        for (int i = 0; i < cleanFeatures.size(); i++) {
            boolean isDuplicate = false;
            for (double[] existing : dedupFeatures) {
                if (java.util.Arrays.equals(existing, cleanFeatures.get(i))) {
                    isDuplicate = true;
                    break;
                }
            }
            if (!isDuplicate) {
                dedupFeatures.add(cleanFeatures.get(i));
                dedupLabels.add(cleanLabels.get(i));
            } else {
                removedDuplicates++;
            }
        }

        // Step 3 — Remove outliers (beyond 3 standard deviations per feature)
        int removedOutliers = 0;
        double[] mean = new double[featureCount];
        double[] std = new double[featureCount];

        for (double[] row : dedupFeatures) {
            for (int j = 0; j < featureCount; j++) {
                mean[j] += row[j];
            }
        }
        for (int j = 0; j < featureCount; j++) {
            mean[j] /= dedupFeatures.size();
        }

        for (double[] row : dedupFeatures) {
            for (int j = 0; j < featureCount; j++) {
                std[j] += Math.pow(row[j] - mean[j], 2);
            }
        }
        for (int j = 0; j < featureCount; j++) {
            std[j] = Math.sqrt(std[j] / dedupFeatures.size());
        }

        List<double[]> finalFeatures = new ArrayList<>();
        List<Integer> finalLabels = new ArrayList<>();

        for (int i = 0; i < dedupFeatures.size(); i++) {
            boolean isOutlier = false;
            for (int j = 0; j < featureCount; j++) {
                if (std[j] > 0 && Math.abs(dedupFeatures.get(i)[j] - mean[j]) > 3 * std[j]) {
                    isOutlier = true;
                    break;
                }
            }
            if (!isOutlier) {
                finalFeatures.add(dedupFeatures.get(i));
                finalLabels.add(dedupLabels.get(i));
            } else {
                removedOutliers++;
            }
        }

        double[][] result = finalFeatures.toArray(new double[0][]);
        int[] resultLabels = finalLabels.stream().mapToInt(i -> i).toArray();

        return new CleanedDataset(
            result, resultLabels,
            originalRows, removedMissing, removedDuplicates, removedOutliers,
            result.length
        );
    }
}