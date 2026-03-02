package com.fl.network;

import com.fl.model.ModelWeights;
import java.io.*;

/**
 * ModelSerializer — Converts ModelWeights to/from byte arrays for network transmission.
 *
 * Used when the server broadcasts the global model to clients (as byte[])
 * and when clients need to reconstruct it after receiving it over the socket.
 */
public class ModelSerializer {

    public static byte[] serialize(ModelWeights weights) throws IOException {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(bos)) {
            double[] w = weights.getWeights();
            dos.writeInt(w.length);
            for (double v : w)  dos.writeDouble(v);
            dos.writeDouble(weights.getBias());
            dos.writeInt(weights.getSampleCount());
            dos.writeInt(weights.getRoundNumber());
            dos.writeUTF(weights.getClientId() != null ? weights.getClientId() : "");
            dos.writeDouble(weights.getEpsilonConsumed());
            return bos.toByteArray();
        }
    }

    public static ModelWeights deserialize(byte[] data) throws IOException {
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data))) {
            int len = dis.readInt();
            double[] w = new double[len];
            for (int i = 0; i < len; i++) w[i] = dis.readDouble();
            double bias     = dis.readDouble();
            int samples     = dis.readInt();
            int round       = dis.readInt();
            String clientId = dis.readUTF();
            double epsilon  = dis.readDouble();
            return new ModelWeights(w, bias, samples, round, clientId, epsilon);
        }
    }
}
