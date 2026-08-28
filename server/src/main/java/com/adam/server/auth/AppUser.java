package com.adam.server.auth;

import java.security.Principal;
import java.util.List;
import java.util.Set;

/**
 * Authenticated portal user carried in the {@link Principal} / Spring Security
 * context. {@code books} is the set of broker books this user may see; ADMIN
 * users still get their explicit grants but authorization treats them as
 * all-books.
 *
 * @param id          persisted user id
 * @param username    login name
 * @param displayName human label shown in the UI
 * @param role        ADMIN or USER
 * @param books       broker books granted to this user (empty = none visible)
 */
public record AppUser(
        Long id,
        String username,
        String displayName,
        UserRole role,
        Set<String> books
) implements Principal {

    public boolean isAdmin() {
        return role == UserRole.ADMIN;
    }

    public boolean canSeeBook(String book) {
        return isAdmin() || (book != null && books.contains(book));
    }

    @Override
    public String getName() {
        return username;
    }

    public List<String> booksSorted() {
        return books.stream().sorted().toList();
    }
}
