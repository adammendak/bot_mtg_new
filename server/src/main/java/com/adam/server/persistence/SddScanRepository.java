package com.adam.server.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SddScanRepository extends JpaRepository<SddScanEntity, Long> {
    Optional<SddScanEntity> findTopByOrderByIdDesc();
}
