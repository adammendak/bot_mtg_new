package com.adam.server.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One row = one broker book (demo/live/glowne) a user is allowed to see.
 * Isolation rule: a USER sees only the books listed here; an ADMIN sees all.
 */
@Entity
@Table(name = "user_books")
@Getter
@Setter
public class UserBookEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 16)
    private String book;
}
