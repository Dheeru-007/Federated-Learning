package com.fl.app.api.ws;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoundUpdateMessage {
    private Long sessionId;
    private int roundNumber;
    private int totalRounds;
    private double globalAccuracy;
    private double globalLoss;
    private double epsilonConsumed;
    private long bytesTransferred;
    private int numClients;
    private String status;  // RUNNING, COMPLETED, FAILED
    private String message; // human readable update
}

