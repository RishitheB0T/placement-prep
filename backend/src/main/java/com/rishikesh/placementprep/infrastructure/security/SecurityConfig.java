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
                    // Both cell roles do it: coordinators run the board day to day, and
                    // the person in charge can do anything a coordinator can.
                    .requestMatchers(HttpMethod.POST, "/api/drives", "/api/drives/**")
                            .hasAnyRole("TNP_PIC", "TNP_COORDINATOR")
                    .requestMatchers(HttpMethod.PUT, "/api/drives/**")
                            .hasAnyRole("TNP_PIC", "TNP_COORDINATOR")
                    .requestMatchers(HttpMethod.DELETE, "/api/drives/**")
                            .hasAnyRole("TNP_PIC", "TNP_COORDINATOR")

                    // Applying, reading your own applications, and withdrawing need only
                    // an account - ApplicationController resolves the caller's own id
                    // from the token, so there is nothing here for a role to gate.
                    .requestMatchers(HttpMethod.POST, "/api/applications").authenticated()
                    .requestMatchers(HttpMethod.GET, "/api/applications/me").authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/applications/*/withdraw").authenticated()

                    // Seeing who applied to a drive, and deciding what happens to them,
                    // is the placement cell's job. Left open, any student could read the
                    // whole applicant list for a drive they are competing in.
                    .requestMatchers(HttpMethod.GET, "/api/applications/drive/*")
                            .hasAnyRole("TNP_PIC", "TNP_COORDINATOR")
                    .requestMatchers(HttpMethod.PATCH, "/api/applications/*/status")
                            .hasAnyRole("TNP_PIC", "TNP_COORDINATOR")

                    // Staffing the cell is the person in charge's decision alone, so this
                    // one stays singular. A coordinator calling it gets a 403, which is
                    // what stops the cell from growing itself without oversight.
                    // The * matches exactly one path segment, so it cannot span an id.
                    .requestMatchers(HttpMethod.POST, "/api/users/*/promote").hasRole("TNP_PIC")

                    // Designation, department, staff id and the rest belong to the person
                    // in charge specifically, not the cell in general - a coordinator gets
                    // a 403 here exactly like a student would. Declared before the plain
                    // /api/users/me rule has no bearing on matching order here since the
                    // two paths are distinct strings, but it sits next to the other
                    // PIC-only rule on principle.
                    .requestMatchers(HttpMethod.PUT, "/api/users/me/staff-profile").hasRole("TNP_PIC")

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
