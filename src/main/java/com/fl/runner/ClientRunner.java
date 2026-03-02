package com.fl.runner;

import com.fl.client.FLClient;

/**
 * ClientRunner — Launch a Hospital Client Node from its own terminal.
 *
 * Each hospital runs this on its own machine/terminal.
 * The hospital ID determines which partition of the dataset this node uses.
 *
 * Usage:
 *   java -cp target/federated-learning-java.jar com.fl.runner.ClientRunner [hospitalId] [serverHost] [serverPort]
 *
 * Arguments:
 *   hospitalId   0–9  (determines which hospital's patient data this node uses)
 *   serverHost   IP or hostname of the FL server (default: localhost)
 *   serverPort   Port the server is listening on (default: 9090)
 *
 * ─────────────────────────────────────────────────────────────────────
 * HOW TO RUN A FULL DISTRIBUTED EXPERIMENT (4 terminals example):
 * ─────────────────────────────────────────────────────────────────────
 *
 * Step 1 — Terminal 1 (Server):
 *   java -cp target/federated-learning-java.jar com.fl.runner.ServerRunner 9090 3 20
 *   (waits for 3 hospital clients to connect before training starts)
 *
 * Step 2 — Terminal 2 (Hospital 1):
 *   java -cp target/federated-learning-java.jar com.fl.runner.ClientRunner 1 localhost 9090
 *
 * Step 3 — Terminal 3 (Hospital 4):
 *   java -cp target/federated-learning-java.jar com.fl.runner.ClientRunner 4 localhost 9090
 *
 * Step 4 — Terminal 4 (Hospital 7):
 *   java -cp target/federated-learning-java.jar com.fl.runner.ClientRunner 7 localhost 9090
 *
 * Training starts automatically once 3 clients connect.
 *
 * ─────────────────────────────────────────────────────────────────────
 * ON DIFFERENT MACHINES (replace localhost with server's actual IP):
 * ─────────────────────────────────────────────────────────────────────
 *
 *   Machine A (Server):    ServerRunner 9090 3 20
 *   Machine B (Hospital 1): ClientRunner 1 192.168.1.10 9090
 *   Machine C (Hospital 4): ClientRunner 4 192.168.1.10 9090
 *   Machine D (Hospital 7): ClientRunner 7 192.168.1.10 9090
 *
 * Each machine's patient data NEVER leaves that machine.
 * Only encrypted, DP-noised model weights are transmitted.
 */
public class ClientRunner {

    public static void main(String[] args) throws Exception {
        int    hospitalId  = args.length > 0 ? Integer.parseInt(args[0]) : 1;
        String serverHost  = args.length > 1 ? args[1] : "localhost";
        int    serverPort  = args.length > 2 ? Integer.parseInt(args[2]) : 9090;

        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║  Federated ML — Privacy-Preserving Healthcare AI     ║");
        System.out.printf( "║  Hospital Client Node — Hospital-%d                   ║%n", hospitalId);
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.printf("  Hospital ID:   %d%n", hospitalId);
        System.out.printf("  Server:        %s:%d%n", serverHost, serverPort);
        System.out.printf("  Privacy:       ε=0.5 (Gaussian DP), δ=1e-5%n");
        System.out.printf("  Encryption:    AES-256-GCM + RSA-2048%n");
        System.out.printf("  Data sharing:  NONE — raw data stays on this node%n%n");

        FLClient client = new FLClient(hospitalId);
        client.connectAndRun(serverHost, serverPort);
    }
}
