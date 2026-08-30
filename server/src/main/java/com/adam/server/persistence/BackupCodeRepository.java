package com.adam.server.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface BackupCodeRepository extends JpaRepository<BackupCodeEntity, Long> {

    List<BackupCodeEntity> findByUserIdAndUsedAtIsNull(Long userId);

    @Transactional
    void deleteByUserId(Long userId);
}
