package com.fl.app.fl.pipeline;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Handles AES-256-GCM encryption and SHA-256 integrity checking for model weight updates.
 *
 * Fixes applied:
 *  - Was AES/ECB/PKCS5Padding  → now AES/GCM/NoPadding (authenticated encryption, no IV reuse)
 *  - Was hardcoded key "FederatedLearning" → now injected from app.security.aes.key in application.properties
 *  - Was Java ObjectOutputStream serialization → now ByteBuffer (no deserialization gadget risk)
 */
@Component
public class SecurityLayer {

    // ─── Crypto constants ────────────────────────────────────────────────────
    private static final String ALGORITHM    = "AES/GCM/NoPadding";
    private static final int    IV_LENGTH    = 12;   // 96-bit IV — NIST SP 800-38D recommended
    private static final int    TAG_BITS     = 128;  // 128-bit GCM authentication tag

    private final SecretKey    secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    // ─── Constructor — key injected from application.properties ─────────────
    public SecurityLayer(@Value("${app.security.aes.key}") String keyHex) {
        byte[] keyBytes = hexToBytes(keyHex);
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException(
                "AES key must be exactly 32 bytes (256-bit). Got: " + keyBytes.length + " bytes. " +
                "Check app.security.aes.key in application.properties.");
        }
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    // ─── Public types ────────────────────────────────────────────────────────

    public record SecuredUpdate(
        byte[] encryptedWeights,
        String hash,
        String clientId,
        int    sampleCount,
        int    round
    ) {}

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * Serializes, hashes (SHA-256), then encrypts (AES-256-GCM) the model weights.
     * The plaintext hash is stored alongside the ciphertext so the receiver can
     * verify integrity after decryption.
     */
    public SecuredUpdate secure(LocalTrainer.ModelWeights weights) {
        try {
            byte[] serialized = serialize(weights.weights(), weights.bias());
            String hash       = sha256(serialized);
            byte[] encrypted  = encrypt(serialized);

            return new SecuredUpdate(
                encrypted, hash,
                weights.clientId(), weights.sampleCount(), weights.round());

        } catch (Exception e) {
            throw new RuntimeException("Failed to secure weights: " + e.getMessage(), e);
        }
    }

    /**
     * Decrypts the update (AES-256-GCM) and verifies the SHA-256 hash.
     * Throws SecurityException on hash mismatch — the Aggregator will reject the client.
     */
    public LocalTrainer.ModelWeights verify(SecuredUpdate update) {
        try {
            byte[] decrypted       = decrypt(update.encryptedWeights());
            String recomputedHash  = sha256(decrypted);

            if (!recomputedHash.equals(update.hash())) {
                throw new SecurityException(
                    "Hash mismatch for client: " + update.clientId()
                    + " — update rejected as potentially malicious");
            }

            double[] weights = deserializeWeights(decrypted);
            double   bias    = deserializeBias(decrypted);

            return new LocalTrainer.ModelWeights(
                weights, bias,
                update.sampleCount(), update.round(), update.clientId());

        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to verify weights: " + e.getMessage(), e);
        }
    }

    // ─── Encryption (AES-256-GCM) ────────────────────────────────────────────

    /**
     * Encrypts data with AES-256-GCM.
     * Output format: [12-byte random IV | ciphertext + 16-byte GCM tag]
     */
    private byte[] encrypt(byte[] data) throws Exception {
        byte[] iv = new byte[IV_LENGTH];
        secureRandom.nextBytes(iv);                          // fresh random IV every call

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_BITS, iv));
        byte[] ciphertext = cipher.doFinal(data);

        // Prepend IV so decrypt() can extract it
        byte[] result = new byte[IV_LENGTH + ciphertext.length];
        System.arraycopy(iv,         0, result, 0,         IV_LENGTH);
        System.arraycopy(ciphertext, 0, result, IV_LENGTH, ciphertext.length);
        return result;
    }

    private byte[] decrypt(byte[] data) throws Exception {
        byte[] iv         = Arrays.copyOfRange(data, 0,         IV_LENGTH);
        byte[] ciphertext = Arrays.copyOfRange(data, IV_LENGTH, data.length);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_BITS, iv));
        return cipher.doFinal(ciphertext);           // GCM tag verification is automatic
    }

    // ─── Serialization (ByteBuffer — no Java object serialization) ───────────
    //
    // Layout: [4-byte int: weight count | N × 8-byte doubles: weights | 8-byte double: bias]

    private static byte[] serialize(double[] weights, double bias) {
        ByteBuffer buf = ByteBuffer.allocate(Integer.BYTES + weights.length * Double.BYTES + Double.BYTES);
        buf.putInt(weights.length);
        for (double w : weights) buf.putDouble(w);
        buf.putDouble(bias);
        return buf.array();
    }

    private static double[] deserializeWeights(byte[] data) {
        ByteBuffer buf = ByteBuffer.wrap(data);
        int len = buf.getInt();
        double[] weights = new double[len];
        for (int i = 0; i < len; i++) weights[i] = buf.getDouble();
        return weights;
    }

    private static double deserializeBias(byte[] data) {
        ByteBuffer buf = ByteBuffer.wrap(data);
        int len = buf.getInt();
        buf.position(Integer.BYTES + len * Double.BYTES); // skip past weights
        return buf.getDouble();
    }

    // ─── Hashing ─────────────────────────────────────────────────────────────

    public static String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 failed", e);
        }
    }

    // ─── Key utilities ───────────────────────────────────────────────────────

    private static byte[] hexToBytes(String hex) {
        if (hex == null || hex.length() % 2 != 0) {
            throw new IllegalArgumentException("Hex string must be non-null and even-length");
        }
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }
}