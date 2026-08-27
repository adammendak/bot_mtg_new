package com.adam.server.config;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Parses Heroku {@code DATABASE_URL} / {@code JDBC_DATABASE_URL} into JDBC properties.
 * Password is never included in {@link Parsed#jdbcUrl()} so logs cannot leak it.
 */
public final class DatabaseUrl {

    private DatabaseUrl() {
    }

    public static Optional<Parsed> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String trimmed = raw.trim();
        try {
            if (startsWithIgnoreCase(trimmed, "postgres://")) {
                return Optional.of(fromPostgresUri("postgresql://" + trimmed.substring("postgres://".length())));
            }
            if (startsWithIgnoreCase(trimmed, "postgresql://")) {
                return Optional.of(fromPostgresUri(trimmed));
            }
            if (startsWithIgnoreCase(trimmed, "jdbc:postgres://")) {
                return Optional.of(fromJdbc("jdbc:postgresql://" + trimmed.substring("jdbc:postgres://".length())));
            }
            if (startsWithIgnoreCase(trimmed, "jdbc:postgresql://")) {
                return Optional.of(fromJdbc(trimmed));
            }
            return Optional.empty();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid DATABASE_URL (expected postgres:// or jdbc:postgresql://)");
        }
    }

    private static Parsed fromPostgresUri(String postgresqlUri) {
        URI uri = URI.create(postgresqlUri);
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Invalid DATABASE_URL (expected postgres:// or jdbc:postgresql://)");
        }
        int port = uri.getPort() > 0 ? uri.getPort() : 5432;
        String path = uri.getPath() == null ? "" : uri.getPath();
        String database = path.startsWith("/") ? path.substring(1) : path;
        if (database.isBlank()) {
            throw new IllegalArgumentException("Invalid DATABASE_URL (expected postgres:// or jdbc:postgresql://)");
        }
        String username = null;
        String password = null;
        String userInfo = uri.getRawUserInfo();
        if (userInfo != null && !userInfo.isBlank()) {
            int colon = userInfo.indexOf(':');
            if (colon < 0) {
                username = decode(userInfo);
            } else {
                username = decode(userInfo.substring(0, colon));
                password = decode(userInfo.substring(colon + 1));
            }
        }
        Map<String, String> query = parseQuery(uri.getRawQuery());
        if (username == null) {
            username = query.remove("user");
        } else {
            query.remove("user");
        }
        if (password == null) {
            password = query.remove("password");
        } else {
            query.remove("password");
        }
        applySslDefault(host, query);
        String jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + database + formatQuery(query);
        return new Parsed(jdbcUrl, username, password);
    }

    private static Parsed fromJdbc(String jdbc) {
        String rest = jdbc.substring("jdbc:postgresql://".length());
        String username = null;
        String password = null;
        int at = rest.lastIndexOf('@');
        int slash = rest.indexOf('/');
        if (at > 0 && (slash < 0 || at < slash)) {
            String userInfo = rest.substring(0, at);
            rest = rest.substring(at + 1);
            int colon = userInfo.indexOf(':');
            if (colon < 0) {
                username = decode(userInfo);
            } else {
                username = decode(userInfo.substring(0, colon));
                password = decode(userInfo.substring(colon + 1));
            }
        }
        int q = rest.indexOf('?');
        String hostDb = q < 0 ? rest : rest.substring(0, q);
        String rawQuery = q < 0 ? null : rest.substring(q + 1);
        slash = hostDb.indexOf('/');
        if (slash < 0) {
            throw new IllegalArgumentException("Invalid DATABASE_URL (expected postgres:// or jdbc:postgresql://)");
        }
        String hostPort = hostDb.substring(0, slash);
        String database = hostDb.substring(slash + 1);
        if (database.isBlank()) {
            throw new IllegalArgumentException("Invalid DATABASE_URL (expected postgres:// or jdbc:postgresql://)");
        }
        String host;
        int port = 5432;
        int colon = hostPort.lastIndexOf(':');
        if (colon >= 0) {
            host = hostPort.substring(0, colon);
            port = Integer.parseInt(hostPort.substring(colon + 1));
        } else {
            host = hostPort;
        }
        Map<String, String> query = parseQuery(rawQuery);
        if (username == null) {
            username = query.remove("user");
        } else {
            query.remove("user");
        }
        if (password == null) {
            password = query.remove("password");
        } else {
            query.remove("password");
        }
        applySslDefault(host, query);
        String jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + database + formatQuery(query);
        return new Parsed(jdbcUrl, username, password);
    }

    private static void applySslDefault(String host, Map<String, String> query) {
        if (query.containsKey("sslmode")) {
            return;
        }
        if (isLocal(host)) {
            return;
        }
        query.put("sslmode", "require");
    }

    private static boolean isLocal(String host) {
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        return "localhost".equals(h) || "127.0.0.1".equals(h) || "::1".equals(h);
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> out = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return out;
        }
        for (String part : rawQuery.split("&")) {
            if (part.isBlank()) {
                continue;
            }
            int eq = part.indexOf('=');
            if (eq < 0) {
                out.put(decode(part), "");
            } else {
                out.put(decode(part.substring(0, eq)), decode(part.substring(eq + 1)));
            }
        }
        return out;
    }

    private static String formatQuery(Map<String, String> query) {
        if (query.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("?");
        boolean first = true;
        for (Map.Entry<String, String> e : query.entrySet()) {
            if (!first) {
                sb.append('&');
            }
            first = false;
            sb.append(e.getKey());
            if (e.getValue() != null) {
                sb.append('=').append(e.getValue());
            }
        }
        return sb.toString();
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static boolean startsWithIgnoreCase(String s, String prefix) {
        return s.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    public static final class Parsed {
        private final String jdbcUrl;
        private final String username;
        private final String password;

        Parsed(String jdbcUrl, String username, String password) {
            this.jdbcUrl = jdbcUrl;
            this.username = username;
            this.password = password;
        }

        public String jdbcUrl() {
            return jdbcUrl;
        }

        public String username() {
            return username;
        }

        public String password() {
            return password;
        }

        @Override
        public String toString() {
            return "Parsed{jdbcUrl='(redacted)', username='(redacted)', password='***'}";
        }
    }
}
