package com.fl.app.fl.pipeline;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

public class DataIngestion {

    public record ParsedDataset(
        double[][] features,
        int[] labels,
        int featureCount,
        int rowCount
    ) {}

    public static ParsedDataset parse(String csvContent) {
        List<double[]> featureList = new ArrayList<>();
        List<Integer> labelList = new ArrayList<>();
        int featureCount = -1;

        try (BufferedReader reader = new BufferedReader(new StringReader(csvContent))) {
            String line;
            boolean firstLine = true;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                // Skip header row
                if (firstLine) {
                    firstLine = false;
                    continue;
                }

                String[] parts = line.split(",");

                // Last column is label
                int numFeatures = parts.length - 1;

                if (featureCount == -1) {
                    featureCount = numFeatures;
                } else if (numFeatures != featureCount) {
                    throw new IllegalArgumentException(
                        "Row has " + numFeatures + " features, expected " + featureCount
                    );
                }

                double[] row = new double[numFeatures];
                for (int i = 0; i < numFeatures; i++) {
                    row[i] = Double.parseDouble(parts[i].trim());
                }

                int label = (int) Double.parseDouble(parts[parts.length - 1].trim());
                if (label != 0 && label != 1) {
                    throw new IllegalArgumentException("Label must be 0 or 1, got: " + label);
                }

                featureList.add(row);
                labelList.add(label);
            }
        } catch (Exception e) {
            throw new RuntimeException("CSV parsing failed: " + e.getMessage(), e);
        }

        if (featureList.isEmpty()) {
            throw new IllegalArgumentException("CSV has no data rows");
        }

        double[][] features = featureList.toArray(new double[0][]);
        int[] labels = labelList.stream().mapToInt(i -> i).toArray();

        return new ParsedDataset(features, labels, featureCount, features.length);
    }
}