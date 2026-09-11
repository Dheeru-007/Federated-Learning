package com.fl.app.domain;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "training_sessions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainingSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false)
    private int numHospitals;

    @Column(nullable = false)
    private int numRounds;

    @Column(nullable = false)
    private double privacyBudget;

    @Column(nullable = false)
    @Builder.Default
    private double clipNorm = 1.0;

    @Column(nullable = false)
    @Builder.Default
    private double noiseSigma = 0.05;

    @Column(nullable = false)
    @Builder.Default
    private int localEpochs = 1;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private DataSource dataSource = DataSource.SIMULATED;

    @Builder.Default
    private boolean maliciousClientEnabled = false;

    private Integer featureCount;

    @Column(length = 2000)
    private String expectedHeaders;

    @Builder.Default
    private int hospitalsUploaded = 0;

    @CreationTimestamp
    private LocalDateTime createdAt;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private Double testAccuracy;

    private Double testLoss;

    private Double testPrecision;

    private Double testRecall;

    private Double testSpecificity;

    private Double testF1Score;

    private Double testRocAuc;

    private Double testPrAuc;

    public enum Status {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED
    }

    public enum DataSource {
        SIMULATED,
        CSV
    }
}