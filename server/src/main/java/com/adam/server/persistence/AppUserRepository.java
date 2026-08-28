package com.adam.server.persistence;

import com.adam.server.auth.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUserEntity, Long> {
    Optional<AppUserEntity> findByUsername(String username);

    List<AppUserEntity> findAllByOrderByUsernameAsc();

    boolean existsByUsername(String username);

    List<AppUserEntity> findByRole(UserRole role);
}
