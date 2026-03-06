package com.fl.app.fl;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainingConfig {

    @Builder.Default
    private int numHospitals = 5;

    @Builder.Default
    private int numRounds = 20;

    @Builder.Default
    private double privacyBudget = 0.5;

    @Builder.Default
    private double clipNorm = 1.0;

    @Builder.Default
    private double noiseSigma = 0.05;

    @Builder.Default
    private int localEpochs = 7;

    private String sessionName;
}

