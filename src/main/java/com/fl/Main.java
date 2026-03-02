package com.fl;

/**
 * Main — Redirect entry point.
 *
 * This system runs as separate processes. Use ServerRunner or ClientRunner.
 *
 * HOW TO RUN:
 *
 * 1) Build:
 *    mvn package -q
 *
 * 2) Terminal 1 — Server:
 *    java -cp target\federated-learning-java.jar com.fl.runner.ServerRunner 9090 3 20
 *
 * 3) Terminal 2 — Hospital 1:
 *    java -cp target\federated-learning-java.jar com.fl.runner.ClientRunner 1 localhost 9090
 *
 * 4) Terminal 3 — Hospital 4:
 *    java -cp target\federated-learning-java.jar com.fl.runner.ClientRunner 4 localhost 9090
 *
 * 5) Terminal 4 — Hospital 7:
 *    java -cp target\federated-learning-java.jar com.fl.runner.ClientRunner 7 localhost 9090
 */
public class Main {
    public static void main(String[] args) {
        System.out.println("=============================================================");
        System.out.println("  Federated ML - Java Distributed System");
        System.out.println("=============================================================");
        System.out.println("  This system runs across SEPARATE terminals.");
        System.out.println();
        System.out.println("  Step 1 - Terminal 1 (Server):");
        System.out.println("    java -cp target\\federated-learning-java.jar com.fl.runner.ServerRunner 9090 3 20");
        System.out.println();
        System.out.println("  Step 2 - Terminal 2 (Hospital 1):");
        System.out.println("    java -cp target\\federated-learning-java.jar com.fl.runner.ClientRunner 1 localhost 9090");
        System.out.println();
        System.out.println("  Step 3 - Terminal 3 (Hospital 4):");
        System.out.println("    java -cp target\\federated-learning-java.jar com.fl.runner.ClientRunner 4 localhost 9090");
        System.out.println();
        System.out.println("  Step 4 - Terminal 4 (Hospital 7):");
        System.out.println("    java -cp target\\federated-learning-java.jar com.fl.runner.ClientRunner 7 localhost 9090");
        System.out.println();
        System.out.println("  Training starts automatically once all 3 clients connect.");
        System.out.println("=============================================================");
    }
}
