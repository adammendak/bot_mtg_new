package com.adam.server.web;

import com.adam.server.auth.AuthService;
import com.adam.server.auth.UserRole;
import com.adam.server.persistence.AppUserEntity;
import com.adam.server.persistence.AppUserRepository;
import com.adam.server.persistence.UserBookEntity;
import com.adam.server.persistence.UserBookRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin-only user management: list/create/update/delete portal users and the
 * broker books each may see. Secured by {@code /api/admin/** -> hasRole(ADMIN)}.
 */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final AppUserRepository users;
    private final UserBookRepository userBooks;

    public AdminUserController(AppUserRepository users, UserBookRepository userBooks) {
        this.users = users;
        this.userBooks = userBooks;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return users.findAllByOrderByUsernameAsc().stream().map(this::toJson).toList();
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public ResponseEntity<Object> create(@RequestBody UpsertUser body) {
        if (body.username() == null || body.username().isBlank()) {
            return bad("username is required");
        }
        String username = body.username().trim();
        if (users.existsByUsername(username)) {
            return bad("user already exists: " + username);
        }
        if (body.password() == null || body.password().length() < 6) {
            return bad("password must be at least 6 characters");
        }
        AppUserEntity entity = new AppUserEntity();
        entity.setUsername(username);
        entity.setDisplayName(body.displayName() == null ? username : body.displayName());
        entity.setPasswordHash(AuthService.hashFor(body.password()));
        entity.setRole(body.role() == null ? UserRole.USER : body.role());
        users.save(entity);
        saveBooks(entity.getId(), body.books());
        return ResponseEntity.status(HttpStatus.CREATED).body(toJson(entity));
    }

    @PutMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public ResponseEntity<Object> update(@PathVariable Long id, @RequestBody UpsertUser body) {
        AppUserEntity entity = users.findById(id).orElse(null);
        if (entity == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        if (body.displayName() != null) {
            entity.setDisplayName(body.displayName());
        }
        if (body.role() != null) {
            entity.setRole(body.role());
        }
        if (body.password() != null && !body.password().isBlank()) {
            if (body.password().length() < 6) {
                return bad("password must be at least 6 characters");
            }
            entity.setPasswordHash(AuthService.hashFor(body.password()));
        }
        if (body.books() != null) {
            userBooks.deleteByUserId(id);
            userBooks.flush(); // force DELETE before re-INSERT (unique (user_id, book))
            saveBooks(id, body.books());
        }
        users.save(entity);
        return ResponseEntity.ok(toJson(entity));
    }

    @DeleteMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public Map<String, Object> delete(@PathVariable Long id) {
        userBooks.deleteByUserId(id);
        users.deleteById(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        return out;
    }

    private void saveBooks(Long userId, List<String> books) {
        if (books == null) {
            return;
        }
        for (String book : books.stream().distinct().toList()) {
            if (book == null || book.isBlank()) {
                continue;
            }
            UserBookEntity row = new UserBookEntity();
            row.setUserId(userId);
            row.setBook(book.trim());
            userBooks.save(row);
        }
    }

    private Map<String, Object> toJson(AppUserEntity entity) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", entity.getId());
        m.put("username", entity.getUsername());
        m.put("displayName", entity.getDisplayName());
        m.put("role", entity.getRole().name());
        m.put("createdAt", entity.getCreatedAt());
        m.put("books", userBooks.findByUserId(entity.getId()).stream()
                .map(UserBookEntity::getBook)
                .sorted()
                .toList());
        return m;
    }

    private static ResponseEntity<Object> bad(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", "bad_request", "message", message));
    }

    public record UpsertUser(String username, String displayName, String password, UserRole role, List<String> books) {
    }
}
