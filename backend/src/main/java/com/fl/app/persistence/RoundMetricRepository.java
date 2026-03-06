package com.fl.app.persistence;

import com.fl.app.domain.RoundMetric;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RoundMetricRepository extends JpaRepository<RoundMetric, Long> {

    List<RoundMetric> findBySessionIdOrderByRoundNumberAsc(Long sessionId);

    Optional<RoundMetric> findTopBySessionIdOrderByRoundNumberDesc(Long sessionId);
}

