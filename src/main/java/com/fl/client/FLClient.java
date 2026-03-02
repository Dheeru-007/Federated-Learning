package com.fl.client;

import com.fl.crypto.SecureCommunication;
import com.fl.data.HealthcareDataSimulator;
import com.fl.data.HealthcareDataSimulator.HospitalDataset;
import com.fl.model.LogisticRegressionModel;
import com.fl.model.ModelWeights;
import com.fl.network.Message;
import com.fl.network.ModelSerializer;
import com.fl.privacy.DifferentialPrivacy;

import java.io.*;
import java.net.Socket;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;

/**
 * FLClient — Networked Hospital Client Node.
 *
 * Runs as a SEPARATE PROCESS on its own terminal.
 * Connects to the FL Server via TCP socket.
 *
 * Run from a separate terminal:
 *   java -cp target/federated-learning-java.jar com.fl.runner.ClientRunner [hospitalId] [host] [port]
 *
 * Example — 3 hospitals in 3 terminals:
 *   Terminal 2: java ... ClientRunner 1 localhost 9090
 *   Terminal 3: java ... ClientRunner 4 localhost 9090
 *   Terminal 4: java ... ClientRunner 7 localhost 9090
 *
 * Raw patient data NEVER leaves this process.
 */
public class FLClient {

    private static final int    LOCAL_EPOCHS  = 7;
    private static final double LEARNING_RATE = 0.01;
    private static final int    BATCH_SIZE    = 32;
    private static final double MAX_EPSILON   = 2.0;

    private final String     clientId;
    private final int        hospitalId;
    private final double[][] trainFeatures;
    private final int[]      trainLabels;
    private final double[][] testFeatures;
    private final int[]      testLabels;
    private final int        totalDatasetSize;

    private final LogisticRegressionModel localModel;
    private final DifferentialPrivacy     dp;
    private final SecureCommunication     crypto;

    private Socket             socket;
    private ObjectOutputStream out;
    private ObjectInputStream  in;
    private PublicKey          serverPublicKey;

    private int  roundsCompleted = 0;
    private long totalBytesSent  = 0;

    public FLClient(int hospitalId) throws Exception {
        this.hospitalId = hospitalId;
        this.clientId   = "Hospital-" + hospitalId;

        System.out.printf("[%s] Generating local patient dataset...%n", clientId);

        HealthcareDataSimulator sim = new HealthcareDataSimulator(2024L + hospitalId);
        HospitalDataset[] all       = sim.generateAndPartition();
        HospitalDataset   myData    = all[hospitalId % all.length];

        this.totalDatasetSize = myData.getSize();

        double[][][] fSplit = myData.getTrainTestFeatures();
        int[][]      lSplit = myData.getTrainTestLabels();
        this.trainFeatures  = fSplit[0];
        this.testFeatures   = fSplit[1];
        this.trainLabels    = lSplit[0];
        this.testLabels     = lSplit[1];

        int features     = myData.getFeatures()[0].length;
        this.localModel  = new LogisticRegressionModel(features, LEARNING_RATE, BATCH_SIZE);
        this.dp          = DifferentialPrivacy.gradientPerturbation();
        this.crypto      = new SecureCommunication(clientId);

        System.out.printf("[%s] Ready | Train=%,d | Test=%,d | Features=%d | Key=%s%n",
                clientId, trainFeatures.length, testFeatures.length,
                features, crypto.getPublicKeyFingerprint());
    }

    public void connectAndRun(String host, int port) throws Exception {
        System.out.printf("[%s] Connecting to %s:%d...%n", clientId, host, port);
        socket = new Socket(host, port);
        out    = new ObjectOutputStream(socket.getOutputStream());
        out.flush();
        in     = new ObjectInputStream(socket.getInputStream());
        System.out.printf("[%s] Connected!%n", clientId);

        // Register
        send(Message.register(clientId, crypto.getPublicKey().getEncoded()));

        // Receive WELCOME
        Message welcome = receive();
        if (welcome.getType() != Message.Type.WELCOME)
            throw new Exception("Expected WELCOME, got " + welcome.getType());

        serverPublicKey = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(welcome.getPayloadBytes()));

        System.out.printf("[%s] Registered. %s%n%n", clientId, welcome.getStatusMessage());
        System.out.printf("[%s] Waiting for training rounds from server...%n", clientId);

        messageLoop();
    }

    private void messageLoop() throws Exception {
        while (!socket.isClosed()) {
            Message msg = receive();
            switch (msg.getType()) {
                case ROUND_START:
                    System.out.printf("%n[%s] ══ Round %d ══%n",
                            clientId, msg.getRoundNumber());
                    break;

                case GLOBAL_MODEL:
                    handleGlobalModel(msg);
                    break;

                case ROUND_RESULT:
                    double[] r = msg.getPayloadDoubles();
                    System.out.printf("[%s] ← Server result: accuracy=%.2f%%  loss=%.4f%n",
                            clientId, r[0]*100, r[1]);
                    send(Message.ready(clientId, msg.getRoundNumber()));
                    break;

                case TRAINING_DONE:
                    handleTrainingDone(msg);
                    socket.close();
                    return;

                case WAIT:
                    System.out.printf("[%s] Waiting: %s%n", clientId, msg.getStatusMessage());
                    Thread.sleep(2000);
                    break;

                case ERROR:
                    System.err.printf("[%s] Server error: %s%n", clientId, msg.getStatusMessage());
                    break;

                default:
                    break;
            }
        }
        printSummary();
    }

    private void handleGlobalModel(Message msg) throws Exception {
        int round = msg.getRoundNumber();
        ModelWeights global = ModelSerializer.deserialize(msg.getPayloadBytes());
        localModel.loadWeights(global);

        if (!dp.hasBudgetRemaining(MAX_EPSILON)) {
            System.out.printf("[%s] Privacy budget exhausted. Skipping.%n", clientId);
            return;
        }

        System.out.printf("[%s] Training locally on %,d private samples (%d epochs)...%n",
                clientId, trainFeatures.length, LOCAL_EPOCHS);

        long t0 = System.currentTimeMillis();
        ModelWeights update = localModel.train(
                trainFeatures, trainLabels, LOCAL_EPOCHS, clientId, round);
        long dt = System.currentTimeMillis() - t0;

        double acc = localModel.evaluate(testFeatures, testLabels);
        System.out.printf("[%s] Local accuracy: %.2f%%  (%dms)%n", clientId, acc*100, dt);

        ModelWeights privateUpdate = dp.applyDP(update, trainFeatures.length, totalDatasetSize);
        System.out.printf("[%s] DP applied — ε_round=%.4f  ε_total=%.4f%n",
                clientId, privateUpdate.getEpsilonConsumed(), dp.getAccumulatedEpsilon());

        SecureCommunication.EncryptedPayload payload =
                crypto.encrypt(privateUpdate, serverPublicKey);

        byte[] bytes = serializePayload(payload);
        totalBytesSent += bytes.length;

        send(Message.localUpdate(clientId, bytes, round));
        System.out.printf("[%s] → Sent encrypted update (%,d bytes)%n", clientId, bytes.length);

        roundsCompleted++;
    }

    private void handleTrainingDone(Message msg) throws Exception {
        System.out.printf("%n[%s] ══════════════════════════════%n", clientId);
        System.out.printf("[%s]  FEDERATED TRAINING COMPLETE%n", clientId);
        System.out.printf("[%s]  %s%n", clientId, msg.getStatusMessage());
        System.out.printf("[%s] ══════════════════════════════%n", clientId);

        ModelWeights finalModel = ModelSerializer.deserialize(msg.getPayloadBytes());
        localModel.loadWeights(finalModel);
        double finalAcc = localModel.evaluate(testFeatures, testLabels);
        System.out.printf("[%s] Final model accuracy (local test): %.2f%%%n",
                clientId, finalAcc*100);

        printSummary();
    }

    private void send(Message msg) throws IOException {
        out.writeObject(msg);
        out.flush();
        out.reset();
    }

    private Message receive() throws Exception {
        return (Message) in.readObject();
    }

    private byte[] serializePayload(SecureCommunication.EncryptedPayload p) throws IOException {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(p);
            return bos.toByteArray();
        }
    }

    private void printSummary() {
        System.out.printf("%n[%s] Session summary:%n", clientId);
        System.out.printf("  Rounds completed:  %d%n", roundsCompleted);
        System.out.printf("  ε consumed:        %.4f / %.1f%n",
                dp.getAccumulatedEpsilon(), MAX_EPSILON);
        System.out.printf("  Data transmitted:  %.1f KB%n", totalBytesSent/1024.0);
        System.out.printf("  Private records:   %,d (never left this node)%n",
                trainFeatures.length);
    }
}
