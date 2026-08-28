package com.adam.server.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BrokerSnapshotRepository extends JpaRepository<BrokerSnapshotEntity, Long> {
    List<BrokerSnapshotEntity> findByBookOrderByCapturedAtAsc(String book);

    Optional<BrokerSnapshotEntity> findTopByBookOrderByCapturedAtDesc(String book);

    boolean existsByBookAndCapturedAtBetween(String book, Instant from, Instant to);

    long deleteByBook(String book);
}
