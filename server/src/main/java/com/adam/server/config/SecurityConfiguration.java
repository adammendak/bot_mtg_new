package com.adam.server.config;

import com.adam.server.auth.TokenAuthFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Security wiring for the portal auth:
 * <ul>
 *   <li>Public: the SPA (static resources), {@code /health} and
 *       {@code POST /api/auth/login}.</li>
 *   <li>{@code /api/**} requires a valid bearer token (login required to see
 *       any account data).</li>
 *   <li>{@code /api/admin/**} additionally requires {@code ROLE_ADMIN}.</li>
 * </ul>
 * Book-level isolation (which books a USER may see) is enforced in the
 * controllers/services against {@link com.adam.server.auth.AppUser}.
 */
@Configuration
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, TokenAuthFilter tokenAuthFilter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                // SAMEORIGIN, not disabled: the H2 console (dev only) frames itself
                // same-origin and still works, while the dashboard can no longer be
                // embedded in a third-party page (clickjacking).
                .headers(headers -> headers.frameOptions(HeadersConfigurer.FrameOptionsConfig::sameOrigin))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(HttpStatus.UNAUTHORIZED.value());
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.getWriter().write("{\"error\":\"unauthorized\",\"message\":\"Missing or invalid token.\"}");
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setStatus(HttpStatus.FORBIDDEN.value());
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.getWriter().write("{\"error\":\"forbidden\",\"message\":\"You do not have access to this resource.\"}");
                        }))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/health", "/h2-console/**", "/error", "/").permitAll()
                        .requestMatchers("/api/auth/login", "/api/auth/login/totp").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .addFilterBefore(tokenAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
