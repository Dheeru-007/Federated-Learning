package com.fl.app.persistence;

import com.fl.app.domain.TrainingSession;
import com.fl.app.domain.TrainingSession.Status;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrainingSessionRepository extends JpaRepository<TrainingSession, Long> {

    List<TrainingSession> findAllByOrderByIdDesc();

    List<TrainingSession> findAllByOrderByStartedAtDesc();

    List<TrainingSession> findByStatus(Status status);
}