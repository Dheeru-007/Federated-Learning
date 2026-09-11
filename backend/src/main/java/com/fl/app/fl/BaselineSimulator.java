package com.fl.app.fl;

import com.fl.app.fl.pipeline.Aggregator;
import com.fl.app.fl.pipeline.Evaluator;
import com.fl.app.fl.pipeline.LocalTrainer;
import com.fl.app.fl.pipeline.PrivacyEngine;
import com.fl.app.fl.pipeline.SecurityLayer;

import java.util.ArrayList;

import java.util.List;
import java.util.Random;

public class BaselineSimulator {

    public static void main(String[] args) {
        int numHospitals = 3;
        int samplesPerHospital = 200;
        int features = 10;
        int localEpochs = 5;
        int numRounds = 20;
        double privacyBudget = 1.0;

        System.out.println("=======================================================================");
        System.out.println("                  FEDERATED LEARNING BASELINE TEST                     ");
        System.out.println("=======================================================================\n");

        // 1. Generate Data
        HospitalData[] trainData = new HospitalData[numHospitals];
        Random rand = new Random(42);

        int totalTrainSamples = numHospitals * samplesPerHospital;
        double[][] centralizedX = new double[totalTrainSamples][features];
        int[] centralizedY = new int[totalTrainSamples];
        int cIdx = 0;

        for (int h = 0; h < numHospitals; h++) {
            double[][] X = new double[samplesPerHospital][features];
            int[] y = new int[samplesPerHospital];
            for (int i = 0; i < samplesPerHospital; i++) {
                for (int j = 0; j < features; j++) {
                    X[i][j] = rand.nextGaussian();
                }
                double signal = X[i][0] + X[i][1] + X[i][2];
                y[i] = signal > 0 ? 1 : 0;
                
                centralizedX[cIdx] = X[i];
                centralizedY[cIdx] = y[i];
                cIdx++;
            }
            trainData[h] = new HospitalData("H" + h, X, y);
        }

        // Test Data (Separate seed)
        Random testRand = new Random(1000);
        int testSamples = 200;
        double[][] testX = new double[testSamples][features];
        int[] testY = new int[testSamples];
        for (int i = 0; i < testSamples; i++) {
            for (int j = 0; j < features; j++) {
                testX[i][j] = testRand.nextGaussian();
            }
            double signal = testX[i][0] + testX[i][1] + testX[i][2];
            testY[i] = signal > 0 ? 1 : 0;
        }

        System.out.println("Data generated. Starting experiments...\n");

        // Experiment A: Centralized
        LocalTrainer centTrainer = new LocalTrainer(features);
        // initialize dummy weights
        double[][] initW1 = new double[features][16];
        double[] initW2 = new double[16];
        LocalTrainer.ModelWeights dummyInit = new LocalTrainer.ModelWeights(initW1, new double[16], initW2, 0.0, 0, 0, "Init");
        centTrainer.loadWeights(dummyInit);
        
        LocalTrainer.ModelWeights centWeights = centTrainer.train(centralizedX, centralizedY, numRounds * localEpochs, "Centralized", 1);
        Evaluator.EvaluationResult centEval = Evaluator.evaluate(centWeights, testX, testY);
        
        // Experiment B: FL without DP
        Evaluator.EvaluationResult flNoDpEval = runFlSimulation(trainData, testX, testY, features, localEpochs, numRounds, 1000.0, false, dummyInit);
        
        // Experiment C: FL with DP
        Evaluator.EvaluationResult flDpEval = runFlSimulation(trainData, testX, testY, features, localEpochs, numRounds, privacyBudget, true, dummyInit);

        System.out.println("\n=========================================================================================");
        System.out.println(String.format("%-20s | %-10s | %-10s | %-10s | %-10s | %-10s", "Model", "Accuracy", "F1", "Recall", "ROC-AUC", "PR-AUC"));
        System.out.println("-----------------------------------------------------------------------------------------");
        printRow("Centralized", centEval);
        printRow("FL", flNoDpEval);
        printRow("FL + DP", flDpEval);
        System.out.println("=========================================================================================\n");
    }

    private static void printRow(String name, Evaluator.EvaluationResult eval) {
        System.out.println(String.format("%-20s | %-10.4f | %-10.4f | %-10.4f | %-10.4f | %-10.4f", 
                name, eval.accuracy(), eval.f1Score(), eval.recall(), eval.rocAuc(), eval.prAuc()));
    }

    private static Evaluator.EvaluationResult runFlSimulation(
            HospitalData[] trainData, double[][] valX, int[] valY,
            int features, int localEpochs, int numRounds, double totalBudget, boolean useDp, LocalTrainer.ModelWeights initWeights) {
        
        com.fl.app.security.AppSecurityProperties props = new com.fl.app.security.AppSecurityProperties();
        props.getAes().setKey("7a3f2c1e8b5d9f4023ac5e7b90d12f47a8c3e5f1d92b74e60a1f3c5e8b2d4f69");
        SecurityLayer secLayer = new SecurityLayer(props);
        double perRoundEpsilon = totalBudget / numRounds;
        
        LocalTrainer.ModelWeights globalWeights = initWeights;
        Evaluator.EvaluationResult lastEval = null;

        for (int r = 1; r <= numRounds; r++) {
            List<SecurityLayer.SecuredUpdate> updates = new ArrayList<>();
            for (HospitalData h : trainData) {
                LocalTrainer trainer = new LocalTrainer(features);
                trainer.loadWeights(globalWeights);
                
                LocalTrainer.ModelWeights rawWeights = trainer.train(h.X, h.y, localEpochs, h.name, r);
                
                LocalTrainer.ModelWeights finalWeights = rawWeights;
                if (useDp) {
                    PrivacyEngine pe = new PrivacyEngine();
                    finalWeights = pe.applyNoise(rawWeights, globalWeights, perRoundEpsilon);
                }
                
                updates.add(secLayer.secure(finalWeights));
            }
            
            Aggregator.AggregationResult aggRes = Aggregator.aggregate(updates, r, secLayer);
            globalWeights = aggRes.globalWeights();
            lastEval = Evaluator.evaluate(globalWeights, valX, valY);
        }
        return lastEval;
    }

    private record HospitalData(String name, double[][] X, int[] y) {}
}
