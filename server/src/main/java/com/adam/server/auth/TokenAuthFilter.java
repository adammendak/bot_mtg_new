package com.adam.server.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Reads the {@code Authorization: Bearer <token>} header and, when valid, fills
 * the SecurityContext with an {@link AppUser} principal. No token (or a bad one)
 * simply leaves the context empty so {@code permitAll} endpoints stay open and
 * protected ones answer 401/403.
 */
@Component
public class TokenAuthFilter extends OncePerRequestFilter {

    public static final String HEADER = "Authorization";
    public static final String BEARER = "Bearer ";

    private final AuthService auth;

    public TokenAuthFilter(AuthService auth) {
        this.auth = auth;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = bearerToken(request);
        if (token != null && !token.isBlank()) {
            AppUser user = auth.authenticate(token);
            if (user != null) {
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                user,
                                null,
                                List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * Bearer token from the {@code Authorization} header, or — for the SSE stream
     * {@code GET /api/live}, which the browser's EventSource cannot send headers
     * on — from the {@code access_token} query parameter. The query fallback is
     * accepted only for GET so it can never authorise a mutating request from a
     * link, and the endpoint still filters its payload to the user's books.
     */
    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header != null && header.startsWith(BEARER)) {
            return header.substring(BEARER.length()).trim();
        }
        if ("GET".equalsIgnoreCase(request.getMethod())) {
            String param = request.getParameter("access_token");
            if (param != null && !param.isBlank()) {
                return param.trim();
            }
        }
        return null;
    }
}
