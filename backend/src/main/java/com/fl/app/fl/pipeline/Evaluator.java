package com.fl.app.fl.pipeline;

public class Evaluator {

    public record EvaluationResult(
        double accuracy,
        double loss
    ) {}

    public static EvaluationResult evaluate(
            LocalTrainer.ModelWeights globalWeights,
            double[][] valFeatures,
            int[] valLabels) {

        LocalTrainer evaluator = new LocalTrainer(globalWeights.weights().length);
        evaluator.loadWeights(globalWeights);

        double accuracy = evaluator.evaluate(valFeatures, valLabels);
        double loss = evaluator.computeLoss(valFeatures, valLabels);

        System.out.printf("[Evaluator] Accuracy: %.4f | Loss: %.4f%n", accuracy, loss);

        return new EvaluationResult(accuracy, loss);
    }
}