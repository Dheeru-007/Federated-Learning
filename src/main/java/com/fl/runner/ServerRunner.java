package com.fl.runner;

import com.fl.data.HealthcareDataSimulator;
import com.fl.data.HealthcareDataSimulator.HospitalDataset;
import com.fl.server.FLServer;

/**
 * ServerRunner — Launch the FL Aggregation Server from a terminal.
 *
 * Usage:
 *   java -cp target/federated-learning-java.jar com.fl.runner.ServerRunner [port] [minClients] [maxRounds]
 *
 * Defaults:
 *   port       = 9090
 *   minClients = 3
 *   maxRounds  = 20
 *
 * Example:
 *   java -cp target/federated-learning-java.jar com.fl.runner.ServerRunner 9090 3 20
 */
public class ServerRunner {

    public static void main(String[] args) throws Exception {
        int port       = args.length > 0 ? Integer.parseInt(args[0]) : 9090;
        int minClients = args.length > 1 ? Integer.parseInt(args[1]) : 3;
        int maxRounds  = args.length > 2 ? Integer.parseInt(args[2]) : 20;

        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║  Federated ML — Privacy-Preserving Healthcare AI     ║");
        System.out.println("║  Java-Based Distributed System (Server Node)         ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.printf("  Based on: Ren, Li & Wu (2024) — AIMLR%n");
        System.out.printf("  Encryption: AES-256-GCM + RSA-2048 (JCA)%n");
        System.out.printf("  Privacy:    Differential Privacy ε=0.5, δ=1e-5%n%n");

        // Generate validation data (Hospital-0 held at server for global evaluation)
        System.out.println("[ServerRunner] Generating server-side validation data...");
        HealthcareDataSimulator sim  = new HealthcareDataSimulator(2024L);
        HospitalDataset[] hospitals  = sim.generateAndPartition();
        HospitalDataset   h0         = hospitals[0];

        double[][][] splits = h0.getTrainTestFeatures();
        int[][]      labels = h0.getTrainTestLabels();
        double[][]   valX   = splits[1];  // Test split of hospital 0
        int[]        valY   = labels[1];

        System.out.printf("[ServerRunner] Validation set: %,d samples%n%n", valY.length);

        // Start server
        FLServer server = new FLServer(
                port, minClients, maxRounds,
                valX, valY,
                HealthcareDataSimulator.FEATURE_COUNT);

        server.start();
    }
}
