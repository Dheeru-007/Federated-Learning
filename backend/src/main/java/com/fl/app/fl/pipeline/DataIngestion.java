package com.fl.app.fl.pipeline;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

public class DataIngestion {

    public record ParsedDataset(
        double[][] features,
        int[] labels,
        int featureCount,
        int rowCount,
        String extractedHeaders // Comma-separated list of all headers (features + label)
    ) {}

    public static ParsedDataset parse(String csvContent) {
        List<double[]> featureList = new ArrayList<>();
        List<Integer> labelList = new ArrayList<>();
        
        CSVFormat format = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setTrim(true)
            .setIgnoreEmptyLines(true)
            .build();

        List<String> headerNames;
        int featureCount = -1;

        try (CSVParser parser = CSVParser.parse(new StringReader(csvContent), format)) {
            headerNames = parser.getHeaderNames();
            
            if (headerNames == null || headerNames.isEmpty()) {
                throw new IllegalArgumentException("CSV file must contain a header row.");
            }
            
            featureCount = headerNames.size() - 1;
            if (featureCount <= 0) {
                throw new IllegalArgumentException("CSV file must contain at least one feature and one label column.");
            }

            for (CSVRecord record : parser) {
                if (record.size() != headerNames.size()) {
                    throw new IllegalArgumentException("Row " + record.getRecordNumber() + " has " + record.size() + " columns, expected " + headerNames.size());
                }

                double[] row = new double[featureCount];
                for (int i = 0; i < featureCount; i++) {
                    try {
                        row[i] = Double.parseDouble(record.get(i).trim());
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("Invalid numeric value '" + record.get(i) + "' in column '" + headerNames.get(i) + "' at row " + record.getRecordNumber());
                    }
                }

                int label;
                try {
                    label = (int) Double.parseDouble(record.get(featureCount).trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid label value '" + record.get(featureCount) + "' at row " + record.getRecordNumber());
                }

                if (label != 0 && label != 1) {
                    throw new IllegalArgumentException("Label must be 0 or 1, got: " + label + " at row " + record.getRecordNumber());
                }

                featureList.add(row);
                labelList.add(label);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("CSV parsing failed: " + e.getMessage(), e);
        }

        if (featureList.isEmpty()) {
            throw new IllegalArgumentException("CSV has no data rows");
        }

        double[][] features = featureList.toArray(new double[0][]);
        int[] labels = labelList.stream().mapToInt(i -> i).toArray();
        String extractedHeaders = String.join(",", headerNames);

        return new ParsedDataset(features, labels, featureCount, features.length, extractedHeaders);
    }
}