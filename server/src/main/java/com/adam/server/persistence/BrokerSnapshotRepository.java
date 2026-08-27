package com.adam.server.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BrokerSnapshotRepository extends JpaRepository<BrokerSnapshotEntity, Long> {
    List<BrokerSnapshotEntity> findByBookOrderByCapturedAtAsc(String book);
}
