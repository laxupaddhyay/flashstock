package com.flashstock.config;

import com.flashstock.security.CustomerPrincipal;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.HttpStatus;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.MDC;

@Configuration
public class SecurityConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    UserDetailsService userDetailsService(FlashStockProperties properties, PasswordEncoder encoder) {
        String password = properties.getSecurity().getDevPassword();
        if (password == null || password.isBlank() || "change-me-local-only".equals(password)) {
            throw new IllegalStateException("Set DEV_PASSWORD to a unique local value before starting FlashStock.");
        }
        String username = properties.getSecurity().getDevUser();
        var principal = new CustomerPrincipal(UUID.nameUUIDFromBytes(username.getBytes(StandardCharsets.UTF_8)), username, encoder.encode(password));
        return requested -> {
            if (!requested.equals(username)) throw new org.springframework.security.core.userdetails.UsernameNotFoundException("User not found");
            return principal;
        };
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        return http.csrf(csrf -> csrf.ignoringRequestMatchers("/api/**", "/actuator/**"))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> writeProblem(objectMapper, response, HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Authentication is required"))
                        .accessDeniedHandler((request, response, exception) -> writeProblem(objectMapper, response, HttpStatus.FORBIDDEN, "ACCESS_DENIED", "You cannot access this resource")))
                .httpBasic(Customizer.withDefaults()).build();
    }

    private void writeProblem(ObjectMapper mapper, jakarta.servlet.http.HttpServletResponse response, HttpStatus status, String code, String message) throws java.io.IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, message);
        problem.setType(URI.create("https://flashstock.dev/problems/" + code.toLowerCase().replace('_', '-')));
        problem.setProperty("code", code);
        String correlationId = MDC.get("correlationId");
        if (correlationId != null) problem.setProperty("correlationId", correlationId);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), problem);
    }
}
