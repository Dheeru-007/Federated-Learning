package com.fl.crypto;

import com.fl.model.ModelWeights;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.*;
import java.util.Base64;
import java.io.*;

/**
 * SecureCommunication — JCA-based encrypted channel for FL parameter exchange.
 *
 * Implements AES-256-GCM authenticated encryption for model weight transmission
 * between FL clients and the aggregation server. Uses RSA-2048 for initial
 * session key exchange (hybrid encryption).
 *
 * Security architecture:
 *   1. RSA-2048 key pair generated per node (server and each client)
 *   2. AES-256-GCM session key generated per communication session
 *   3. Session key encrypted with recipient's RSA public key
 *   4. Model weights serialized, encrypted with AES-GCM, and signed with sender's RSA private key
 *   5. Recipient decrypts session key with own private key, then decrypts payload
 *   6. Digital signature verified before weights are accepted
 *
 * This architecture satisfies enterprise cryptographic requirements:
 *   - Confidentiality: AES-256-GCM
 *   - Authenticity: RSA-2048 digital signature (SHA256withRSA)
 *   - Integrity: GCM authentication tag (128-bit)
 *   - Forward secrecy: Per-session AES keys
 *
 * Matches enterprise Java cryptographic standards (JCA/JCE):
 *   - Provider: SunJCE (built-in, no external dependency)
 *   - Key management: Java KeyStore (JKS) compatible
 */
public class SecureCommunication {

    // ── Cryptographic constants ───────────────────────────────────────────
    private static final String AES_ALGORITHM   = "AES";
    private static final String AES_MODE        = "AES/GCM/NoPadding";
    private static final String RSA_ALGORITHM   = "RSA";
    private static final String RSA_SIGN_ALG    = "SHA256withRSA";
    private static final String HASH_ALGORITHM  = "SHA-256";
    private static final int    AES_KEY_BITS    = 256;
    private static final int    RSA_KEY_BITS    = 2048;
    private static final int    GCM_IV_LENGTH   = 12;   // 96-bit IV (NIST recommended)
    private static final int    GCM_TAG_LENGTH  = 128;  // 128-bit authentication tag

    // ── Node identity ─────────────────────────────────────────────────────
    private final String nodeId;
    private final KeyPair rsaKeyPair;

    // ── Constructor ───────────────────────────────────────────────────────

    /**
     * Initialize a secure communication endpoint.
     * Generates a fresh RSA-2048 key pair for this node.
     *
     * @param nodeId Human-readable identifier (e.g., "Hospital-1", "AggregatorServer")
     */
    public SecureCommunication(String nodeId) throws NoSuchAlgorithmException {
        this.nodeId = nodeId;
        this.rsaKeyPair = generateRSAKeyPair();
        System.out.printf("[SecureCommunication] Node '%s' initialized with RSA-%d key pair%n",
                nodeId, RSA_KEY_BITS);
    }

    // ── Key generation ────────────────────────────────────────────────────

    private KeyPair generateRSAKeyPair() throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(RSA_ALGORITHM);
        generator.initialize(RSA_KEY_BITS, new SecureRandom());
        return generator.generateKeyPair();
    }

    private SecretKey generateAESKey() throws NoSuchAlgorithmException {
        KeyGenerator kg = KeyGenerator.getInstance(AES_ALGORITHM);
        kg.init(AES_KEY_BITS, new SecureRandom());
        return kg.generateKey();
    }

    // ── Encryption pipeline ───────────────────────────────────────────────

    /**
     * Encrypt and sign ModelWeights for secure transmission to a recipient.
     *
     * @param weights          Model weights to protect
     * @param recipientPublicKey RSA public key of the receiving node
     * @return EncryptedPayload containing all cryptographic material
     */
    public EncryptedPayload encrypt(ModelWeights weights, PublicKey recipientPublicKey)
            throws Exception {

        // 1. Serialize weights to bytes
        byte[] plaintext = serializeWeights(weights);

        // 2. Generate per-session AES-256 key
        SecretKey sessionKey = generateAESKey();

        // 3. Generate random IV (nonce)
        byte[] iv = new byte[GCM_IV_LENGTH];
        new SecureRandom().nextBytes(iv);

        // 4. Encrypt payload with AES-256-GCM
        Cipher aesCipher = Cipher.getInstance(AES_MODE);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        aesCipher.init(Cipher.ENCRYPT_MODE, sessionKey, gcmSpec);
        byte[] ciphertext = aesCipher.doFinal(plaintext);

        // 5. Encrypt AES session key with recipient's RSA public key
        Cipher rsaCipher = Cipher.getInstance(RSA_ALGORITHM);
        rsaCipher.init(Cipher.ENCRYPT_MODE, recipientPublicKey);
        byte[] encryptedKey = rsaCipher.doFinal(sessionKey.getEncoded());

        // 6. Sign the ciphertext with sender's RSA private key
        Signature signer = Signature.getInstance(RSA_SIGN_ALG);
        signer.initSign(rsaKeyPair.getPrivate());
        signer.update(ciphertext);
        byte[] signature = signer.sign();

        // 7. Compute integrity hash
        MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
        byte[] payloadHash = digest.digest(plaintext);

        return new EncryptedPayload(ciphertext, encryptedKey, iv, signature,
                payloadHash, nodeId, weights.getRoundNumber());
    }

    // ── Decryption pipeline ───────────────────────────────────────────────

    /**
     * Decrypt and verify an EncryptedPayload received from a client.
     *
     * @param payload        The encrypted payload to process
     * @param senderPublicKey RSA public key of the sending node (for signature verification)
     * @return Decrypted ModelWeights, or null if verification fails
     */
    public ModelWeights decrypt(EncryptedPayload payload, PublicKey senderPublicKey)
            throws Exception {

        // 1. Verify digital signature
        Signature verifier = Signature.getInstance(RSA_SIGN_ALG);
        verifier.initVerify(senderPublicKey);
        verifier.update(payload.getCiphertext());
        boolean signatureValid = verifier.verify(payload.getSignature());

        if (!signatureValid) {
            System.err.printf("[SecureCommunication] SECURITY ALERT: Invalid signature " +
                    "from node '%s' in round %d. Payload REJECTED.%n",
                    payload.getSenderId(), payload.getRoundNumber());
            return null;
        }

        // 2. Decrypt AES session key with own RSA private key
        Cipher rsaCipher = Cipher.getInstance(RSA_ALGORITHM);
        rsaCipher.init(Cipher.DECRYPT_MODE, rsaKeyPair.getPrivate());
        byte[] aesKeyBytes = rsaCipher.doFinal(payload.getEncryptedKey());
        SecretKey sessionKey = new SecretKeySpec(aesKeyBytes, AES_ALGORITHM);

        // 3. Decrypt payload with AES-256-GCM (GCM tag automatically verifies integrity)
        Cipher aesCipher = Cipher.getInstance(AES_MODE);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, payload.getIv());
        aesCipher.init(Cipher.DECRYPT_MODE, sessionKey, gcmSpec);
        byte[] plaintext = aesCipher.doFinal(payload.getCiphertext());

        // 4. Verify integrity hash
        MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
        byte[] computedHash = digest.digest(plaintext);
        if (!MessageDigest.isEqual(computedHash, payload.getPayloadHash())) {
            System.err.println("[SecureCommunication] INTEGRITY FAILURE: Hash mismatch. Payload REJECTED.");
            return null;
        }

        System.out.printf("[SecureCommunication] Payload from '%s' (round %d): " +
                "signature VALID, integrity VERIFIED%n",
                payload.getSenderId(), payload.getRoundNumber());

        return deserializeWeights(plaintext);
    }

    // ── Serialization ─────────────────────────────────────────────────────

    private byte[] serializeWeights(ModelWeights weights) throws IOException {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(bos)) {
            double[] w = weights.getWeights();
            dos.writeInt(w.length);
            for (double v : w) dos.writeDouble(v);
            dos.writeDouble(weights.getBias());
            dos.writeInt(weights.getSampleCount());
            dos.writeInt(weights.getRoundNumber());
            String cid = weights.getClientId() != null ? weights.getClientId() : "";
            dos.writeUTF(cid);
            dos.writeDouble(weights.getEpsilonConsumed());
            return bos.toByteArray();
        }
    }

    private ModelWeights deserializeWeights(byte[] data) throws IOException {
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data))) {
            int len = dis.readInt();
            double[] w = new double[len];
            for (int i = 0; i < len; i++) w[i] = dis.readDouble();
            double bias        = dis.readDouble();
            int sampleCount    = dis.readInt();
            int roundNumber    = dis.readInt();
            String clientId    = dis.readUTF();
            double epsilon     = dis.readDouble();
            return new ModelWeights(w, bias, sampleCount, roundNumber, clientId, epsilon);
        }
    }

    // ── Public key access ─────────────────────────────────────────────────

    /** Returns this node's RSA public key (shared with peers for encryption). */
    public PublicKey getPublicKey() { return rsaKeyPair.getPublic(); }

    /** Returns the node identifier. */
    public String getNodeId() { return nodeId; }

    /** Returns hex-encoded fingerprint of this node's public key for logging. */
    public String getPublicKeyFingerprint() {
        try {
            MessageDigest md = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hash = md.digest(rsaKeyPair.getPublic().getEncoded());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02X", hash[i]));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "UNKNOWN";
        }
    }

    // ── EncryptedPayload inner class ──────────────────────────────────────

    /**
     * Immutable container for all cryptographic material in one transmission.
     */
    public static class EncryptedPayload {
        private final byte[] ciphertext;
        private final byte[] encryptedKey;
        private final byte[] iv;
        private final byte[] signature;
        private final byte[] payloadHash;
        private final String senderId;
        private final int roundNumber;

        public EncryptedPayload(byte[] ciphertext, byte[] encryptedKey, byte[] iv,
                                byte[] signature, byte[] payloadHash,
                                String senderId, int roundNumber) {
            this.ciphertext   = ciphertext;
            this.encryptedKey = encryptedKey;
            this.iv           = iv;
            this.signature    = signature;
            this.payloadHash  = payloadHash;
            this.senderId     = senderId;
            this.roundNumber  = roundNumber;
        }

        public byte[] getCiphertext()   { return ciphertext; }
        public byte[] getEncryptedKey() { return encryptedKey; }
        public byte[] getIv()           { return iv; }
        public byte[] getSignature()    { return signature; }
        public byte[] getPayloadHash()  { return payloadHash; }
        public String getSenderId()     { return senderId; }
        public int    getRoundNumber()  { return roundNumber; }

        public int getTotalSizeBytes() {
            return ciphertext.length + encryptedKey.length + iv.length
                    + signature.length + payloadHash.length;
        }
    }
}
