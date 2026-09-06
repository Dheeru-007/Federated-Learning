package com.fl.app.api.rest;

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

import com.fl.app.domain.ClientMetric;
import com.fl.app.domain.RoundMetric;
import com.fl.app.domain.TrainingSession;
import com.fl.app.domain.TrainingSession.DataSource;
import com.fl.app.fl.TrainingConfig;
import com.fl.app.service.SessionService;

/**
 * Thin HTTP adapter for session management.
 * All business logic lives in {@link SessionService}.
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
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

            return ResponseEntity.ok(sessionService.createSession(config, username));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/start")
    public ResponseEntity<?> startTraining(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(sessionService.startSession(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping
    public List<TrainingSession> listSessions() {
        return sessionService.listSessions();
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getSession(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(sessionService.getSession(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{id}/rounds")
    public List<RoundMetric> getRounds(@PathVariable Long id) {
        return sessionService.getRounds(id);
    }

    @GetMapping("/{id}/clients")
    public List<ClientMetric> getClients(@PathVariable Long id) {
        return sessionService.getClients(id);
    }

    // ─── Internal ─────────────────────────────────────────────────────────────

    private static String getCurrentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth == null || auth.getName() == null) ? "unknown" : auth.getName();
    }

    // ─── Request DTO ──────────────────────────────────────────────────────────

    public record CreateSessionRequest(
            String sessionName,
            int    numHospitals,
            int    numRounds,
            double privacyBudget,
            double clipNorm,
            double noiseSigma,
            int    localEpochs,
            String dataSource,
            boolean maliciousClientEnabled
    ) {}
}