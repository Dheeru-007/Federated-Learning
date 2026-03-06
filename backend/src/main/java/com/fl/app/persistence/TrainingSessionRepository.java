package com.fl.app.persistence;

import com.fl.app.domain.TrainingSession;
import com.fl.app.domain.TrainingSession.Status;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TrainingSessionRepository extends JpaRepository<TrainingSession, Long> {

    List<TrainingSession> findAllByOrderByStartedAtDesc();

    List<TrainingSession> findByStatus(Status status);
}

