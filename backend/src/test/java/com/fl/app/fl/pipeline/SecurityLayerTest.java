package com.fl.app.fl.pipeline;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for SecurityLayer — AES-256-GCM, ByteBuffer serialization, SHA-256.
 * Uses a valid 32-byte (64 hex char) test key; no Spring context needed.
 */
class SecurityLayerTest {

    // Valid 64-char hex key for testing (32 bytes = 256-bit AES)
    private static final String TEST_KEY = "7a3f2c1e8b5d9f4023ac5e7b90d12f47a8c3e5f1d92b74e60a1f3c5e8b2d4f69";

    private SecurityLayer securityLayer;

    @BeforeEach
    void setUp() {
        securityLayer = new SecurityLayer(TEST_KEY);
    }

    // ─── Round-trip ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("secure() then verify() round-trips weights and bias exactly")
    void roundTrip_preservesWeightsAndBias() {
        double[] weights = {0.1, -0.2, 0.35, 1.0, -9.99};
        double   bias    = 0.75;
        var original = new LocalTrainer.ModelWeights(weights, bias, 100, 3, "hospital-0");

        SecurityLayer.SecuredUpdate secured = securityLayer.secure(original);
        LocalTrainer.ModelWeights   recovered = securityLayer.verify(secured);

        assertArrayEquals(weights, recovered.weights(), 1e-12,
            "Weights must survive encrypt→decrypt without change");
        assertEquals(bias, recovered.bias(), 1e-12, "Bias must survive encrypt→decrypt");
        assertEquals("hospital-0", recovered.clientId());
        assertEquals(100, recovered.sampleCount());
        assertEquals(3, recovered.round());
    }

    @Test
    @DisplayName("Each encrypt call uses a fresh IV — same input produces different ciphertext")
    void encrypt_usesFreshIvEachCall() {
        double[] weights = {0.5};
        var mw = new LocalTrainer.ModelWeights(weights, 0.0, 10, 1, "c0");

        SecurityLayer.SecuredUpdate a = securityLayer.secure(mw);
        SecurityLayer.SecuredUpdate b = securityLayer.secure(mw);

        // Ciphertexts must differ because IV is random
        assertFalse(java.util.Arrays.equals(a.encryptedWeights(), b.encryptedWeights()),
            "Two encryptions of the same data must produce different ciphertexts (IND-CPA)");
    }

    @Test
    @DisplayName("Hash mismatch throws SecurityException, not a generic exception")
    void verify_hashMismatch_throwsSecurityException() {
        var mw = new LocalTrainer.ModelWeights(new double[]{0.1}, 0.0, 50, 1, "c1");
        SecurityLayer.SecuredUpdate secured = securityLayer.secure(mw);

        // Tamper with the hash
        SecurityLayer.SecuredUpdate tampered = new SecurityLayer.SecuredUpdate(
            secured.encryptedWeights(),
            "deadbeef" + secured.hash().substring(8),  // corrupt the hash
            secured.clientId(),
            secured.sampleCount(),
            secured.round()
        );

        assertThrows(SecurityException.class, () -> securityLayer.verify(tampered),
            "Tampered hash must throw SecurityException");
    }

    @Test
    @DisplayName("Bit-flip in ciphertext causes AEADBadTagException (GCM integrity)")
    void verify_tamperedCiphertext_throwsException() {
        var mw = new LocalTrainer.ModelWeights(new double[]{0.1, 0.2}, 0.5, 50, 1, "c2");
        SecurityLayer.SecuredUpdate secured = securityLayer.secure(mw);

        // Flip a byte in the ciphertext (after the 12-byte IV)
        byte[] tampered = secured.encryptedWeights().clone();
        tampered[15] ^= (byte) 0xFF;

        SecurityLayer.SecuredUpdate tamperedUpdate = new SecurityLayer.SecuredUpdate(
            tampered, secured.hash(), secured.clientId(), secured.sampleCount(), secured.round()
        );

        // GCM tag check will fail before we even reach the hash check
        assertThrows(Exception.class, () -> securityLayer.verify(tamperedUpdate),
            "Bit-flip in ciphertext must be detected by GCM authentication tag");
    }

    @Test
    @DisplayName("Short AES key (< 32 bytes) is rejected at construction time")
    void constructor_shortKey_throws() {
        // 62 hex chars = 31 bytes — not 32
        String shortKey = "7a3f2c1e8b5d9f4023ac5e7b90d12f47a8c3e5f1d92b74e60a1f3c5e8b2d4f";
        assertThrows(IllegalArgumentException.class, () -> new SecurityLayer(shortKey));
    }

    @Test
    @DisplayName("Odd-length hex key is rejected at construction time")
    void constructor_oddLengthHex_throws() {
        assertThrows(IllegalArgumentException.class, () -> new SecurityLayer("abc"));
    }

    @Test
    @DisplayName("Large weight vector (1000 features) round-trips correctly")
    void roundTrip_largeWeightVector() {
        double[] weights = new double[1000];
        for (int i = 0; i < weights.length; i++) weights[i] = i * 0.001 - 0.5;
        var mw = new LocalTrainer.ModelWeights(weights, -0.123, 5000, 10, "hosp-99");

        SecurityLayer.SecuredUpdate secured = securityLayer.secure(mw);
        LocalTrainer.ModelWeights   recovered = securityLayer.verify(secured);

        assertArrayEquals(weights, recovered.weights(), 1e-12);
        assertEquals(-0.123, recovered.bias(), 1e-12);
    }

    // ─── SHA-256 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sha256 produces a deterministic 64-char lowercase hex string")
    void sha256_deterministicAndCorrectLength() {
        byte[] data = "hello world".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash1 = SecurityLayer.sha256(data);
        String hash2 = SecurityLayer.sha256(data);

        assertEquals(64, hash1.length(), "SHA-256 must produce 64 hex chars");
        assertTrue(hash1.matches("[0-9a-f]{64}"), "SHA-256 must be lowercase hex");
        assertEquals(hash1, hash2, "SHA-256 must be deterministic");
    }

    @Test
    @DisplayName("sha256 is collision resistant — different inputs produce different hashes")
    void sha256_differentInputs_differentHashes() {
        String h1 = SecurityLayer.sha256("input-A".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String h2 = SecurityLayer.sha256("input-B".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertNotEquals(h1, h2, "Different inputs must produce different SHA-256 hashes");
    }

    @Test
    @DisplayName("sha256 of empty byte array returns consistent hash (not an exception)")
    void sha256_emptyInput_doesNotThrow() {
        assertDoesNotThrow(() -> SecurityLayer.sha256(new byte[0]));
        assertEquals(64, SecurityLayer.sha256(new byte[0]).length());
    }
}
