package com.fl.app.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.fl.app.domain.UploadedDataset;

public interface UploadedDatasetRepository extends JpaRepository<UploadedDataset, Long> {
    List<UploadedDataset> findBySessionId(Long sessionId);
    Optional<UploadedDataset> findBySessionIdAndHospitalId(Long sessionId, int hospitalId);
    int countBySessionId(Long sessionId);
}