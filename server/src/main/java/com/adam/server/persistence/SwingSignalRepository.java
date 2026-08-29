package com.adam.server.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SwingSignalRepository extends JpaRepository<SwingSignalEntity, Long> {
    List<SwingSignalEntity> findAllByOrderByIdDesc(Pageable pageable);
}
