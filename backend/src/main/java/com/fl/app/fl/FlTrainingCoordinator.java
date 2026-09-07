package com.fl.app.fl;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fl.app.api.ws.RoundUpdateMessage;
import com.fl.app.api.ws.TrainingEventPublisher;
import com.fl.app.domain.ClientMetric;
import com.fl.app.domain.RoundMetric;
import com.fl.app.domain.TrainingSession;
import com.fl.app.domain.TrainingSession.Status;
import com.fl.app.domain.UploadedDataset;
import com.fl.app.fl.pipeline.Aggregator;
import com.fl.app.fl.pipeline.DataCleaner;
import com.fl.app.fl.pipeline.DataIngestion;
import com.fl.app.fl.pipeline.DataNormalizer;
import com.fl.app.fl.pipeline.Evaluator;
import com.fl.app.fl.pipeline.LocalTrainer;
import com.fl.app.fl.pipeline.PrivacyEngine;
import com.fl.app.fl.pipeline.SecurityLayer;
import com.fl.app.persistence.ClientMetricRepository;
import com.fl.app.persistence.RoundMetricRepository;
import com.fl.app.persistence.TrainingSessionRepository;
import com.fl.app.persistence.UploadedDatasetRepository;

@Service
@SuppressWarnings("null")
public class FlTrainingCoordinator {

    private static final Logger log = LoggerFactory.getLogger(FlTrainingCoordinator.class);
    private static final Set<Long> ACTIVE_SESSIONS = ConcurrentHashMap.newKeySet();
    private static final double EARLY_STOPPING_ACCURACY = 0.95;

    private final TrainingSessionRepository sessionRepository;
    private final RoundMetricRepository roundMetricRepository;
    private final ClientMetricRepository clientMetricRepository;
    private final UploadedDatasetRepository datasetRepository;
    private final TrainingEventPublisher eventPublisher;
    private final ApplicationContext applicationContext;
    private final SecurityLayer securityLayer;

    public FlTrainingCoordinator(
            TrainingSessionRepository sessionRepository,
            RoundMetricRepository roundMetricRepository,
            ClientMetricRepository clientMetricRepository,
            UploadedDatasetRepository datasetRepository,
            TrainingEventPublisher eventPublisher,
            ApplicationContext applicationContext,
            SecurityLayer securityLayer) {

        this.sessionRepository = sessionRepository;
        this.roundMetricRepository = roundMetricRepository;
        this.clientMetricRepository = clientMetricRepository;
        this.datasetRepository = datasetRepository;
        this.eventPublisher = eventPublisher;
        this.applicationContext = applicationContext;
        this.securityLayer = securityLayer;
    }

    public TrainingSession startTraining(TrainingConfig config, String createdBy) {
        String sessionName = config.getSessionName() == null || config.getSessionName().isBlank()
                ? "FL Session " + System.currentTimeMillis()
                : config.getSessionName();

        TrainingSession session = TrainingSession.builder()
                .name(sessionName)
                .createdBy(createdBy)
                .status(Status.RUNNING)             // pre-set RUNNING synchronously — avoids stale PENDING response
                .startedAt(LocalDateTime.now())
                .numHospitals(config.getNumHospitals())
                .numRounds(config.getNumRounds())
                .privacyBudget(config.getPrivacyBudget())
                .dataSource(config.getDataSource())
                .maliciousClientEnabled(config.isMaliciousClientEnabled())
                .build();

        TrainingSession saved = sessionRepository.save(session);

        applicationContext
                .getBean(FlTrainingCoordinator.class)
                .runTrainingAsync(saved.getId(), config);

        return saved;
    }

    public TrainingSession getSessionStatus(Long sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found"));
    }

    public void runTrainingAsync(Long sessionId, TrainingConfig config) {
        applicationContext
                .getBean(FlTrainingCoordinator.class)
                .runTraining(sessionId, config);
    }

    @Async
    public void runTraining(Long sessionId, TrainingConfig config) {
        if (!ACTIVE_SESSIONS.add(sessionId)) {
            log.warn("Training already running for session {}", sessionId);
            return;
        }

        try {
            TrainingSession session = sessionRepository.findById(sessionId)
                    .orElseThrow(() -> new IllegalArgumentException("Session not found"));

            // Resolve training + validation data together (thread-safe, no shared state)
            ResolvedData resolvedData = resolveAllData(session);
            HospitalData[] hospitalData = resolvedData.trainingData();
            EvaluationDataset evaluationDataset = resolvedData.validationData();

            int featureCount = validateAndResolveFeatureCount(hospitalData);

            // Compute global normalization params across ALL hospitals
            GlobalNormParams globalNorm = computeGlobalNormParams(hospitalData, featureCount);

            // Normalize the validation set with the SAME global params
            double[][] normValFeatures = DataNormalizer.normalizeWithParams(
                    evaluationDataset.features(), globalNorm.min(), globalNorm.max());
            EvaluationDataset normValDataset = new EvaluationDataset(
                    normValFeatures, evaluationDataset.labels());

            // featureCount is resolved here in the async thread after data is loaded
            session.setFeatureCount(featureCount);
            sessionRepository.save(session);
            // Note: status is already RUNNING (set synchronously in the caller before dispatch)

            log.info("Starting training for session {} with {} hospitals and {} rounds",
                    sessionId, hospitalData.length, session.getNumRounds());
            eventPublisher.publishSessionStatus(sessionId, "RUNNING", "Training started");

            LocalTrainer.ModelWeights globalWeights =
                    new LocalTrainer.ModelWeights(new double[featureCount], 0.0, 0, 0, "Global");

            double finalAcc = 0;
            double finalLoss = 0;

            for (int round = 1; round <= session.getNumRounds(); round++) {
                log.info("Starting round {} for session {}", round, sessionId);

                RoundArtifacts roundArtifacts = executeRound(
                        session, config, hospitalData, featureCount,
                        globalWeights, round, globalNorm);

                clientMetricRepository.saveAll(roundArtifacts.clientMetrics());

                Aggregator.AggregationResult result =
                        Aggregator.aggregate(roundArtifacts.securedUpdates(), round, securityLayer);

                globalWeights = result.globalWeights();

                Evaluator.EvaluationResult eval =
                        Evaluator.evaluate(
                                globalWeights,
                                normValDataset.features(),
                                normValDataset.labels());

                finalAcc = eval.accuracy();
                finalLoss = eval.loss();

                long communication = roundArtifacts.totalCommunicationBytes();

                double perRoundEpsilon = config.getPrivacyBudget() / config.getNumRounds();

                RoundMetric metric = RoundMetric.builder()
                        .session(session)
                        .roundNumber(round)
                        .globalAccuracy(finalAcc)
                        .globalLoss(finalLoss)
                        .numClients(result.acceptedClients())
                        .bytesTransferred(communication)
                        .epsilonConsumed(perRoundEpsilon)  // per-round ε, not total budget
                        .build();

                roundMetricRepository.save(metric);

                eventPublisher.publishRoundUpdate(
                        RoundUpdateMessage.builder()
                                .sessionId(sessionId)
                                .roundNumber(round)
                                .totalRounds(session.getNumRounds())
                                .globalAccuracy(finalAcc)
                                .globalLoss(finalLoss)
                                .epsilonConsumed(perRoundEpsilon)  // per-round ε, not total budget
                                .bytesTransferred(communication)
                                .numClients(result.acceptedClients())
                                .status("RUNNING")
                                .message("Round " + round + " completed")
                                .build());

                log.info("Completed round {} for session {} — acc={} loss={}",
                        round, sessionId, finalAcc, finalLoss);

                if (finalAcc > EARLY_STOPPING_ACCURACY) {
                    log.info("Early stopping triggered at round {} for session {}", round, sessionId);
                    break;
                }
            }

            session.setStatus(Status.COMPLETED);
            session.setFinishedAt(LocalDateTime.now());
            session.setFinalAccuracy(finalAcc);
            session.setFinalLoss(finalLoss);
            sessionRepository.save(session);

            eventPublisher.publishSessionStatus(sessionId, "COMPLETED", "Training completed");
            log.info("Training completed for session {}", sessionId);

        } catch (Exception e) {
            log.error("Training failed for session {}", sessionId, e);
            markSessionFailed(sessionId);
            eventPublisher.publishSessionStatus(sessionId, "FAILED", e.getMessage());
        } finally {
            ACTIVE_SESSIONS.remove(sessionId);
        }
    }

    // -----------------------------------------------------------------------
    //  Data resolution — returns training + validation together (thread-safe)
    // -----------------------------------------------------------------------

    /**
     * Single entry point that resolves BOTH training data and validation data
     * as a local return value. No instance fields are used, so concurrent
     * sessions cannot interfere with each other.
     */
    private ResolvedData resolveAllData(TrainingSession session) {
        if (session.getDataSource() == TrainingSession.DataSource.CSV) {
            return loadCSVData(session);
        } else {
            HospitalData[] trainingData = generateSimulatedData(session.getNumHospitals());
            EvaluationDataset validationData = generateSimulatedValidation();
            return new ResolvedData(trainingData, validationData);
        }
    }

    // -----------------------------------------------------------------------
    //  Validation & normalization helpers
    // -----------------------------------------------------------------------

    private int validateAndResolveFeatureCount(HospitalData[] hospitalData) {
        int featureCount = -1;

        for (HospitalData hospital : hospitalData) {
            if (hospital.features().length == 0 || hospital.labels().length == 0) {
                throw new IllegalStateException("Hospital dataset is empty: " + hospital.name());
            }

            if (hospital.features().length != hospital.labels().length) {
                throw new IllegalStateException(
                        "Feature/label size mismatch for hospital: " + hospital.name());
            }

            int currentFeatureCount = hospital.features()[0].length;
            if (currentFeatureCount == 0) {
                throw new IllegalStateException(
                        "Hospital dataset has zero features: " + hospital.name());
            }

            for (double[] row : hospital.features()) {
                if (row.length != currentFeatureCount) {
                    throw new IllegalStateException(
                            "Inconsistent feature width for hospital: " + hospital.name());
                }
            }

            if (featureCount == -1) {
                featureCount = currentFeatureCount;
            } else if (featureCount != currentFeatureCount) {
                throw new IllegalStateException("Mismatched feature counts across hospitals");
            }
        }

        return featureCount;
    }

    /**
     * Compute GLOBAL min/max across ALL hospitals so every client normalizes
     * with the same parameters. This ensures FedAvg averages models trained
     * on identically-scaled features.
     */
    private GlobalNormParams computeGlobalNormParams(
            HospitalData[] hospitalData, int featureCount) {

        double[] globalMin = new double[featureCount];
        double[] globalMax = new double[featureCount];

        Arrays.fill(globalMin, Double.MAX_VALUE);
        Arrays.fill(globalMax, -Double.MAX_VALUE);

        for (HospitalData hospital : hospitalData) {
            for (double[] row : hospital.features()) {
                for (int j = 0; j < featureCount; j++) {
                    if (row[j] < globalMin[j]) globalMin[j] = row[j];
                    if (row[j] > globalMax[j]) globalMax[j] = row[j];
                }
            }
        }

        return new GlobalNormParams(globalMin, globalMax);
    }

    // -----------------------------------------------------------------------
    //  Round execution
    // -----------------------------------------------------------------------

    private RoundArtifacts executeRound(
            TrainingSession session,
            TrainingConfig config,
            HospitalData[] hospitalData,
            int featureCount,
            LocalTrainer.ModelWeights globalWeights,
            int round,
            GlobalNormParams globalNorm) {

        List<ClientRoundResult> clientResults = IntStream.range(0, hospitalData.length)
                .parallel()
                .mapToObj(clientId -> trainClient(
                        session, config, hospitalData[clientId],
                        featureCount, globalWeights, round, clientId, globalNorm))
                .sorted(Comparator.comparingInt(ClientRoundResult::clientId))
                .toList();

        List<SecurityLayer.SecuredUpdate> securedUpdates = new ArrayList<>(clientResults.size());
        List<ClientMetric> clientMetrics = new ArrayList<>(clientResults.size());
        long totalCommunicationBytes = 0L;

        for (ClientRoundResult result : clientResults) {
            securedUpdates.add(result.securedUpdate());
            clientMetrics.add(result.clientMetric());
            totalCommunicationBytes += result.clientMetric().getBytesSent();
        }

        // Add malicious client if enabled
        if (session.isMaliciousClientEnabled()) {
            log.info("Injecting malicious client for session {}", session.getId());
            byte[] fakeEncrypted = new byte[64];
            securedUpdates.add(new SecurityLayer.SecuredUpdate(
                    fakeEncrypted, "fakehash123", "MaliciousClient", 100, round));
        }

        return new RoundArtifacts(securedUpdates, clientMetrics, totalCommunicationBytes);
    }

    private ClientRoundResult trainClient(
            TrainingSession session,
            TrainingConfig config,
            HospitalData hospital,
            int featureCount,
            LocalTrainer.ModelWeights globalWeights,
            int round,
            int clientId,
            GlobalNormParams globalNorm) {

        DataCleaner.CleanedDataset cleaned =
                DataCleaner.clean(hospital.features(), hospital.labels());

        if (cleaned.features().length == 0 || cleaned.labels().length == 0) {
            throw new IllegalStateException(
                    "Cleaning removed all rows for hospital: " + hospital.name());
        }

        // Normalize with GLOBAL min/max — same params for every hospital
        double[][] normalizedFeatures = DataNormalizer.normalizeWithParams(
                cleaned.features(), globalNorm.min(), globalNorm.max());

        LocalTrainer trainer = new LocalTrainer(featureCount);
        trainer.loadWeights(globalWeights);

        LocalTrainer.ModelWeights rawWeights = trainer.train(
                normalizedFeatures,
                cleaned.labels(),
                config.getLocalEpochs(),
                hospital.name(),
                round);

        double localAccuracy = trainer.evaluate(normalizedFeatures, cleaned.labels());

        // Per-round epsilon: divide total budget evenly across rounds
        // This ensures simple composition ε_total = numRounds × ε_per_round stays within budget
        double perRoundEpsilon = config.getPrivacyBudget() / config.getNumRounds();

        PrivacyEngine roundPrivacyEngine = new PrivacyEngine();
        LocalTrainer.ModelWeights noisyWeights =
                roundPrivacyEngine.applyNoise(rawWeights, perRoundEpsilon);

        SecurityLayer.SecuredUpdate secured = securityLayer.secure(noisyWeights);

        ClientMetric metric = ClientMetric.builder()
                .session(session)
                .roundNumber(round)
                .clientId(clientId)
                .hospitalName(hospital.name())
                .localAccuracy(localAccuracy)
                .epsilonConsumed(roundPrivacyEngine.computeEpsilonConsumed())  // actual ε, derived from sigma
                .bytesSent((long) secured.encryptedWeights().length)
                .build();

        return new ClientRoundResult(clientId, secured, metric);
    }

    // -----------------------------------------------------------------------
    //  Session lifecycle
    // -----------------------------------------------------------------------

    private void markSessionFailed(Long sessionId) {
        sessionRepository.findById(sessionId).ifPresent(session -> {
            session.setStatus(Status.FAILED);
            session.setFinishedAt(LocalDateTime.now());
            sessionRepository.save(session);
        });
    }

    // -----------------------------------------------------------------------
    //  CSV data loading — returns training + validation together
    // -----------------------------------------------------------------------

    /**
     * Loads CSV data and splits each hospital 80/20. Returns training data
     * (80% per hospital) and pooled validation data (20% from all hospitals)
     * as a single ResolvedData value — no instance fields needed.
     */
    private ResolvedData loadCSVData(TrainingSession session) {
        List<UploadedDataset> datasets = datasetRepository.findBySessionId(session.getId());

        if (datasets.isEmpty()) {
            throw new IllegalStateException("No datasets uploaded for session " + session.getId());
        }

        List<double[]> allValFeatures = new ArrayList<>();
        List<Integer> allValLabels = new ArrayList<>();

        HospitalData[] trainingData = new HospitalData[datasets.size()];
        for (int i = 0; i < datasets.size(); i++) {
            UploadedDataset ds = datasets.get(i);
            DataIngestion.ParsedDataset parsed = DataIngestion.parse(ds.getCsvContent());

            double[][] features = parsed.features();
            int[] labels = parsed.labels();

            // Hold out 20% from each hospital for validation
            int valSize = Math.max(1, features.length / 5);
            int trainSize = features.length - valSize;

            double[][] trainFeatures = Arrays.copyOfRange(features, 0, trainSize);
            int[] trainLabels = Arrays.copyOfRange(labels, 0, trainSize);

            double[][] valFeatures = Arrays.copyOfRange(features, trainSize, features.length);
            int[] valLabels = Arrays.copyOfRange(labels, trainSize, labels.length);

            // Accumulate validation data from ALL hospitals
            for (double[] row : valFeatures) allValFeatures.add(row);
            for (int lbl : valLabels) allValLabels.add(lbl);

            // Hospital trains on only 80%
            trainingData[i] = new HospitalData(ds.getHospitalName(), trainFeatures, trainLabels);
        }

        EvaluationDataset validationData = new EvaluationDataset(
                allValFeatures.toArray(new double[0][]),
                allValLabels.stream().mapToInt(x -> x).toArray());

        return new ResolvedData(trainingData, validationData);
    }

    // -----------------------------------------------------------------------
    //  Simulated data — training data + separate validation data
    // -----------------------------------------------------------------------

    private HospitalData[] generateSimulatedData(int hospitals) {
        int hospitalCount = Math.max(1, hospitals);
        HospitalData[] result = new HospitalData[hospitalCount];
        Random rand = new Random(42);  // Training seed

        for (int h = 0; h < hospitalCount; h++) {
            int samples = 100 + rand.nextInt(50);
            int features = 10;

            double[][] X = new double[samples][features];
            int[] y = new int[samples];

            for (int i = 0; i < samples; i++) {
                for (int j = 0; j < features; j++) {
                    X[i][j] = rand.nextGaussian();
                }
                // Create a learnable pattern: label = 1 if sum of first 3 features > 0
                double signal = X[i][0] + X[i][1] + X[i][2];
                y[i] = signal > 0 ? 1 : 0;
            }

            result[h] = new HospitalData("Hospital-" + h, X, y);
        }

        return result;
    }

    /**
     * Generate a completely separate validation dataset using a DIFFERENT seed
     * so there is zero overlap with training data.
     */
    private EvaluationDataset generateSimulatedValidation() {
        Random valRand = new Random(999);  // Different seed from training (42)
        int valSamples = 50;
        int features = 10;

        double[][] X = new double[valSamples][features];
        int[] y = new int[valSamples];

        for (int i = 0; i < valSamples; i++) {
            for (int j = 0; j < features; j++) {
                X[i][j] = valRand.nextGaussian();
            }
            // Same learnable pattern as training data
            double signal = X[i][0] + X[i][1] + X[i][2];
            y[i] = signal > 0 ? 1 : 0;
        }

        return new EvaluationDataset(X, y);
    }

    // -----------------------------------------------------------------------
    //  Records
    // -----------------------------------------------------------------------

    /** Bundles training data + validation data from a single resolve call. */
    private record ResolvedData(
            HospitalData[] trainingData,
            EvaluationDataset validationData) {}

    private record EvaluationDataset(double[][] features, int[] labels) {}

    private record GlobalNormParams(double[] min, double[] max) {}

    private record ClientRoundResult(
            int clientId,
            SecurityLayer.SecuredUpdate securedUpdate,
            ClientMetric clientMetric) {}

    private record RoundArtifacts(
            List<SecurityLayer.SecuredUpdate> securedUpdates,
            List<ClientMetric> clientMetrics,
            long totalCommunicationBytes) {}

    private record HospitalData(String name, double[][] features, int[] labels) {}
}
