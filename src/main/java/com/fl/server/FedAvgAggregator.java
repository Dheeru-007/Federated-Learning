package com.fl.server;

import com.fl.model.ModelWeights;
import java.util.*;

/**
 * FedAvgAggregator — Weighted federated averaging aggregation algorithm.
 *
 * Implements the FedAvg algorithm (McMahan et al., 2017) as the global model
 * update strategy. Aggregates model weight updates from multiple client nodes,
 * weighting each client's contribution proportionally to its local dataset size.
 *
 * Formula:
 *   w_global = Σ (n_k / n_total) * w_k
 *
 * where n_k is the number of training samples at client k and n_total is
 * the total training samples across all participating clients.
 *
 * Additionally implements:
 *   - Byzantine fault tolerance via coordinate-wise median filtering
 *   - Participation threshold enforcement (minimum client percentage)
 *   - Per-round convergence tracking (loss delta)
 *
 * Parameters matching Ren et al. (2024), Table 1:
 *   - Aggregation frequency:   50 rounds
 *   - Participant threshold:   80%
 *   - Local epochs:            5-10
 */
public class FedAvgAggregator {

    /** Minimum fraction of clients that must participate in a round. */
    private final double participationThreshold;

    /** Whether to apply Byzantine-robust median filtering. */
    private final boolean byzantineRobust;

    /** Tracks loss history for convergence analysis. */
    private final List<Double> lossHistory;

    /** Tracks accuracy history per round. */
    private final List<Double> accuracyHistory;

    /** Tracks communication overhead per round (total bytes). */
    private final List<Long> communicationOverhead;

    private int totalRoundsCompleted = 0;

    public FedAvgAggregator(double participationThreshold, boolean byzantineRobust) {
        this.participationThreshold = participationThreshold;
        this.byzantineRobust        = byzantineRobust;
        this.lossHistory            = new ArrayList<>();
        this.accuracyHistory        = new ArrayList<>();
        this.communicationOverhead  = new ArrayList<>();
    }

    /**
     * Aggregate model updates from all participating clients using FedAvg.
     *
     * @param clientUpdates  Map of clientId -> ModelWeights
     * @param totalClients   Total number of registered clients (for threshold check)
     * @param currentGlobal  Current global model weights (returned unchanged if threshold not met)
     * @return Aggregated global model weights, or null if participation threshold not met
     */
    public ModelWeights aggregate(Map<String, ModelWeights> clientUpdates,
                                  int totalClients,
                                  ModelWeights currentGlobal,
                                  int round) {

        int participating = clientUpdates.size();
        double participationRate = (double) participating / totalClients;

        System.out.printf("%n[FedAvg] Round %d aggregation: %d/%d clients (%.0f%%)%n",
                round, participating, totalClients, participationRate * 100);

        // Enforce participation threshold
        if (participationRate < participationThreshold) {
            System.out.printf("[FedAvg] WARNING: Participation %.1f%% below threshold %.1f%%. " +
                    "Skipping round.%n", participationRate * 100, participationThreshold * 100);
            return null;
        }

        List<ModelWeights> updates = new ArrayList<>(clientUpdates.values());

        // Byzantine filtering before aggregation
        if (byzantineRobust && updates.size() >= 3) {
            updates = byzantineFilter(updates);
            System.out.printf("[FedAvg] Byzantine filter: %d/%d updates accepted%n",
                    updates.size(), participating);
        }

        // Compute total weighted sample count
        long totalSamples = updates.stream()
                .mapToLong(ModelWeights::getSampleCount)
                .sum();

        if (totalSamples == 0) {
            System.err.println("[FedAvg] ERROR: All updates have 0 samples. Aborting.");
            return currentGlobal;
        }

        int featureCount = updates.get(0).getFeatureCount();
        double[] aggregatedWeights = new double[featureCount];
        double aggregatedBias = 0.0;
        double totalEpsilonConsumed = 0.0;

        // Weighted FedAvg: w_global = Σ (n_k / n_total) * w_k
        for (ModelWeights update : updates) {
            double clientWeight = (double) update.getSampleCount() / totalSamples;

            double[] w = update.getWeights();
            for (int i = 0; i < featureCount; i++) {
                aggregatedWeights[i] += clientWeight * w[i];
            }
            aggregatedBias += clientWeight * update.getBias();
            totalEpsilonConsumed += update.getEpsilonConsumed();
        }

        ModelWeights globalWeights = new ModelWeights(
                aggregatedWeights, aggregatedBias,
                (int) totalSamples, round,
                "GlobalAggregator",
                totalEpsilonConsumed / updates.size()  // Average epsilon consumed
        );

        totalRoundsCompleted++;

        printAggregationSummary(clientUpdates, updates.size(), totalSamples,
                totalEpsilonConsumed, round);

        return globalWeights;
    }

    /**
     * Byzantine-robust filtering using coordinate-wise median.
     * Removes updates whose L2 distance from the geometric median exceeds 2 standard deviations.
     * This detects and excludes poisoning attacks or severely corrupted clients.
     */
    private List<ModelWeights> byzantineFilter(List<ModelWeights> updates) {
        if (updates.size() < 3) return updates;

        int featureCount = updates.get(0).getFeatureCount();

        // Compute element-wise mean and std across all updates
        double[] mean = new double[featureCount];
        for (ModelWeights mw : updates) {
            double[] w = mw.getWeights();
            for (int i = 0; i < featureCount; i++) mean[i] += w[i];
        }
        for (int i = 0; i < featureCount; i++) mean[i] /= updates.size();

        // Compute L2 distance of each update from mean
        double[] distances = new double[updates.size()];
        for (int k = 0; k < updates.size(); k++) {
            double[] w = updates.get(k).getWeights();
            double dist = 0;
            for (int i = 0; i < featureCount; i++) {
                double diff = w[i] - mean[i];
                dist += diff * diff;
            }
            distances[k] = Math.sqrt(dist);
        }

        // Compute mean and std of distances
        double meanDist = Arrays.stream(distances).average().orElse(0);
        double stdDist  = Math.sqrt(Arrays.stream(distances)
                .map(d -> (d - meanDist) * (d - meanDist))
                .average().orElse(0));

        // Filter out updates beyond 2 standard deviations
        double threshold = meanDist + 2.0 * stdDist;
        List<ModelWeights> filtered = new ArrayList<>();
        for (int k = 0; k < updates.size(); k++) {
            if (distances[k] <= threshold) {
                filtered.add(updates.get(k));
            } else {
                System.out.printf("[FedAvg] Byzantine filter: EXCLUDED update from '%s' " +
                        "(distance=%.4f, threshold=%.4f)%n",
                        updates.get(k).getClientId(), distances[k], threshold);
            }
        }
        return filtered.isEmpty() ? updates : filtered;
    }

    /**
     * Check convergence: return true if loss delta over last 5 rounds is below threshold.
     */
    public boolean hasConverged(double convergenceThreshold) {
        if (lossHistory.size() < 5) return false;
        int n = lossHistory.size();
        double recent = lossHistory.get(n - 1);
        double prior  = lossHistory.get(n - 5);
        return Math.abs(prior - recent) / (Math.abs(prior) + 1e-10) < convergenceThreshold;
    }

    // ── Metrics recording ─────────────────────────────────────────────────

    public void recordMetrics(double loss, double accuracy, long bytesTransmitted) {
        lossHistory.add(loss);
        accuracyHistory.add(accuracy);
        communicationOverhead.add(bytesTransmitted);
    }

    private void printAggregationSummary(Map<String, ModelWeights> allUpdates,
                                         int acceptedCount, long totalSamples,
                                         double totalEpsilon, int round) {
        System.out.printf("[FedAvg] Round %d summary:%n", round);
        System.out.printf("         Updates accepted:    %d%n", acceptedCount);
        System.out.printf("         Total samples:       %,d%n", totalSamples);
        System.out.printf("         Avg epsilon/client:  %.4f%n", totalEpsilon / acceptedCount);
    }

    // ── Reporting ─────────────────────────────────────────────────────────

    public void printFinalReport() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("  FEDAVG AGGREGATOR — FINAL REPORT");
        System.out.println("=".repeat(60));
        System.out.printf("  Total rounds completed: %d%n", totalRoundsCompleted);
        if (!accuracyHistory.isEmpty()) {
            System.out.printf("  Best accuracy:          %.2f%%%n",
                    accuracyHistory.stream().mapToDouble(d -> d).max().orElse(0) * 100);
            System.out.printf("  Final accuracy:         %.2f%%%n",
                    accuracyHistory.get(accuracyHistory.size() - 1) * 100);
        }
        if (!lossHistory.isEmpty()) {
            System.out.printf("  Final loss:             %.4f%n",
                    lossHistory.get(lossHistory.size() - 1));
            System.out.printf("  Converged:              %s%n",
                    hasConverged(0.001) ? "YES" : "NO");
        }
        if (!communicationOverhead.isEmpty()) {
            long totalBytes = communicationOverhead.stream().mapToLong(l -> l).sum();
            System.out.printf("  Total communication:    %,.0f KB%n", totalBytes / 1024.0);
        }
        System.out.println("=".repeat(60));
    }

    public List<Double> getLossHistory()     { return Collections.unmodifiableList(lossHistory); }
    public List<Double> getAccuracyHistory() { return Collections.unmodifiableList(accuracyHistory); }
    public int getTotalRoundsCompleted()     { return totalRoundsCompleted; }
}
