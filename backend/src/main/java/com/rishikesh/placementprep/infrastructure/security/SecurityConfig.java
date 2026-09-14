package com.rishikesh.placementprep.infrastructure.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import jakarta.servlet.DispatcherType;

/**
 * The rulebook: who may call what, and how a caller is identified.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            JwtAuthenticationFilter jwtAuthenticationFilter,
                                            JsonAuthenticationErrorHandler errorHandler) throws Exception {
        http
            // CSRF protection defends against a browser silently replaying a cookie the
            // user already has. This API carries no cookies and no session, so there is
            // nothing to replay: a caller without the token cannot forge a request no
            // matter what site they are on. Leaving it enabled would simply reject every
            // POST, PUT and DELETE with a 403.
            .csrf(csrf -> csrf.disable())

            // Never create an HttpSession. Every request must carry its own proof, which
            // is what makes the API horizontally scalable: any instance can serve any
            // request without shared session storage.
            .sessionManagement(session ->
                    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            .authorizeHttpRequests(auth -> auth
                    // Since Spring Security 6 the filter chain runs on every dispatcher
                    // type, not just the original request. When a controller throws, the
                    // servlet container re-dispatches to /error, which would then be
                    // matched by anyRequest().authenticated() and answered with 401 -
                    // replacing the real 400, 404 or 409 the caller should have seen.
                    // Permitting the ERROR and FORWARD dispatches lets the genuine status
                    // through. This is not a hole: the original request was already
                    // authorised, and this dispatch only renders the error it produced.
                    .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD).permitAll()

                    // Reachable without a token, because you cannot get one otherwise.
                    .requestMatchers("/api/auth/register", "/api/auth/login").permitAll()
                    .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()

                    // Reading the board needs an account, but either role will do.
                    .requestMatchers(HttpMethod.GET, "/api/drives", "/api/drives/**").authenticated()

                    // Changing the board is the training-and-placement cell's job only.
                    .requestMatchers(HttpMethod.POST, "/api/drives", "/api/drives/**").hasRole("TNP_ADMIN")
                    .requestMatchers(HttpMethod.PUT, "/api/drives/**").hasRole("TNP_ADMIN")
                    .requestMatchers(HttpMethod.DELETE, "/api/drives/**").hasRole("TNP_ADMIN")

                    // Default deny. Anything added later is locked until a rule is written
                    // for it, which fails safe rather than silently exposing a new endpoint.
                    .anyRequest().authenticated())

            .exceptionHandling(handling -> handling
                    .authenticationEntryPoint(errorHandler)   // no or bad token  -> 401
                    .accessDeniedHandler(errorHandler))       // valid, wrong role -> 403

            // Run before the username/password filter so that a request carrying a token
            // is already authenticated by the time the standard machinery looks at it.
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * BCrypt deliberately takes a noticeable amount of time per hash, which is what makes
     * brute-forcing a stolen database expensive. It also salts internally, so identical
     * passwords do not produce identical hashes.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Wires the password check used by the login endpoint.
     *
     * <p>In Spring Security 7 DaoAuthenticationProvider takes its UserDetailsService as a
     * constructor argument; the older no-argument constructor plus setUserDetailsService
     * that most tutorials still show has been removed.
     */
    @Bean
    AuthenticationManager authenticationManager(UserDetailsService userDetailsService,
                                                PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }
}
