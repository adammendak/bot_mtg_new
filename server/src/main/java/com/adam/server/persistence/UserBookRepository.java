package com.adam.server.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserBookRepository extends JpaRepository<UserBookEntity, Long> {
    List<UserBookEntity> findByUserId(Long userId);

    void deleteByUserId(Long userId);
}
