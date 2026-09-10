package com.fl.app.api.ws;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
public class TrainingEventPublisher {

    private final SimpMessagingTemplate messagingTemplate;

    public TrainingEventPublisher(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void publishRoundUpdate(RoundUpdateMessage msg) {
        if (msg == null || msg.getSessionId() == null) return;
        String destination = "/topic/sessions/" + msg.getSessionId() + "/rounds";
        messagingTemplate.convertAndSend(destination, msg);
    }

    public void publishSessionStatus(Long sessionId, String status, String message) {
        if (sessionId == null) return;
        RoundUpdateMessage msg = RoundUpdateMessage.builder()
                .sessionId(sessionId)
                .status(status)
                .message(message)
                .build();

        String destination = "/topic/sessions/" + sessionId + "/status";
        messagingTemplate.convertAndSend(destination, msg);
    }
}

