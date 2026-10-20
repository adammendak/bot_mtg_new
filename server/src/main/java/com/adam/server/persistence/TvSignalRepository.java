package com.adam.server.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TvSignalRepository extends JpaRepository<TvSignalEntity, Long> {

    boolean existsByDedupeKey(String dedupeKey);

    List<TvSignalEntity> findAllByOrderByIdDesc(Pageable pageable);
}
