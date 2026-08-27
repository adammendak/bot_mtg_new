package com.adam.server.auth;

import com.adam.server.config.AppProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Checks dashboard UI credentials against {@code DASHBOARD_USERNAME} /
 * {@code DASHBOARD_PASSWORD}. Missing values fail closed for login only;
 * scan, health, and webhooks do not use this service.
 */
@Service
public class DashboardAuthService {

    private static final Logger log = LoggerFactory.getLogger(DashboardAuthService.class);

    private final String username;
    private final String password;

    public DashboardAuthService(AppProperties properties) {
        this(properties.getDashboard().getUsername(), properties.getDashboard().getPassword());
    }

    DashboardAuthService(String username, String password) {
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
    }

    @PostConstruct
    void warnIfUnconfigured() {
        if (!configured()) {
            log.warn("Dashboard login is fail-closed: set DASHBOARD_USERNAME and DASHBOARD_PASSWORD. Scan and health stay open.");
        }
    }

    public boolean configured() {
        return !username.isBlank() && !password.isBlank();
    }

    public boolean authenticate(String givenUser, String givenPass) {
        if (!configured() || givenUser == null || givenPass == null) {
            return false;
        }
        byte[] expectedUser = username.getBytes(StandardCharsets.UTF_8);
        byte[] actualUser = givenUser.getBytes(StandardCharsets.UTF_8);
        byte[] expectedPass = password.getBytes(StandardCharsets.UTF_8);
        byte[] actualPass = givenPass.getBytes(StandardCharsets.UTF_8);
        boolean userOk = MessageDigest.isEqual(expectedUser, actualUser);
        boolean passOk = MessageDigest.isEqual(expectedPass, actualPass);
        return userOk && passOk;
    }
}
