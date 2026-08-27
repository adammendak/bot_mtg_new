package com.adam.server.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BrokerSnapshotRepository extends JpaRepository<BrokerSnapshotEntity, Long> {
}
