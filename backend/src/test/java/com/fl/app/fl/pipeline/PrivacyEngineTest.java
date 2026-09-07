package com.fl.app.fl.pipeline;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for PrivacyEngine — Gaussian mechanism, sigma calibration, epsilon accounting.
 */
class PrivacyEngineTest {



    private PrivacyEngine engine;

    @BeforeEach
    void setUp() {
        engine = new PrivacyEngine();
    }

    // ─── Sigma calibration ────────────────────────────────────────────────────

    @Test
    @DisplayName("applyNoise does not change array dimensions")
    void applyNoise_preservesDimensions() {
        double[] weights = {0.1, 0.2, 0.3, 0.4, 0.5};
        var mw = new LocalTrainer.ModelWeights(weights, 0.5, 100, 1, "h0");

        LocalTrainer.ModelWeights noisy = engine.applyNoise(mw, 1.0);

        assertEquals(weights.length, noisy.weights().length, "Weight count must not change after noise");
        assertEquals(mw.sampleCount(), noisy.sampleCount());
        assertEquals(mw.round(),       noisy.round());
        assertEquals(mw.clientId(),    noisy.clientId());
    }

    @Test
    @DisplayName("computeEpsilonConsumed returns per-round epsilon (not total budget)")
    void computeEpsilonConsumed_matchesInputEpsilon() {
        double perRoundEpsilon = 0.2;  // e.g. budget=1.0 / numRounds=5
        var mw = new LocalTrainer.ModelWeights(new double[]{0.5}, 0.0, 100, 1, "h0");

        engine.applyNoise(mw, perRoundEpsilon);
        double consumed = engine.computeEpsilonConsumed();

        assertEquals(perRoundEpsilon, consumed, 1e-9,
            "computeEpsilonConsumed must return perRoundEpsilon (derived from sigma), not total budget");
    }

    @Test
    @DisplayName("computeEpsilonConsumed returns 0 before applyNoise is called")
    void computeEpsilonConsumed_beforeApplyNoise_returnsZero() {
        assertEquals(0.0, engine.computeEpsilonConsumed(),
            "Should return 0 when applyNoise has not been called yet");
    }

    @Test
    @DisplayName("Smaller epsilon → larger sigma (more noise for tighter privacy)")
    void smallerEpsilon_producesDifferentNoise() {
        // With deterministic seed we can verify noise scale increases with smaller epsilon
        double[] weights = new double[100];  // all zeros
        var mw = new LocalTrainer.ModelWeights(weights, 0.0, 100, 1, "h0");

        LocalTrainer.ModelWeights highPrivacy = new PrivacyEngine().applyNoise(mw, 0.01);  // tight ε
        LocalTrainer.ModelWeights lowPrivacy  = new PrivacyEngine().applyNoise(mw, 10.0); // loose ε

        // Average absolute noise should be larger for tight privacy (smaller ε)
        double highPrivacyNoise = averageAbsNoise(weights, highPrivacy.weights());
        double lowPrivacyNoise  = averageAbsNoise(weights, lowPrivacy.weights());

        assertTrue(highPrivacyNoise > lowPrivacyNoise,
            "Tighter privacy (smaller epsilon) must add more noise. Got: high=" + highPrivacyNoise + " low=" + lowPrivacyNoise);
    }

    @Test
    @DisplayName("applyNoise with epsilon=1.0/5 rounds tracks to budget correctly")
    void perRoundEpsilon_composesToTotalBudget() {
        double totalBudget = 1.0;
        int    numRounds   = 5;
        double perRound    = totalBudget / numRounds;

        double totalConsumed = 0.0;
        for (int r = 0; r < numRounds; r++) {
            PrivacyEngine pe = new PrivacyEngine();
            var mw = new LocalTrainer.ModelWeights(new double[]{0.5}, 0.0, 100, r, "h0");
            pe.applyNoise(mw, perRound);
            totalConsumed += pe.computeEpsilonConsumed();
        }

        assertEquals(totalBudget, totalConsumed, 1e-9,
            "Sum of per-round epsilon must equal total privacy budget");
    }

    @Test
    @DisplayName("applyNoise preserves metadata (sampleCount, round, clientId)")
    void applyNoise_preservesMetadata() {
        var mw = new LocalTrainer.ModelWeights(new double[]{0.1}, 1.0, 250, 7, "hospital-3");
        LocalTrainer.ModelWeights noisy = engine.applyNoise(mw, 0.5);

        assertEquals(250,          noisy.sampleCount());
        assertEquals(7,            noisy.round());
        assertEquals("hospital-3", noisy.clientId());
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static double averageAbsNoise(double[] original, double[] noisy) {
        double sum = 0;
        for (int i = 0; i < original.length; i++) {
            sum += Math.abs(noisy[i] - original[i]);
        }
        return sum / original.length;
    }
}
