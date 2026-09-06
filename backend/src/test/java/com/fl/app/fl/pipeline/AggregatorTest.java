package com.fl.app.fl.pipeline;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for Aggregator — FedAvg weighted aggregation, Byzantine rejection, edge cases.
 */
class AggregatorTest {

    private static final String TEST_KEY =
        "7a3f2c1e8b5d9f4023ac5e7b90d12f47a8c3e5f1d92b74e60a1f3c5e8b2d4f69";

    private SecurityLayer securityLayer;

    @BeforeEach
    void setUp() {
        securityLayer = new SecurityLayer(TEST_KEY);
    }

    // ─── FedAvg correctness ───────────────────────────────────────────────────

    @Test
    @DisplayName("Single client — global weights equal client weights exactly")
    void aggregate_singleClient_globalEqualsClient() {
        double[] w = {0.3, 0.5, -0.1};
        var update = securityLayer.secure(
            new LocalTrainer.ModelWeights(w, 0.7, 100, 1, "h0"));

        var result = Aggregator.aggregate(List.of(update), 1, securityLayer);

        assertEquals(1, result.acceptedClients());
        assertEquals(0, result.rejectedClients());
        assertArrayEquals(w, result.globalWeights().weights(), 1e-12,
            "With one client, FedAvg must return that client's weights unchanged");
        assertEquals(0.7, result.globalWeights().bias(), 1e-12);
    }

    @Test
    @DisplayName("Equal-sample clients — global weights are unweighted average")
    void aggregate_equalSamples_producesUnweightedAverage() {
        double[] wA = {1.0, 0.0};
        double[] wB = {0.0, 1.0};

        var updateA = securityLayer.secure(new LocalTrainer.ModelWeights(wA, 0.2, 100, 1, "h0"));
        var updateB = securityLayer.secure(new LocalTrainer.ModelWeights(wB, 0.4, 100, 1, "h1"));

        var result = Aggregator.aggregate(List.of(updateA, updateB), 1, securityLayer);

        // FedAvg with equal samples → simple average
        assertArrayEquals(new double[]{0.5, 0.5}, result.globalWeights().weights(), 1e-12,
            "Equal-sample FedAvg must produce simple average");
        assertEquals(0.3, result.globalWeights().bias(), 1e-12);
        assertEquals(2, result.acceptedClients());
    }

    @Test
    @DisplayName("FedAvg is sample-count weighted — larger dataset has more influence")
    void aggregate_weightedBySampleCount() {
        // Client A: 200 samples, weight=1.0
        // Client B: 100 samples, weight=0.0
        // Expected global = (200*1.0 + 100*0.0) / 300 = 0.6666...
        double[] wA = {1.0};
        double[] wB = {0.0};

        var updateA = securityLayer.secure(new LocalTrainer.ModelWeights(wA, 0.0, 200, 1, "h0"));
        var updateB = securityLayer.secure(new LocalTrainer.ModelWeights(wB, 0.0, 100, 1, "h1"));

        var result = Aggregator.aggregate(List.of(updateA, updateB), 1, securityLayer);

        assertEquals(2.0 / 3.0, result.globalWeights().weights()[0], 1e-10,
            "FedAvg weight for larger client must dominate");
    }

    @Test
    @DisplayName("Tampered update is rejected, clean ones still aggregate")
    void aggregate_tamperedUpdate_isRejected() {
        double[] goodW = {0.5, 0.5};
        var good = securityLayer.secure(
            new LocalTrainer.ModelWeights(goodW, 0.0, 100, 1, "h0"));

        // Construct a tampered update — corrupt the ciphertext
        byte[] corruptedCipher = good.encryptedWeights().clone();
        corruptedCipher[15] ^= 0xFF;
        var bad = new SecurityLayer.SecuredUpdate(
            corruptedCipher, good.hash(), "malicious", 100, 1);

        var result = Aggregator.aggregate(List.of(good, bad), 1, securityLayer);

        assertEquals(1, result.acceptedClients(), "Only the clean update should be accepted");
        assertEquals(1, result.rejectedClients(), "Tampered update must be counted as rejected");
        assertTrue(result.rejectedClientIds().contains("malicious"));
    }

    @Test
    @DisplayName("All clients rejected → throws IllegalStateException")
    void aggregate_allRejected_throwsIllegalState() {
        byte[] garbage = new byte[60];
        var bad = new SecurityLayer.SecuredUpdate(
            garbage, "badhash", "hacker", 100, 1);

        assertThrows(IllegalStateException.class,
            () -> Aggregator.aggregate(List.of(bad), 1, securityLayer),
            "Aggregation with zero valid clients must throw");
    }

    @Test
    @DisplayName("totalSamples in global weights is sum of all accepted clients")
    void aggregate_globalSampleCount_isSumOfAccepted() {
        var u1 = securityLayer.secure(new LocalTrainer.ModelWeights(new double[]{0.1}, 0.0, 80,  1, "h0"));
        var u2 = securityLayer.secure(new LocalTrainer.ModelWeights(new double[]{0.2}, 0.0, 120, 1, "h1"));

        var result = Aggregator.aggregate(List.of(u1, u2), 1, securityLayer);

        assertEquals(200, result.globalWeights().sampleCount(),
            "Global sampleCount must equal sum of all accepted client sample counts");
    }
}
