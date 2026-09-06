package com.fl.app.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.fl.app.domain.TrainingSession;
import com.fl.app.domain.TrainingSession.DataSource;
import com.fl.app.domain.UploadedDataset;
import com.fl.app.fl.pipeline.DataIngestion;
import com.fl.app.persistence.TrainingSessionRepository;
import com.fl.app.persistence.UploadedDatasetRepository;

/**
 * Service layer for hospital dataset uploads and upload-status queries.
 * Encapsulates all business logic, keeping DatasetController as a thin HTTP mapping layer.
 */
@Service
public class DatasetService {

    private static final Logger log = LoggerFactory.getLogger(DatasetService.class);

    private final TrainingSessionRepository sessionRepository;
    private final UploadedDatasetRepository datasetRepository;

    public DatasetService(
            TrainingSessionRepository sessionRepository,
            UploadedDatasetRepository datasetRepository) {
        this.sessionRepository = sessionRepository;
        this.datasetRepository = datasetRepository;
    }

    // ─── Upload ───────────────────────────────────────────────────────────────

    /**
     * Parses and stores a CSV dataset for a specific hospital in a session.
     *
     * @throws IllegalArgumentException on validation failures (session not found, wrong state, etc.)
     * @throws RuntimeException         if the file cannot be read or parsed
     */
    @Transactional
    public Map<String, Object> uploadDataset(Long sessionId, int hospitalId, MultipartFile file) {
        TrainingSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

        validateUploadAllowed(session, hospitalId);

        String csvContent = readFileContent(file);
        DataIngestion.ParsedDataset parsed = DataIngestion.parse(csvContent);

        validateFeatureConsistency(session, parsed.featureCount(), sessionId);

        // Lock feature count from first successful upload
        if (session.getFeatureCount() == null) {
            session.setFeatureCount(parsed.featureCount());
        }

        // Replace any existing upload for this hospital
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

        // Sync denormalized counter + switch data source
        int uploaded = datasetRepository.countBySessionId(sessionId);
        session.setHospitalsUploaded(uploaded);
        session.setDataSource(DataSource.CSV);
        sessionRepository.save(session);

        log.info("Hospital-{} dataset uploaded for session {} ({} rows, {} features)",
                hospitalId, sessionId, parsed.rowCount(), parsed.featureCount());

        Map<String, Object> response = new HashMap<>();
        response.put("message", "Dataset uploaded successfully");
        response.put("hospitalId", hospitalId);
        response.put("rows", parsed.rowCount());
        response.put("features", parsed.featureCount());
        response.put("hospitalsUploaded", uploaded);
        response.put("hospitalsRequired", session.getNumHospitals());
        response.put("allUploaded", uploaded == session.getNumHospitals());
        return response;
    }

    // ─── Status query ─────────────────────────────────────────────────────────

    /**
     * Returns a summary of uploaded datasets for the given session.
     */
    public Map<String, Object> getUploadStatus(Long sessionId) {
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
            "hospitalId",   d.getHospitalId(),
            "hospitalName", d.getHospitalName(),
            "rows",         d.getRowCount(),
            "features",     d.getFeatureCount(),
            "uploadedAt",   d.getUploadedAt()
        )).toList());
        return response;
    }

    // ─── Validation helpers ───────────────────────────────────────────────────

    private void validateUploadAllowed(TrainingSession session, int hospitalId) {
        if (session.getStatus() != TrainingSession.Status.PENDING) {
            throw new IllegalArgumentException(
                "Cannot upload to a session that is not PENDING (current: " + session.getStatus() + ")");
        }
        if (hospitalId < 0 || hospitalId >= session.getNumHospitals()) {
            throw new IllegalArgumentException("Invalid hospital ID: " + hospitalId
                + " (session has " + session.getNumHospitals() + " hospitals, IDs 0–"
                + (session.getNumHospitals() - 1) + ")");
        }
    }

    private void validateFeatureConsistency(TrainingSession session, int newFeatureCount, Long sessionId) {
        if (session.getFeatureCount() != null && session.getFeatureCount() != newFeatureCount) {
            throw new IllegalArgumentException(
                "Feature count mismatch. Session expects "
                + session.getFeatureCount() + " features, got " + newFeatureCount);
        }
    }

    private String readFileContent(MultipartFile file) {
        try {
            return new String(file.getBytes());
        } catch (Exception e) {
            throw new RuntimeException("Failed to read uploaded file: " + e.getMessage(), e);
        }
    }
}
