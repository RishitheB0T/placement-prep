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

    /** Accounts that run hiring: post drives and decide applications. */
    private static final String[] MANAGERS = {"TNP_PIC", "TNP_COORDINATOR", "RECRUITER"};

    /** Accounts that are students: apply, get resume feedback, use the assistant. */
    private static final String[] STUDENTS = {"STUDENT", "TNP_COORDINATOR"};

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
                    // through. ASYNC is the same idea for a streamed reply: when the stream
                    // ends, the container dispatches the request once more to finish it,
                    // and that second pass carries no token. None of these is a hole: the
                    // original request was already authorised, and the later dispatch only
                    // finishes what it started.
                    .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD, DispatcherType.ASYNC).permitAll()

                    // Reachable without a token, because you cannot get one otherwise.
                    .requestMatchers("/api/auth/register", "/api/auth/login").permitAll()
                    .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()

                    // Reading the board needs an account, but any role will do.
                    .requestMatchers(HttpMethod.GET, "/api/drives", "/api/drives/**").authenticated()

                    // Changing the board is for the people who run hiring: a company's
                    // recruiter, a coordinator running the board day to day, and the
                    // person in charge, who can do anything a coordinator can.
                    .requestMatchers(HttpMethod.POST, "/api/drives", "/api/drives/**")
                            .hasAnyRole(MANAGERS)
                    .requestMatchers(HttpMethod.PUT, "/api/drives/**")
                            .hasAnyRole(MANAGERS)
                    .requestMatchers(HttpMethod.DELETE, "/api/drives/**")
                            .hasAnyRole(MANAGERS)

                    // Applying, reading your own applications, and withdrawing are a
                    // student's actions - a coordinator is a student, a recruiter and the
                    // person in charge are not. ApplicationController resolves the
                    // caller's own id from the token, so nobody can act for someone else.
                    .requestMatchers(HttpMethod.POST, "/api/applications").hasAnyRole(STUDENTS)
                    .requestMatchers(HttpMethod.GET, "/api/applications/me").hasAnyRole(STUDENTS)
                    .requestMatchers(HttpMethod.POST, "/api/applications/*/withdraw").hasAnyRole(STUDENTS)

                    // Seeing who applied to a drive, and deciding what happens to them,
                    // is for the people running hiring. Left open, any student could read
                    // the whole applicant list for a drive they are competing in.
                    .requestMatchers(HttpMethod.GET, "/api/applications/drive/*")
                            .hasAnyRole(MANAGERS)
                    // Counts across every drive: the hiring side's dashboard, not a student's.
                    .requestMatchers(HttpMethod.GET, "/api/applications/stats")
                            .hasAnyRole(MANAGERS)
                    .requestMatchers(HttpMethod.PATCH, "/api/applications/*/status")
                            .hasAnyRole(MANAGERS)

                    // Adding a company's recruiter is the person in charge's decision, like
                    // promotion. Recruiters cannot register themselves: registration is for
                    // college addresses only.
                    .requestMatchers(HttpMethod.POST, "/api/users/recruiters").hasRole("TNP_PIC")

                    // Setting someone else's password - the "forgot password" path, since
                    // there is no email reset. The person in charge's alone. Changing your
                    // OWN password (PUT /api/users/me/password) needs only an account, so
                    // it falls through to the default rule at the bottom.
                    .requestMatchers(HttpMethod.POST, "/api/users/*/password").hasRole("TNP_PIC")

                    // CGPA, branch and marks belong to students. A recruiter or the person
                    // in charge has none to fill in. Reading /api/users/me stays open to all.
                    .requestMatchers(HttpMethod.PUT, "/api/users/me").hasAnyRole(STUDENTS)

                    // Staffing the cell is the person in charge's decision alone, so this
                    // one stays singular. A coordinator calling it gets a 403, which is
                    // what stops the cell from growing itself without oversight.
                    // The * matches exactly one path segment, so it cannot span an id.
                    .requestMatchers(HttpMethod.POST, "/api/users/*/promote").hasRole("TNP_PIC")

                    // The full list of accounts, for choosing whom to promote. Exactly
                    // "/api/users" - so /api/users/me stays open to every account.
                    .requestMatchers(HttpMethod.GET, "/api/users").hasRole("TNP_PIC")

                    // Designation, department, staff id and the rest belong to the person
                    // in charge specifically, not the cell in general - a coordinator gets
                    // a 403 here exactly like a student would. Declared before the plain
                    // /api/users/me rule has no bearing on matching order here since the
                    // two paths are distinct strings, but it sits next to the other
                    // PIC-only rule on principle.
                    .requestMatchers(HttpMethod.PUT, "/api/users/me/staff-profile").hasRole("TNP_PIC")

                    // Resume feedback is a student's tool, and a coordinator is a student.
                    // The person in charge is faculty with no resume to review, so they get
                    // a 403 here. ResumeController keys every path to the caller's own id,
                    // so one rule covers uploading, reading, downloading and retrying.
                    .requestMatchers("/api/resumes", "/api/resumes/**")
                            .hasAnyRole(STUDENTS)

                    // The assistant's tools all act as a student - eligible drives, my
                    // applications, apply - so it follows the same rule as resumes.
                    .requestMatchers("/api/agent/**").hasAnyRole(STUDENTS)

                    // Notifications are written only for students - new drives, deadline
                    // reminders, status changes - so only students can read them.
                    .requestMatchers("/api/notifications", "/api/notifications/**").hasAnyRole(STUDENTS)

                    // Searching job descriptions spans every company's drives. A
                    // recruiter sees only their own drives, so this is closed to them.
                    .requestMatchers(HttpMethod.GET, "/api/job-descriptions/search")
                            .hasAnyRole("STUDENT", "TNP_COORDINATOR", "TNP_PIC")

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
