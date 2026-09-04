package com.fl.app.fl.pipeline;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.security.MessageDigest;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

public class SecurityLayer {

    private static final String AES_KEY = "FederatedLearning";
    private static final SecretKey SECRET_KEY = generateKey();

    private static SecretKey generateKey() {
        try {
            byte[] keyBytes = new byte[16];
            byte[] src = AES_KEY.getBytes();
            System.arraycopy(src, 0, keyBytes, 0, Math.min(src.length, 16));
            return new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate AES key", e);
        }
    }

    public record SecuredUpdate(
        byte[] encryptedWeights,
        String hash,
        String clientId,
        int sampleCount,
        int round
    ) {}

    /**
     * Bundles weights + bias into a single serialized payload so both
     * survive the encrypt → decrypt round-trip.
     */
    private static final class WeightsWithBias implements java.io.Serializable {
        final double[] weights;
        final double bias;
        WeightsWithBias(double[] weights, double bias) {
            this.weights = weights;
            this.bias = bias;
        }
    }

    public static SecuredUpdate secure(LocalTrainer.ModelWeights weights) {
        try {
            byte[] serialized = serialize(
                    new WeightsWithBias(weights.weights(), weights.bias()));
            String hash = sha256(serialized);
            byte[] encrypted = encrypt(serialized);

            return new SecuredUpdate(
                encrypted,
                hash,
                weights.clientId(),
                weights.sampleCount(),
                weights.round()
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to secure weights: " + e.getMessage(), e);
        }
    }

    public static LocalTrainer.ModelWeights verify(SecuredUpdate update) {
        try {
            byte[] decrypted = decrypt(update.encryptedWeights());
            String recomputedHash = sha256(decrypted);

            if (!recomputedHash.equals(update.hash())) {
                throw new SecurityException("Hash mismatch for client: " + update.clientId()
                    + " — update rejected as potentially malicious");
            }

            WeightsWithBias wb = deserialize(decrypted);

            return new LocalTrainer.ModelWeights(
                wb.weights,
                wb.bias,
                update.sampleCount(),
                update.round(),
                update.clientId()
            );
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to verify weights: " + e.getMessage(), e);
        }
    }

    public static String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 failed", e);
        }
    }

    private static byte[] encrypt(byte[] data) throws Exception {
        Cipher cipher = Cipher.getInstance("AES");
        cipher.init(Cipher.ENCRYPT_MODE, SECRET_KEY);
        return cipher.doFinal(data);
    }

    private static byte[] decrypt(byte[] data) throws Exception {
        Cipher cipher = Cipher.getInstance("AES");
        cipher.init(Cipher.DECRYPT_MODE, SECRET_KEY);
        return cipher.doFinal(data);
    }

    private static byte[] serialize(WeightsWithBias wb) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(bos);
        oos.writeObject(wb.weights);
        oos.writeDouble(wb.bias);
        oos.flush();
        return bos.toByteArray();
    }

    private static WeightsWithBias deserialize(byte[] data) throws Exception {
        ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(data));
        double[] weights = (double[]) ois.readObject();
        double bias = ois.readDouble();
        return new WeightsWithBias(weights, bias);
    }
}