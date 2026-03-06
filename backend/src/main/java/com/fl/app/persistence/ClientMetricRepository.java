package com.fl.app.persistence;

import com.fl.app.domain.ClientMetric;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ClientMetricRepository extends JpaRepository<ClientMetric, Long> {

    List<ClientMetric> findBySessionIdAndRoundNumber(Long sessionId, int roundNumber);

    List<ClientMetric> findBySessionId(Long sessionId);
}

