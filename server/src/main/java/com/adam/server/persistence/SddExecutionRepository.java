package com.adam.server.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface SddExecutionRepository extends JpaRepository<SddExecutionEntity, Long> {
    List<SddExecutionEntity> findByBook(String book);

    List<SddExecutionEntity> findByBookAndSymbol(String book, String symbol);

    /**
     * Derived delete queries are not covered by {@code SimpleJpaRepository}'s
     * {@code @Transactional} CRUD methods. Calling this from a non-transactional
     * {@code SddExecutionState.put/remove/update} throws
     * {@code InvalidDataAccessApiUsageException} ("Executing an update/delete query"
     * / no EntityManager transaction) — the production skip after a Capital fill.
     */
    @Transactional
    long deleteByBookAndSymbol(String book, String symbol);
}
