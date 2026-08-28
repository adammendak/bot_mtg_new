package com.adam.server.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SddExecutionRepository extends JpaRepository<SddExecutionEntity, Long> {
    List<SddExecutionEntity> findByBook(String book);

    List<SddExecutionEntity> findByBookAndSymbol(String book, String symbol);

    long deleteByBookAndSymbol(String book, String symbol);
}
