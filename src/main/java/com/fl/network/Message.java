package com.fl.network;

import java.io.Serializable;

/**
 * Message — Network protocol message exchanged between FL Server and Clients.
 *
 * All communication between the server and hospital clients uses this message
 * format, transmitted over TCP sockets as serialized Java objects.
 *
 * Message flow:
 *
 *   CLIENT → SERVER:
 *     REGISTER       Client joins the FL session (sends clientId + public key)
 *     LOCAL_UPDATE   Client sends encrypted, DP-noised weight update
 *     READY          Client signals it is ready for next round
 *
 *   SERVER → CLIENT:
 *     WELCOME        Server acknowledges registration (sends server public key)
 *     GLOBAL_MODEL   Server broadcasts current global model weights
 *     ROUND_START    Server signals a new training round is beginning
 *     ROUND_RESULT   Server sends round accuracy/loss after aggregation
 *     TRAINING_DONE  Server signals training is complete (final model)
 *     WAIT           Server tells client to wait (threshold not yet met)
 *     ERROR          Server signals an error condition
 */
public class Message implements Serializable {

    private static final long serialVersionUID = 1L;

    // ── Message types ─────────────────────────────────────────────────────

    public enum Type {
        // Client → Server
        REGISTER,
        LOCAL_UPDATE,
        READY,
        // Server → Client
        WELCOME,
        GLOBAL_MODEL,
        ROUND_START,
        ROUND_RESULT,
        TRAINING_DONE,
        WAIT,
        ERROR
    }

    // ── Fields ────────────────────────────────────────────────────────────

    private final Type   type;
    private final String senderId;
    private final int    roundNumber;
    private final Object payload;       // Flexible payload depending on message type
    private final String statusMessage; // Human-readable status
    private final long   timestamp;

    // ── Constructors ──────────────────────────────────────────────────────

    public Message(Type type, String senderId, int roundNumber,
                   Object payload, String statusMessage) {
        this.type          = type;
        this.senderId      = senderId;
        this.roundNumber   = roundNumber;
        this.payload       = payload;
        this.statusMessage = statusMessage;
        this.timestamp     = System.currentTimeMillis();
    }

    // ── Factory methods ───────────────────────────────────────────────────

    /** Client registers with the server. Payload = byte[] (encoded public key). */
    public static Message register(String clientId, byte[] publicKeyBytes) {
        return new Message(Type.REGISTER, clientId, 0, publicKeyBytes,
                "Client " + clientId + " requesting registration");
    }

    /** Server welcomes a client. Payload = byte[] (server's encoded public key). */
    public static Message welcome(String serverId, byte[] serverPublicKeyBytes, int totalClients) {
        return new Message(Type.WELCOME, serverId, 0, serverPublicKeyBytes,
                "Welcome! Registered clients: " + totalClients);
    }

    /** Server broadcasts global model. Payload = byte[] (serialized ModelWeights). */
    public static Message globalModel(String serverId, byte[] serializedWeights, int round) {
        return new Message(Type.GLOBAL_MODEL, serverId, round, serializedWeights,
                "Global model for round " + round);
    }

    /** Server signals round start. */
    public static Message roundStart(String serverId, int round, int participantsNeeded) {
        return new Message(Type.ROUND_START, serverId, round, participantsNeeded,
                "Round " + round + " started. Need " + participantsNeeded + " participants.");
    }

    /** Client sends encrypted weight update. Payload = EncryptedPayload bytes. */
    public static Message localUpdate(String clientId, byte[] encryptedPayload, int round) {
        return new Message(Type.LOCAL_UPDATE, clientId, round, encryptedPayload,
                "Local update from " + clientId + " for round " + round);
    }

    /** Client signals it is ready for the next round. */
    public static Message ready(String clientId, int round) {
        return new Message(Type.READY, clientId, round, null,
                clientId + " ready for round " + round);
    }

    /** Server sends round results. Payload = double[]{accuracy, loss}. */
    public static Message roundResult(String serverId, int round, double accuracy, double loss) {
        return new Message(Type.ROUND_RESULT, serverId, round,
                new double[]{accuracy, loss},
                String.format("Round %d: accuracy=%.2f%%, loss=%.4f", round, accuracy * 100, loss));
    }

    /** Server signals training is complete. Payload = byte[] (final model weights). */
    public static Message trainingDone(String serverId, byte[] finalWeights,
                                       int rounds, double finalAccuracy) {
        return new Message(Type.TRAINING_DONE, serverId, rounds, finalWeights,
                String.format("Training complete after %d rounds. Final accuracy: %.2f%%",
                        rounds, finalAccuracy * 100));
    }

    /** Server tells client to wait. */
    public static Message wait(String serverId, String reason) {
        return new Message(Type.WAIT, serverId, 0, null, reason);
    }

    /** Error message. */
    public static Message error(String senderId, String errorMsg) {
        return new Message(Type.ERROR, senderId, 0, null, errorMsg);
    }

    // ── Getters ───────────────────────────────────────────────────────────

    public Type   getType()          { return type; }
    public String getSenderId()      { return senderId; }
    public int    getRoundNumber()   { return roundNumber; }
    public Object getPayload()       { return payload; }
    public String getStatusMessage() { return statusMessage; }
    public long   getTimestamp()     { return timestamp; }

    // Typed payload accessors
    public byte[]   getPayloadBytes()   { return (byte[]) payload; }
    public double[] getPayloadDoubles() { return (double[]) payload; }
    public int      getPayloadInt()     { return (Integer) payload; }

    @Override
    public String toString() {
        return String.format("Message[type=%s, from=%s, round=%d, msg='%s']",
                type, senderId, roundNumber, statusMessage);
    }
}
