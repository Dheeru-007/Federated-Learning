package com.fl.app.fl;

import com.fl.app.api.ws.RoundUpdateMessage;
import com.fl.app.api.ws.TrainingEventPublisher;
import com.fl.app.domain.ClientMetric;
import com.fl.app.domain.RoundMetric;
import com.fl.app.domain.TrainingSession;
import com.fl.app.domain.TrainingSession.Status;
import com.fl.app.persistence.ClientMetricRepository;
import com.fl.app.persistence.RoundMetricRepository;
import com.fl.app.persistence.TrainingSessionRepository;
import com.fl.data.HealthcareDataSimulator;
import com.fl.data.HealthcareDataSimulator.HospitalDataset;
import com.fl.model.LogisticRegressionModel;
import com.fl.model.ModelWeights;
import com.fl.privacy.DifferentialPrivacy;
import com.fl.server.FedAvgAggregator;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class FlTrainingCoordinator {

    private final TrainingSessionRepository trainingSessionRepository;
    private final RoundMetricRepository roundMetricRepository;
    private final ClientMetricRepository clientMetricRepository;
    private final TrainingEventPublisher eventPublisher;
    private final ApplicationContext applicationContext;

    public FlTrainingCoordinator(
            TrainingSessionRepository trainingSessionRepository,
            RoundMetricRepository roundMetricRepository,
            ClientMetricRepository clientMetricRepository,
            TrainingEventPublisher eventPublisher,
            ApplicationContext applicationContext
    ) {
        this.trainingSessionRepository = trainingSessionRepository;
        this.roundMetricRepository = roundMetricRepository;
        this.clientMetricRepository = clientMetricRepository;
        this.eventPublisher = eventPublisher;
        this.applicationContext = applicationContext;
    }

    public TrainingSession startTraining(TrainingConfig config, String createdBy) {
        TrainingSession session = TrainingSession.builder()
                .name(config.getSessionName() == null || config.getSessionName().isBlank()
                        ? "FL Session " + LocalDateTime.now()
                        : config.getSessionName())
                .createdBy(createdBy)
                .status(Status.PENDING)
                .numHospitals(config.getNumHospitals())
                .numRounds(config.getNumRounds())
                .privacyBudget(config.getPrivacyBudget())
                .build();

        TrainingSession saved = trainingSessionRepository.save(session);
        applicationContext.getBean(FlTrainingCoordinator.class).runTraining(saved.getId(), config);
        return saved;
    }

    public TrainingSession getSessionStatus(Long sessionId) {
        return trainingSessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Training session not found: " + sessionId));
    }

    @Async
    public void runTraining(Long sessionId, TrainingConfig config) {
        TrainingSession session = trainingSessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Training session not found: " + sessionId));

        try {
            session.setStatus(Status.RUNNING);
            session.setStartedAt(LocalDateTime.now());
            trainingSessionRepository.save(session);
            eventPublisher.publishSessionStatus(session.getId(), "RUNNING", "Training started");

            HealthcareDataSimulator simulator = new HealthcareDataSimulator(2024L);
            HospitalDataset[] allDatasets = simulator.generateAndPartition();

            int numHospitals = Math.min(config.getNumHospitals(), allDatasets.length);
            if (numHospitals <= 0) {
                throw new IllegalArgumentException("numHospitals must be > 0");
            }

            HospitalDataset[] trainingDatasets = Arrays.copyOfRange(allDatasets, 0, numHospitals);

            // Avoid data leakage: validation data must not come from a hospital used for training.
            // Prefer holding out an extra hospital partition when available; otherwise generate a
            // separate synthetic dataset with a different seed for validation.
            HospitalDataset validationHospital;
            if (allDatasets.length > numHospitals) {
                validationHospital = allDatasets[numHospitals];
            } else {
                HealthcareDataSimulator validationSim = new HealthcareDataSimulator(9999L);
                validationHospital = validationSim.generateAndPartition()[0];
            }
            double[][][] valSplits = validationHospital.getTrainTestFeatures();
            int[][] valLabels = validationHospital.getTrainTestLabels();
            double[][] valX = valSplits[1];
            int[] valY = valLabels[1];

            int featureCount = HealthcareDataSimulator.FEATURE_COUNT;
            ModelWeights globalModel = new ModelWeights(featureCount);
            globalModel.setClientId("Global");

            FedAvgAggregator aggregator = new FedAvgAggregator(0.0, false);

            Map<Integer, DifferentialPrivacy> dpByHospital = new LinkedHashMap<>();
            for (int h = 0; h < numHospitals; h++) {
                dpByHospital.put(h, DifferentialPrivacy.gradientPerturbation());
            }

            double finalAcc = 0.0;
            double finalLoss = 0.0;

            for (int round = 1; round <= config.getNumRounds(); round++) {
                Map<String, ModelWeights> updates = new LinkedHashMap<>();
                long bytesTransferred = 0L;
                double epsConsumedThisRound = 0.0;

                for (int hospitalId = 0; hospitalId < numHospitals; hospitalId++) {
                    System.out.println("[FL] Round " + round + " - Training Hospital " + hospitalId + "...");
                    HospitalDataset hospital = trainingDatasets[hospitalId];
                    String hospitalName = hospital.getName();
                    double[][][] splits = hospital.getTrainTestFeatures();
                    int[][] labels = hospital.getTrainTestLabels();
                    double[][] trainX = splits[0];
                    int[] trainY = labels[0];
                    double[][] testX = splits[1];
                    int[] testY = labels[1];

                    int maxSamples = Math.min(trainX.length, 500);
                    trainX = Arrays.copyOfRange(trainX, 0, maxSamples);
                    trainY = Arrays.copyOfRange(trainY, 0, maxSamples);


                    DifferentialPrivacy dp = dpByHospital.get(hospitalId);
                    if (!dp.hasBudgetRemaining(config.getPrivacyBudget())) {
                        ClientMetric metric = ClientMetric.builder()
                                .session(session)
                                .roundNumber(round)
                                .clientId(hospitalId)
                                .hospitalName(hospitalName)
                                .localAccuracy(0.0)
                                .epsilonConsumed(0.0)
                                .bytesSent(0L)
                                .build();
                        clientMetricRepository.save(metric);
                        continue;
                    }

                    LogisticRegressionModel localModel = new LogisticRegressionModel(featureCount, 0.01, 32);
                    localModel.loadWeights(globalModel);

                    ModelWeights rawUpdate = localModel.train(
                            trainX,
                            trainY,
                          1,
                            hospitalName,
                            round
                    );

                    double localAccuracy = localModel.evaluate(testX, testY);

                    ModelWeights dpUpdate = dp.applyDP(rawUpdate, trainX.length, hospital.getSize());
                    updates.put(hospitalName, dpUpdate);

                    double eps = dpUpdate.getEpsilonConsumed();
                    epsConsumedThisRound += eps;

                    ClientMetric metric = ClientMetric.builder()
                            .session(session)
                            .roundNumber(round)
                            .clientId(hospitalId)
                            .hospitalName(hospitalName)
                            .localAccuracy(localAccuracy)
                            .epsilonConsumed(eps)
                            .bytesSent(0L)
                            .build();
                    clientMetricRepository.save(metric);
                }

                if (updates.isEmpty()) {
                    throw new IllegalStateException("No client updates available to aggregate (privacy budget exhausted?)");
                }

                ModelWeights newGlobal = aggregator.aggregate(updates, numHospitals, globalModel, round);
                if (newGlobal != null) {
                    globalModel = newGlobal;
                }

                LogisticRegressionModel evalModel = new LogisticRegressionModel(featureCount, 0.01, 32);
                evalModel.loadWeights(globalModel);

                double globalAccuracy = evalModel.evaluate(valX, valY);
                double globalLoss = evalModel.computeLoss(valX, valY);

                finalAcc = globalAccuracy;
                finalLoss = globalLoss;

                RoundMetric roundMetric = RoundMetric.builder()
                        .session(session)
                        .roundNumber(round)
                        .globalAccuracy(globalAccuracy)
                        .globalLoss(globalLoss)
                        .numClients(numHospitals)
                        .bytesTransferred(bytesTransferred)
                        .epsilonConsumed(epsConsumedThisRound)
                        .build();
                roundMetricRepository.save(roundMetric);

                RoundUpdateMessage msg = RoundUpdateMessage.builder()
                        .sessionId(session.getId())
                        .roundNumber(round)
                        .totalRounds(config.getNumRounds())
                        .globalAccuracy(globalAccuracy)
                        .globalLoss(globalLoss)
                        .epsilonConsumed(epsConsumedThisRound)
                        .bytesTransferred(bytesTransferred)
                        .numClients(numHospitals)
                        .status("RUNNING")
                        .message("Round " + round + " completed")
                        .build();
                eventPublisher.publishRoundUpdate(msg);

                if (globalAccuracy > 0.95) {
                    break;
                }
            }

            session.setStatus(Status.COMPLETED);
            session.setFinishedAt(LocalDateTime.now());
            session.setFinalAccuracy(finalAcc);
            session.setFinalLoss(finalLoss);
            trainingSessionRepository.save(session);

            eventPublisher.publishSessionStatus(session.getId(), "COMPLETED", "Training completed");
        } catch (Exception e) {
            session.setStatus(Status.FAILED);
            session.setFinishedAt(LocalDateTime.now());
            trainingSessionRepository.save(session);
            eventPublisher.publishSessionStatus(session.getId(), "FAILED", e.getMessage());
        }
    }
}

