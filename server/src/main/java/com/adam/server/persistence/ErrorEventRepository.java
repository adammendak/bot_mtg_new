package com.adam.server.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface ErrorEventRepository extends JpaRepository<ErrorEventEntity, Long> {

    List<ErrorEventEntity> findAllByOrderByIdDesc(Pageable pageable);

    @Modifying
    @Transactional
    @Query("delete from ErrorEventEntity e where e.at < ?1")
    int deleteByAtBefore(Instant cutoff);
}
