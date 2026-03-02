package com.fl.server;

import com.fl.crypto.SecureCommunication;
import com.fl.model.ModelWeights;
import com.fl.network.Message;
import com.fl.network.ModelSerializer;

import java.io.*;
import java.net.*;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.concurrent.*;

/**
 * FLServer — Real networked FL Aggregation Server.
 *
 * Runs as a standalone process. Listens on TCP port for hospital clients.
 *
 * Run from separate terminal:
 *   java -cp ... com.fl.runner.ServerRunner [port] [minClients] [maxRounds]
 *
 * Default: port=9090, minClients=3, maxRounds=20
 */
public class FLServer {

    private final int    port;
    private final int    minClients;
    private final int    maxRounds;
    private static final int    UPDATE_TIMEOUT_SEC    = 120;
    private static final double CONVERGENCE_THRESHOLD = 0.001;

    private ServerSocket serverSocket;
    private final Map<String, ClientHandler> connectedClients = new ConcurrentHashMap<>();
    private final Map<String, PublicKey>     clientPublicKeys = new ConcurrentHashMap<>();
    private final ExecutorService            threadPool;

    private final SecureCommunication crypto;
    private final FedAvgAggregator    aggregator;
    private ModelWeights              globalModel;

    private volatile int     currentRound    = 0;
    private final List<Double> accuracyHistory = new ArrayList<>();
    private final List<Double> lossHistory     = new ArrayList<>();

    private final CountDownLatch      registrationLatch;
    private final Map<String, byte[]> pendingUpdates = new ConcurrentHashMap<>();

    private double[][] validationFeatures;
    private int[]      validationLabels;

    public FLServer(int port, int minClients, int maxRounds,
                    double[][] valFeatures, int[] valLabels,
                    int featureCount) throws Exception {
        this.port               = port;
        this.minClients         = minClients;
        this.maxRounds          = maxRounds;
        this.validationFeatures = valFeatures;
        this.validationLabels   = valLabels;
        this.crypto             = new SecureCommunication("FL-AggregatorServer");
        this.aggregator         = new FedAvgAggregator(0.60, true);
        this.globalModel        = new ModelWeights(featureCount);
        this.globalModel.setClientId("GlobalServer");
        this.registrationLatch  = new CountDownLatch(minClients);
        this.threadPool         = Executors.newCachedThreadPool();

        System.out.println("╔══════════════════════════════════════════════════╗");
        System.out.println("║     FL AGGREGATION SERVER — STARTING             ║");
        System.out.println("╚══════════════════════════════════════════════════╝");
        System.out.printf("  Port:          %d%n", port);
        System.out.printf("  Min clients:   %d%n", minClients);
        System.out.printf("  Max rounds:    %d%n", maxRounds);
        System.out.printf("  Features:      %d%n", featureCount);
        System.out.printf("  Server key:    %s%n", crypto.getPublicKeyFingerprint());
    }

    public void start() throws Exception {
        serverSocket = new ServerSocket(port);
        System.out.printf("%n[Server] Listening on port %d...%n", port);
        System.out.printf("[Server] Waiting for %d hospital clients to connect.%n%n", minClients);

        threadPool.submit(this::acceptConnections);

        registrationLatch.await();
        System.out.printf("%n[Server] %d clients connected. Starting training!%n%n",
                connectedClients.size());

        runTraining();
    }

    private void acceptConnections() {
        while (!serverSocket.isClosed()) {
            try {
                Socket s = serverSocket.accept();
                System.out.printf("[Server] Incoming connection: %s%n",
                        s.getInetAddress().getHostAddress());
                threadPool.submit(new ClientHandler(s));
            } catch (IOException e) {
                if (!serverSocket.isClosed())
                    System.err.println("[Server] Accept error: " + e.getMessage());
            }
        }
    }

    private void runTraining() throws Exception {
        long start = System.currentTimeMillis();

        for (currentRound = 1; currentRound <= maxRounds; currentRound++) {
            System.out.printf("%n══════ ROUND %d / %d  [clients: %d] ══════%n",
                    currentRound, maxRounds, connectedClients.size());

            pendingUpdates.clear();

            // Broadcast global model to all clients
            byte[] modelBytes = ModelSerializer.serialize(globalModel);
            broadcast(Message.roundStart("Server", currentRound, connectedClients.size()));
            broadcast(Message.globalModel("Server", modelBytes, currentRound));

            System.out.printf("[Server] Global model sent. Waiting for updates (timeout: %ds)...%n",
                    UPDATE_TIMEOUT_SEC);

            // Wait for updates
            long deadline = System.currentTimeMillis() + UPDATE_TIMEOUT_SEC * 1000L;
            int lastPrinted = -1;
            while (System.currentTimeMillis() < deadline) {
                int received = pendingUpdates.size();
                int needed   = (int) Math.ceil(connectedClients.size() * 0.6);
                if (received != lastPrinted) {
                    System.out.printf("[Server] Updates: %d/%d received%n",
                            received, connectedClients.size());
                    lastPrinted = received;
                }
                if (received >= needed) break;
                Thread.sleep(500);
            }

            System.out.printf("[Server] Aggregating %d updates...%n", pendingUpdates.size());

            Map<String, ModelWeights> decrypted = decryptUpdates();
            if (decrypted.isEmpty()) {
                System.out.println("[Server] No valid updates. Skipping round.");
                continue;
            }

            ModelWeights newGlobal = aggregator.aggregate(
                    decrypted, connectedClients.size(), globalModel, currentRound);
            if (newGlobal == null) continue;

            globalModel = newGlobal;

            double accuracy = evaluateGlobalModel();
            double loss     = computeValidationLoss();
            accuracyHistory.add(accuracy);
            lossHistory.add(loss);
            aggregator.recordMetrics(loss, accuracy, 0);

            System.out.printf("[Server] ✓ Round %d — Accuracy: %.2f%%  Loss: %.4f%n",
                    currentRound, accuracy * 100, loss);

            broadcast(Message.roundResult("Server", currentRound, accuracy, loss));

            if (aggregator.hasConverged(CONVERGENCE_THRESHOLD) && currentRound >= 5) {
                System.out.printf("[Server] Model converged at round %d!%n", currentRound);
                break;
            }
        }

        double finalAcc = accuracyHistory.isEmpty() ? 0
                : accuracyHistory.get(accuracyHistory.size() - 1);
        byte[] finalBytes = ModelSerializer.serialize(globalModel);
        broadcast(Message.trainingDone("Server", finalBytes, currentRound, finalAcc));

        long elapsed = System.currentTimeMillis() - start;
        printFinalReport(elapsed);
        aggregator.printFinalReport();

        Thread.sleep(2000); // Allow clients to receive final message
        serverSocket.close();
        threadPool.shutdown();
    }

    private Map<String, ModelWeights> decryptUpdates() {
        Map<String, ModelWeights> result = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : pendingUpdates.entrySet()) {
            String clientId = entry.getKey();
            PublicKey key   = clientPublicKeys.get(clientId);
            if (key == null) continue;
            try {
                SecureCommunication.EncryptedPayload payload = deserializePayload(entry.getValue());
                ModelWeights weights = crypto.decrypt(payload, key);
                if (weights != null) {
                    result.put(clientId, weights);
                    System.out.printf("[Server] ✓ Decrypted: %s (%,d samples)%n",
                            clientId, weights.getSampleCount());
                }
            } catch (Exception e) {
                System.err.printf("[Server] Decrypt failed for %s: %s%n", clientId, e.getMessage());
            }
        }
        return result;
    }

    private void broadcast(Message msg) {
        for (ClientHandler h : connectedClients.values()) {
            try { h.send(msg); }
            catch (IOException e) {
                System.err.printf("[Server] Send to %s failed: %s%n", h.clientId, e.getMessage());
            }
        }
    }

    private double evaluateGlobalModel() {
        if (validationFeatures == null) return 0.0;
        double[] w = globalModel.getWeights();
        double   b = globalModel.getBias();
        int correct = 0;
        for (int i = 0; i < validationFeatures.length; i++) {
            double z = b;
            for (int j = 0; j < w.length; j++) z += w[j] * validationFeatures[i][j];
            int pred = (1.0 / (1.0 + Math.exp(-z))) >= 0.5 ? 1 : 0;
            if (pred == validationLabels[i]) correct++;
        }
        return (double) correct / validationFeatures.length;
    }

    private double computeValidationLoss() {
        if (validationFeatures == null) return 0.0;
        double[] w = globalModel.getWeights();
        double   b = globalModel.getBias(), loss = 0, eps = 1e-10;
        for (int i = 0; i < validationFeatures.length; i++) {
            double z = b;
            for (int j = 0; j < w.length; j++) z += w[j] * validationFeatures[i][j];
            double p = Math.max(eps, Math.min(1-eps, 1.0/(1.0+Math.exp(-z))));
            loss -= validationLabels[i]*Math.log(p) + (1-validationLabels[i])*Math.log(1-p);
        }
        return loss / validationFeatures.length;
    }

    private SecureCommunication.EncryptedPayload deserializePayload(byte[] data) throws Exception {
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(data))) {
            return (SecureCommunication.EncryptedPayload) ois.readObject();
        }
    }

    private void printFinalReport(long elapsedMs) {
        System.out.println("\n╔══════════════════════════════════════════════════╗");
        System.out.println("║              TRAINING COMPLETE                   ║");
        System.out.println("╚══════════════════════════════════════════════════╝");
        System.out.printf("  Rounds:         %d%n", currentRound);
        System.out.printf("  Time:           %.1f sec%n", elapsedMs / 1000.0);
        System.out.printf("  Clients served: %d%n", connectedClients.size());
        if (!accuracyHistory.isEmpty()) {
            double best  = accuracyHistory.stream().mapToDouble(d->d).max().orElse(0);
            double last  = accuracyHistory.get(accuracyHistory.size()-1);
            System.out.printf("  Best accuracy:  %.2f%%%n", best*100);
            System.out.printf("  Final accuracy: %.2f%%%n", last*100);
            System.out.printf("  Base paper:     94.20%%%n");
        }
        System.out.println("\n  Round-by-round accuracy:");
        for (int i = 0; i < accuracyHistory.size(); i++)
            System.out.printf("    Round %2d → %.2f%%%n", i+1, accuracyHistory.get(i)*100);
    }

    // ── Inner: per-client TCP handler ─────────────────────────────────────

    class ClientHandler implements Runnable {
        final Socket socket;
        ObjectOutputStream out;
        ObjectInputStream  in;
        String clientId = "unknown";

        ClientHandler(Socket s) { this.socket = s; }

        @Override
        public void run() {
            try {
                out = new ObjectOutputStream(socket.getOutputStream());
                out.flush();
                in  = new ObjectInputStream(socket.getInputStream());

                Message reg = (Message) in.readObject();
                if (reg.getType() != Message.Type.REGISTER) {
                    send(Message.error("Server", "First message must be REGISTER"));
                    return;
                }

                clientId = reg.getSenderId();
                byte[]    keyBytes = reg.getPayloadBytes();
                PublicKey pubKey   = KeyFactory.getInstance("RSA")
                        .generatePublic(new X509EncodedKeySpec(keyBytes));

                clientPublicKeys.put(clientId, pubKey);
                connectedClients.put(clientId, this);
                System.out.printf("[Server] ✓ Registered: %-15s  (total connected: %d)%n",
                        clientId, connectedClients.size());

                send(Message.welcome("Server",
                        crypto.getPublicKey().getEncoded(),
                        connectedClients.size()));

                registrationLatch.countDown();

                while (!socket.isClosed()) {
                    try {
                        Message msg = (Message) in.readObject();
                        if (msg.getType() == Message.Type.LOCAL_UPDATE) {
                            System.out.printf("[Server] ← Update from %-15s  round=%d  size=%,d bytes%n",
                                    clientId, msg.getRoundNumber(), msg.getPayloadBytes().length);
                            pendingUpdates.put(clientId, msg.getPayloadBytes());
                        }
                    } catch (EOFException | SocketException e) { break; }
                }
            } catch (Exception e) {
                System.err.printf("[Server] Error handling %s: %s%n", clientId, e.getMessage());
            } finally {
                connectedClients.remove(clientId);
                System.out.printf("[Server] Client disconnected: %s%n", clientId);
            }
        }

        synchronized void send(Message msg) throws IOException {
            out.writeObject(msg);
            out.flush();
            out.reset();
        }
    }

    public PublicKey getPublicKey() { return crypto.getPublicKey(); }
}
