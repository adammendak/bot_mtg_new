package com.adam.server.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HtsSignalRepository extends JpaRepository<HtsSignalEntity, Long> {
    List<HtsSignalEntity> findAllByOrderByIdDesc(Pageable pageable);
}
