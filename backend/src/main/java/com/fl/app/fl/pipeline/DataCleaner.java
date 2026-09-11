package com.fl.app.fl.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class DataCleaner {

    private static final Logger log = LoggerFactory.getLogger(DataCleaner.class);

    public record CleanedDataset(
        double[][] features,
        int[] labels,
        int originalRows,
        int removedMissing,
        int removedDuplicates,
        int featuresWinsorized,
        int finalRows
    ) {}

    public static CleanedDataset clean(double[][] features, int[] labels) {
        int originalRows = features.length;
        if (originalRows == 0) {
            return new CleanedDataset(features, labels, 0, 0, 0, 0, 0);
        }
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

        // Step 2 — Remove duplicate rows (Comparing both FEATURES and LABEL)
        int removedDuplicates = 0;
        List<double[]> dedupFeatures = new ArrayList<>();
        List<Integer> dedupLabels = new ArrayList<>();

        for (int i = 0; i < cleanFeatures.size(); i++) {
            boolean isDuplicate = false;
            double[] currentFeatures = cleanFeatures.get(i);
            int currentLabel = cleanLabels.get(i);

            for (int j = 0; j < dedupFeatures.size(); j++) {
                if (dedupLabels.get(j) == currentLabel && Arrays.equals(dedupFeatures.get(j), currentFeatures)) {
                    isDuplicate = true;
                    break;
                }
            }

            if (!isDuplicate) {
                dedupFeatures.add(currentFeatures);
                dedupLabels.add(currentLabel);
            } else {
                removedDuplicates++;
            }
        }

        // Step 3 — Winsorization (Clipping Outliers instead of deleting)
        int featuresWinsorized = 0;
        if (!dedupFeatures.isEmpty()) {
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

            for (double[] row : dedupFeatures) {
                for (int j = 0; j < featureCount; j++) {
                    if (std[j] > 0) {
                        double zScore = (row[j] - mean[j]) / std[j];
                        if (zScore > 3.0) {
                            row[j] = mean[j] + 3.0 * std[j]; // Clip to +3 std
                            featuresWinsorized++;
                        } else if (zScore < -3.0) {
                            row[j] = mean[j] - 3.0 * std[j]; // Clip to -3 std
                            featuresWinsorized++;
                        }
                    }
                }
            }
        }

        double[][] result = dedupFeatures.toArray(new double[0][]);
        int[] resultLabels = dedupLabels.stream().mapToInt(i -> i).toArray();

        log.info("DataCleaning complete: Original={}, RemovedMissing={}, RemovedDuplicates={}, FeaturesWinsorized={}, Final={}", 
                originalRows, removedMissing, removedDuplicates, featuresWinsorized, result.length);

        return new CleanedDataset(
            result, resultLabels,
            originalRows, removedMissing, removedDuplicates, featuresWinsorized,
            result.length
        );
    }
}