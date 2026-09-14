package com.rishikesh.placementprep.modules.auth.service;

import java.util.Optional;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rishikesh.placementprep.modules.auth.dto.AuthResponse;
import com.rishikesh.placementprep.modules.auth.dto.LoginRequest;
import com.rishikesh.placementprep.modules.auth.dto.RegisterRequest;
import com.rishikesh.placementprep.modules.auth.model.Role;
import com.rishikesh.placementprep.modules.auth.model.User;
import com.rishikesh.placementprep.modules.auth.repository.UserRepository;

/**
 * Registration and login.
 *
 * <p>Like DriveService, this returns an empty Optional to mean "it did not work" and
 * leaves the choice of status code to the controller. It does import Spring Security
 * types, which DriveService deliberately avoids doing with web types, but authentication
 * is this class's whole subject: depending on the authentication framework is its job,
 * not a leak from another layer.
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       AuthenticationManager authenticationManager,
                       JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
    }

    /**
     * Creates a STUDENT account and signs it in.
     *
     * <p>The role is hard-coded rather than read from the request. If the client could
     * choose, any student could register as TNP_ADMIN and start publishing fake drives.
     *
     * @return empty when the email is already registered
     */
    @Transactional
    public Optional<AuthResponse> register(RegisterRequest request) {
        String email = normalise(request.email());

        if (userRepository.existsByEmail(email)) {
            return Optional.empty();
        }

        User user = new User();
        user.setEmail(email);
        // Hash, never store. BCrypt is one-way and salts each hash internally, so two
        // users who pick the same password still get different values in the database.
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(Role.STUDENT);

        User saved = userRepository.save(user);
        return Optional.of(toResponse(saved.getEmail(), saved.getRole()));
    }

    /**
     * Verifies credentials and issues a token.
     *
     * <p>Delegating to AuthenticationManager rather than comparing hashes by hand means
     * the user lookup, the password check and its timing-safe comparison all come from
     * Spring Security, and later additions such as account locking plug in without
     * changing this method.
     *
     * @return empty when the email is unknown or the password is wrong. The caller must
     *         not distinguish the two, because doing so reveals which accounts exist.
     */
    // Deliberately NOT @Transactional. An unknown email makes loadUserByUsername throw
    // UsernameNotFoundException, and a RuntimeException escaping a @Transactional method
    // marks the surrounding transaction rollback-only. Catching it here would not undo
    // that mark, so the commit at the end of this method would fail with
    // UnexpectedRollbackException even though the login was handled correctly. Nothing
    // here needs to be atomic anyway: it is one read plus a token, and the read already
    // has its own transaction inside AppUserDetailsService.
    public Optional<AuthResponse> login(LoginRequest request) {
        String email = normalise(request.email());

        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(email, request.password()));

            return Optional.of(toResponse(email, roleOf(authentication)));

        } catch (AuthenticationServiceException e) {
            // The lookup itself broke: database down, or a misconfiguration. That is a
            // server fault, not a rejected login, so let it escalate to a 500 rather than
            // telling the caller their password was wrong.
            throw e;
        } catch (AuthenticationException e) {
            return Optional.empty();
        }
    }

    /** Reverses the ROLE_ prefix that AppUserDetailsService adds. */
    private Role roleOf(Authentication authentication) {
        String authority = authentication.getAuthorities().iterator().next().getAuthority();
        return Role.valueOf(authority.replaceFirst("^ROLE_", ""));
    }

    private AuthResponse toResponse(String email, Role role) {
        return new AuthResponse(
                jwtService.generateToken(email, role),
                email,
                role,
                jwtService.expiresAtEpochMillis());
    }

    /**
     * Email addresses are case-insensitive in practice, so store and compare them in one
     * casing. Without this, Rishi@x.com and rishi@x.com become two separate accounts and
     * the unique constraint does nothing to stop it.
     */
    private String normalise(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }
}
