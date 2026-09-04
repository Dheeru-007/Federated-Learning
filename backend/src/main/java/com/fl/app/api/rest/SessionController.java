package com.fl.app.api.rest;

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
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final FlTrainingCoordinator coordinator;
    private final RoundMetricRepository roundMetricRepository;
    private final ClientMetricRepository clientMetricRepository;
    private final TrainingSessionRepository trainingSessionRepository;
    private final UploadedDatasetRepository datasetRepository;

    public SessionController(
            FlTrainingCoordinator coordinator,
            RoundMetricRepository roundMetricRepository,
            ClientMetricRepository clientMetricRepository,
            TrainingSessionRepository trainingSessionRepository,
            UploadedDatasetRepository datasetRepository
    ) {
        this.coordinator = coordinator;
        this.roundMetricRepository = roundMetricRepository;
        this.clientMetricRepository = clientMetricRepository;
        this.trainingSessionRepository = trainingSessionRepository;
        this.datasetRepository = datasetRepository;
    }

    @PostMapping
    public ResponseEntity<?> createSession(@RequestBody CreateSessionRequest request) {
        try {
            String username = getCurrentUsername();

            TrainingConfig config = TrainingConfig.builder()
                    .sessionName(request.sessionName())
                    .numHospitals(request.numHospitals())
                    .numRounds(request.numRounds())
                    .privacyBudget(request.privacyBudget())
                    .clipNorm(request.clipNorm())
                    .noiseSigma(request.noiseSigma())
                    .localEpochs(request.localEpochs())
                    .dataSource(request.dataSource() != null
                            ? DataSource.valueOf(request.dataSource())
                            : DataSource.CSV)
                    .maliciousClientEnabled(request.maliciousClientEnabled())
                    .build();

            // For CSV mode, just create session without starting training
            if (config.getDataSource() == DataSource.CSV) {
                TrainingSession session = TrainingSession.builder()
                        .name(config.getSessionName() == null || config.getSessionName().isBlank()
                                ? "FL Session"
                                : config.getSessionName())
                        .createdBy(username)
                        .status(Status.PENDING)
                        .numHospitals(config.getNumHospitals())
                        .numRounds(config.getNumRounds())
                        .privacyBudget(config.getPrivacyBudget())
                        .dataSource(DataSource.CSV)
                        .maliciousClientEnabled(config.isMaliciousClientEnabled())
                        .build();
                return ResponseEntity.ok(trainingSessionRepository.save(session));
            }

            // For SIMULATED mode, start training immediately
            return ResponseEntity.ok(coordinator.startTraining(config, username));

        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/start")
    public ResponseEntity<?> startTraining(@PathVariable Long id) {
        try {
            TrainingSession session = trainingSessionRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Session not found: " + id));

            if (session.getStatus() != Status.PENDING) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Session is not in PENDING state"));
            }

            if (session.getDataSource() == DataSource.CSV) {
                int uploaded = datasetRepository.countBySessionId(id);
                if (uploaded < session.getNumHospitals()) {
                    return ResponseEntity.badRequest().body(Map.of(
                        "error", "Not all hospitals have uploaded datasets. "
                            + uploaded + "/" + session.getNumHospitals() + " uploaded."
                    ));
                }

                // Ensure feature count was set during CSV upload
                if (session.getFeatureCount() == null || session.getFeatureCount() <= 0) {
                    return ResponseEntity.badRequest().body(Map.of(
                        "error", "Feature count not set. Re-upload datasets."
                    ));
                }
            }

            TrainingConfig config = TrainingConfig.builder()
                    .sessionName(session.getName())
                    .numHospitals(session.getNumHospitals())
                    .numRounds(session.getNumRounds())
                    .privacyBudget(session.getPrivacyBudget())
                    .dataSource(session.getDataSource())
                    .maliciousClientEnabled(session.isMaliciousClientEnabled())
                    .build();

            // Delegate to coordinator — it handles status transition to RUNNING
            coordinator.runTrainingAsync(id, config);

            // Re-read session after async dispatch to return current state
            TrainingSession updated = trainingSessionRepository.findById(id).orElse(session);
            return ResponseEntity.ok(updated);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping
    public List<TrainingSession> listSessions() {
        return trainingSessionRepository.findAllByOrderByIdDesc();
    }

    @GetMapping("/{id}")
    public TrainingSession getSession(@PathVariable Long id) {
        return coordinator.getSessionStatus(id);
    }

    @GetMapping("/{id}/rounds")
    public List<RoundMetric> getRounds(@PathVariable Long id) {
        return roundMetricRepository.findBySessionIdOrderByRoundNumberAsc(id);
    }

    @GetMapping("/{id}/clients")
    public List<ClientMetric> getClients(@PathVariable Long id) {
        return clientMetricRepository.findBySessionId(id);
    }

    private static String getCurrentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) return "unknown";
        return auth.getName();
    }

    public record CreateSessionRequest(
            String sessionName,
            int numHospitals,
            int numRounds,
            double privacyBudget,
            double clipNorm,
            double noiseSigma,
            int localEpochs,
            String dataSource,
            boolean maliciousClientEnabled
    ) {}
}