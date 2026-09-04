package com.fl.app.api.rest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.fl.app.domain.TrainingSession;
import com.fl.app.domain.TrainingSession.DataSource;
import com.fl.app.domain.UploadedDataset;
import com.fl.app.fl.pipeline.DataIngestion;
import com.fl.app.persistence.TrainingSessionRepository;
import com.fl.app.persistence.UploadedDatasetRepository;

@RestController
@RequestMapping("/api/datasets")
public class DatasetController {

    private final TrainingSessionRepository sessionRepository;
    private final UploadedDatasetRepository datasetRepository;

    public DatasetController(
            TrainingSessionRepository sessionRepository,
            UploadedDatasetRepository datasetRepository) {
        this.sessionRepository = sessionRepository;
        this.datasetRepository = datasetRepository;
    }

    @PostMapping("/upload/{sessionId}/{hospitalId}")
    public ResponseEntity<?> uploadDataset(
            @PathVariable Long sessionId,
            @PathVariable int hospitalId,
            @RequestParam("file") MultipartFile file) {

        try {
            TrainingSession session = sessionRepository.findById(sessionId)
                    .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

            if (session.getStatus() != TrainingSession.Status.PENDING) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Cannot upload to a session that is not PENDING"));
            }

            if (hospitalId < 0 || hospitalId >= session.getNumHospitals()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Invalid hospital ID: " + hospitalId));
            }

            // Parse CSV
            String csvContent = new String(file.getBytes());
            DataIngestion.ParsedDataset parsed = DataIngestion.parse(csvContent);

            // Validate feature count consistency
            if (session.getFeatureCount() != null && session.getFeatureCount() != parsed.featureCount()) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "Feature count mismatch. Session expects "
                        + session.getFeatureCount() + " features, got " + parsed.featureCount()
                ));
            }

            // Lock feature count from first upload
            if (session.getFeatureCount() == null) {
                session.setFeatureCount(parsed.featureCount());
            }

            // Save or replace existing upload
            datasetRepository.findBySessionIdAndHospitalId(sessionId, hospitalId)
                    .ifPresent(datasetRepository::delete);

            UploadedDataset dataset = UploadedDataset.builder()
                    .session(session)
                    .hospitalId(hospitalId)
                    .hospitalName("Hospital-" + hospitalId)
                    .rowCount(parsed.rowCount())
                    .featureCount(parsed.featureCount())
                    .csvContent(csvContent)
                    .build();

            datasetRepository.save(dataset);

            // Update hospitals uploaded count
            int uploaded = datasetRepository.countBySessionId(sessionId);
            session.setHospitalsUploaded(uploaded);

            // Switch data source to CSV
            session.setDataSource(DataSource.CSV);
            sessionRepository.save(session);

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Dataset uploaded successfully");
            response.put("hospitalId", hospitalId);
            response.put("rows", parsed.rowCount());
            response.put("features", parsed.featureCount());
            response.put("hospitalsUploaded", uploaded);
            response.put("hospitalsRequired", session.getNumHospitals());
            response.put("allUploaded", uploaded == session.getNumHospitals());

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/status/{sessionId}")
    public ResponseEntity<?> getUploadStatus(@PathVariable Long sessionId) {
        try {
            TrainingSession session = sessionRepository.findById(sessionId)
                    .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

            List<UploadedDataset> datasets = datasetRepository.findBySessionId(sessionId);

            Map<String, Object> response = new HashMap<>();
            response.put("sessionId", sessionId);
            response.put("hospitalsRequired", session.getNumHospitals());
            response.put("hospitalsUploaded", session.getHospitalsUploaded());
            response.put("allUploaded", session.getHospitalsUploaded() == session.getNumHospitals());
            response.put("featureCount", session.getFeatureCount());
            response.put("datasets", datasets.stream().map(d -> Map.of(
                "hospitalId", d.getHospitalId(),
                "hospitalName", d.getHospitalName(),
                "rows", d.getRowCount(),
                "features", d.getFeatureCount(),
                "uploadedAt", d.getUploadedAt()
            )).toList());

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}