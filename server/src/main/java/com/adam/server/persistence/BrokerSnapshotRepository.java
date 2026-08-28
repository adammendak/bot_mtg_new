package com.adam.server.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BrokerSnapshotRepository extends JpaRepository<BrokerSnapshotEntity, Long> {
    List<BrokerSnapshotEntity> findByBookOrderByCapturedAtAsc(String book);

    Optional<BrokerSnapshotEntity> findTopByBookOrderByCapturedAtDesc(String book);

    long deleteByBook(String book);
}
