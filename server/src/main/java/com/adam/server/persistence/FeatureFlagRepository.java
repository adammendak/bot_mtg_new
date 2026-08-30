package com.adam.server.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FeatureFlagRepository extends JpaRepository<FeatureFlagEntity, Long> {

    Optional<FeatureFlagEntity> findByName(String name);

    List<FeatureFlagEntity> findAllByOrderByNameAsc();

    void deleteByName(String name);
}
