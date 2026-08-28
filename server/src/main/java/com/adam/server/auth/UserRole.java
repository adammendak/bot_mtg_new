package com.adam.server.auth;

/**
 * Access level for a portal user. ADMIN can manage users and sees every book;
 * USER sees only the books granted to them in {@code user_books}.
 */
public enum UserRole {
    ADMIN,
    USER
}
