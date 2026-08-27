package com.adam.server.web;

import com.adam.server.auth.DashboardAuthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class LoginController {

    private final DashboardAuthService auth;

    public LoginController(DashboardAuthService auth) {
        this.auth = auth;
    }

    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
        String username = request == null ? null : request.username();
        String password = request == null ? null : request.password();
        if (!auth.authenticate(username, password)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(new LoginResponse(username));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent().build();
    }

    public record LoginRequest(String username, String password) {
    }

    public record LoginResponse(String username) {
    }
}
