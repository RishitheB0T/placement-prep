package com.rishikesh.placementprep.modules.auth.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.rishikesh.placementprep.modules.auth.dto.AuthResponse;
import com.rishikesh.placementprep.modules.auth.dto.LoginRequest;
import com.rishikesh.placementprep.modules.auth.dto.RegisterRequest;
import com.rishikesh.placementprep.modules.auth.service.AuthService;

import jakarta.validation.Valid;

/**
 * Sign-up and sign-in. Both endpoints are reachable without a token, since a caller has
 * no way to obtain one before using them.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * Creates a student account and returns a token, so registering signs the user in
     * rather than making them log in again immediately afterwards.
     */
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT, "That email is already registered"));

        // 409 Conflict, not 400. The request is perfectly well formed; it clashes with
        // the current state of the server, which is exactly what 409 is for.
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Exchanges credentials for a token.
     *
     * <p>The failure is a flat 401 with no detail. Distinguishing "no such account" from
     * "wrong password" would turn this endpoint into a way to discover which email
     * addresses are registered.
     */
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Invalid email or password"));
    }

    /**
     * Issues a fresh token carrying the account's current role.
     *
     * <p>Needs a valid token already (SecurityConfig's default rule), so it cannot be used
     * to sign in. It exists for one situation: the browser reads the role inside its token
     * to decide which pages to show, so after a promotion the old token would keep hiding
     * the coordinator's pages until the next sign-in. The frontend calls this when the
     * profile's role and the token's role disagree, and the new pages appear immediately.
     */
    @PostMapping("/refresh")
    public AuthResponse refresh(@AuthenticationPrincipal UserDetails principal) {
        return authService.refresh(principal.getUsername())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Account no longer exists"));
    }
}
