package com.fl.app.service;

import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fl.app.domain.ClientMetric;
import com.fl.app.domain.RoundMetric;
import com.fl.app.domain.TrainingSession;
import com.fl.app.domain.TrainingSession.DataSource;
import com.fl.app.domain.TrainingSession.Status;
import com.fl.app.fl.FlTrainingCoordinator;
import com.fl.app.fl.TrainingConfig;
import com.fl.app.persistence.ClientMetricRepository;
import com.fl.app.persistence.RoundMetricRepository;
import com.fl.app.persistence.TrainingSessionRepository;
import com.fl.app.persistence.UploadedDatasetRepository;

/**
 * Service layer for FL training session management.
 * Encapsulates all business logic, keeping SessionController as a thin HTTP mapping layer.
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final FlTrainingCoordinator coordinator;
    private final TrainingSessionRepository sessionRepository;
    private final RoundMetricRepository roundMetricRepository;
    private final ClientMetricRepository clientMetricRepository;
    private final UploadedDatasetRepository datasetRepository;

    public SessionService(
            FlTrainingCoordinator coordinator,
            TrainingSessionRepository sessionRepository,
            RoundMetricRepository roundMetricRepository,
            ClientMetricRepository clientMetricRepository,
            UploadedDatasetRepository datasetRepository) {

        this.coordinator           = coordinator;
        this.sessionRepository     = sessionRepository;
        this.roundMetricRepository = roundMetricRepository;
        this.clientMetricRepository = clientMetricRepository;
        this.datasetRepository     = datasetRepository;
    }

    // ─── Create ───────────────────────────────────────────────────────────────

    /**
     * Creates a new training session.
     * - SIMULATED: starts training immediately and returns a RUNNING session.
     * - CSV: saves a PENDING session; caller must upload datasets then call startSession().
     */
    @Transactional
    public TrainingSession createSession(TrainingConfig config, String createdBy) {
        if (config.getDataSource() == DataSource.CSV) {
            // Persist as PENDING — training starts after all datasets are uploaded
            TrainingSession session = TrainingSession.builder()
                    .name(resolvedName(config.getSessionName()))
                    .createdBy(createdBy)
                    .status(Status.PENDING)
                    .numHospitals(config.getNumHospitals())
                    .numRounds(config.getNumRounds())
                    .privacyBudget(config.getPrivacyBudget())
                    .clipNorm(config.getClipNorm())
                    .noiseSigma(config.getNoiseSigma())
                    .localEpochs(config.getLocalEpochs())
                    .dataSource(DataSource.CSV)
                    .maliciousClientEnabled(config.isMaliciousClientEnabled())
                    .build();
            return sessionRepository.save(session);
        }

        // SIMULATED: delegate to coordinator (sets RUNNING synchronously before dispatching async)
        return coordinator.startTraining(config, createdBy);
    }

    // ─── Start ────────────────────────────────────────────────────────────────

    /**
     * Starts a PENDING CSV session after all datasets have been uploaded.
     * Validates state and upload completeness, then transitions to RUNNING synchronously.
     *
     * @throws IllegalArgumentException if session not found, wrong state, or uploads incomplete
     * @throws IllegalStateException    if feature count not yet resolved
     */
    @Transactional
    public TrainingSession startSession(Long sessionId) {
        TrainingSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

        if (session.getStatus() != Status.PENDING) {
            throw new IllegalArgumentException(
                "Session is not in PENDING state (current: " + session.getStatus() + ")");
        }

        if (session.getDataSource() == DataSource.CSV) {
            int uploaded = datasetRepository.countBySessionId(sessionId);
            if (uploaded < session.getNumHospitals()) {
                throw new IllegalArgumentException(
                    "Not all hospitals have uploaded datasets: "
                    + uploaded + "/" + session.getNumHospitals() + " uploaded.");
            }
            if (session.getFeatureCount() == null || session.getFeatureCount() <= 0) {
                throw new IllegalStateException(
                    "Feature count not set. Re-upload datasets to resolve.");
            }
        }

        TrainingConfig config = TrainingConfig.builder()
                .sessionName(session.getName())
                .numHospitals(session.getNumHospitals())
                .numRounds(session.getNumRounds())
                .privacyBudget(session.getPrivacyBudget())
                .clipNorm(session.getClipNorm())
                .noiseSigma(session.getNoiseSigma())
                .localEpochs(session.getLocalEpochs())
                .dataSource(session.getDataSource())
                .maliciousClientEnabled(session.isMaliciousClientEnabled())
                .build();

        // Transition to RUNNING synchronously — HTTP caller sees accurate state immediately
        session.setStatus(Status.RUNNING);
        session.setStartedAt(LocalDateTime.now());
        TrainingSession updated = sessionRepository.save(session);

        coordinator.runTrainingAsync(sessionId, config);
        log.info("Session {} dispatched for async training", sessionId);
        return updated;
    }

    // ─── Queries ──────────────────────────────────────────────────────────────

    public List<TrainingSession> listSessions() {
        return sessionRepository.findAllByOrderByIdDesc();
    }

    public TrainingSession getSession(Long sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
    }

    public List<RoundMetric> getRounds(Long sessionId) {
        return roundMetricRepository.findBySessionIdOrderByRoundNumberAsc(sessionId);
    }

    public List<ClientMetric> getClients(Long sessionId) {
        return clientMetricRepository.findBySessionId(sessionId);
    }

    // ─── Internal ─────────────────────────────────────────────────────────────

    private static String resolvedName(String name) {
        return (name == null || name.isBlank())
                ? "FL Session " + System.currentTimeMillis()
                : name;
    }
}
