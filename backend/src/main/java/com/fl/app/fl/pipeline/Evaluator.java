package com.fl.app.fl.pipeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Evaluator {

    public record EvaluationResult(
        double accuracy,
        double loss,
        double precision,
        double recall,
        double specificity,
        double f1Score,
        double rocAuc,
        double prAuc,
        int tp, int tn, int fp, int fn
    ) {}

    private record Prediction(double prob, int label) implements Comparable<Prediction> {
        @Override
        public int compareTo(Prediction o) {
            // Sort descending by probability
            return Double.compare(o.prob, this.prob);
        }
    }

    public static EvaluationResult evaluate(
            LocalTrainer.ModelWeights weights,
            double[][] features,
            int[] labels) {

        LocalTrainer evaluator = new LocalTrainer(weights.W1().length);
        evaluator.loadWeights(weights);

        int tp = 0, tn = 0, fp = 0, fn = 0;
        double loss = 0.0;
        double eps = 1e-10;

        List<Prediction> preds = new ArrayList<>(features.length);
        int totalPositives = 0;
        int totalNegatives = 0;

        for (int i = 0; i < features.length; i++) {
            double p = evaluator.forwardPass(features[i]);
            int y = labels[i];
            
            preds.add(new Prediction(p, y));

            if (y == 1) totalPositives++;
            else totalNegatives++;

            // Loss computation
            double pClipped = Math.max(eps, Math.min(1 - eps, p));
            loss -= y * Math.log(pClipped) + (1 - y) * Math.log(1 - pClipped);

            // Confusion Matrix (threshold = 0.5)
            int yPred = p >= 0.5 ? 1 : 0;
            if (y == 1 && yPred == 1) tp++;
            else if (y == 0 && yPred == 0) tn++;
            else if (y == 0 && yPred == 1) fp++;
            else fn++;
        }

        loss /= features.length;

        double accuracy = (double) (tp + tn) / features.length;
        double precision = (tp + fp) == 0 ? 0.0 : (double) tp / (tp + fp);
        double recall = (tp + fn) == 0 ? 0.0 : (double) tp / (tp + fn);
        double specificity = (tn + fp) == 0 ? 0.0 : (double) tn / (tn + fp);
        double f1Score = (precision + recall) == 0 ? 0.0 : 2 * (precision * recall) / (precision + recall);

        // Sort predictions descending
        Collections.sort(preds);

        double rocAuc = computeRocAuc(preds, totalPositives, totalNegatives);
        double prAuc = computePrAuc(preds, totalPositives);

        return new EvaluationResult(
            accuracy, loss, precision, recall, specificity, f1Score, rocAuc, prAuc, tp, tn, fp, fn
        );
    }

    private static double computeRocAuc(List<Prediction> preds, int totalPos, int totalNeg) {
        if (totalPos == 0 || totalNeg == 0) return 0.0;

        double auc = 0.0;
        double prevTpr = 0.0;
        double prevFpr = 0.0;

        int tp = 0;
        int fp = 0;

        for (Prediction p : preds) {
            if (p.label == 1) tp++;
            else fp++;

            double tpr = (double) tp / totalPos;
            double fpr = (double) fp / totalNeg;

            auc += trapezoidArea(fpr, prevFpr, tpr, prevTpr);

            prevTpr = tpr;
            prevFpr = fpr;
        }
        return auc;
    }

    private static double computePrAuc(List<Prediction> preds, int totalPos) {
        if (totalPos == 0) return 0.0;

        double auc = 0.0;
        double prevRecall = 0.0;
        double prevPrecision = 1.0; 

        int tp = 0;
        int fp = 0;

        for (Prediction p : preds) {
            if (p.label == 1) tp++;
            else fp++;

            double recall = (double) tp / totalPos;
            double precision = (double) tp / (tp + fp);

            auc += trapezoidArea(recall, prevRecall, precision, prevPrecision);

            prevRecall = recall;
            prevPrecision = precision;
        }
        return auc;
    }

    private static double trapezoidArea(double x1, double x2, double y1, double y2) {
        return Math.abs(x1 - x2) * ((y1 + y2) / 2.0);
    }
}