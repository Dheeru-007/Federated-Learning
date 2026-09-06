package com.fl.app.api.rest;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.fl.app.service.DatasetService;

/**
 * Thin HTTP adapter for dataset uploads and status queries.
 * All business logic lives in {@link DatasetService}.
 */
@RestController
@RequestMapping("/api/datasets")
public class DatasetController {

    private final DatasetService datasetService;

    public DatasetController(DatasetService datasetService) {
        this.datasetService = datasetService;
    }

    @PostMapping("/upload/{sessionId}/{hospitalId}")
    public ResponseEntity<?> uploadDataset(
            @PathVariable Long sessionId,
            @PathVariable int hospitalId,
            @RequestParam("file") MultipartFile file) {

        try {
            return ResponseEntity.ok(datasetService.uploadDataset(sessionId, hospitalId, file));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/status/{sessionId}")
    public ResponseEntity<?> getUploadStatus(@PathVariable Long sessionId) {
        try {
            return ResponseEntity.ok(datasetService.getUploadStatus(sessionId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}