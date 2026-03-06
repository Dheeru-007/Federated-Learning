package com.fl.app.api.rest;

import com.fl.app.domain.ClientMetric;
import com.fl.app.domain.RoundMetric;
import com.fl.app.domain.TrainingSession;
import com.fl.app.fl.FlTrainingCoordinator;
import com.fl.app.fl.TrainingConfig;
import com.fl.app.persistence.ClientMetricRepository;
import com.fl.app.persistence.RoundMetricRepository;
import com.fl.app.persistence.TrainingSessionRepository;
import java.util.List;
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

    public SessionController(
            FlTrainingCoordinator coordinator,
            RoundMetricRepository roundMetricRepository,
            ClientMetricRepository clientMetricRepository,
            TrainingSessionRepository trainingSessionRepository
    ) {
        this.coordinator = coordinator;
        this.roundMetricRepository = roundMetricRepository;
        this.clientMetricRepository = clientMetricRepository;
        this.trainingSessionRepository = trainingSessionRepository;
    }

    @PostMapping
    public TrainingSession createSession(@RequestBody CreateSessionRequest request) {
        String username = getCurrentUsername();

        TrainingConfig config = TrainingConfig.builder()
                .sessionName(request.sessionName())
                .numHospitals(request.numHospitals())
                .numRounds(request.numRounds())
                .privacyBudget(request.privacyBudget())
                .clipNorm(request.clipNorm())
                .noiseSigma(request.noiseSigma())
                .localEpochs(request.localEpochs())
                .build();

        return coordinator.startTraining(config, username);
    }

    @GetMapping
    public List<TrainingSession> listSessions() {
        return trainingSessionRepository.findAllByOrderByStartedAtDesc();
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
            int localEpochs
    ) {}
}

