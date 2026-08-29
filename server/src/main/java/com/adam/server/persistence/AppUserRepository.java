package com.adam.server.persistence;

import com.adam.server.auth.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUserEntity, Long> {
    Optional<AppUserEntity> findByUsername(String username);

    /** Login lookup: usernames are matched case-insensitively ("Adam" == "adam"). */
    Optional<AppUserEntity> findByUsernameIgnoreCase(String username);

    List<AppUserEntity> findAllByOrderByUsernameAsc();

    boolean existsByUsername(String username);

    /** Guards create/rename so "Adam" cannot be added while "adam" exists. */
    boolean existsByUsernameIgnoreCase(String username);

    List<AppUserEntity> findByRole(UserRole role);
}
