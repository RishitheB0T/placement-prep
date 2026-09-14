package com.rishikesh.placementprep.modules.auth.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.rishikesh.placementprep.modules.auth.dto.UpdateProfileRequest;
import com.rishikesh.placementprep.modules.auth.dto.UserProfileDTO;
import com.rishikesh.placementprep.modules.auth.service.UserService;

import jakarta.validation.Valid;

/**
 * The signed-in user's own account.
 *
 * <p>The path is /api/users/me rather than /api/users/{id}. There is no id in the URL to
 * tamper with, so one user can never request another user's profile - the identity comes
 * from the verified token instead. "me" is a widely used convention for exactly this.
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * Who am I, and what does the system know about me?
     *
     * <p>{@code @AuthenticationPrincipal} injects whatever JwtAuthenticationFilter placed
     * in the security context, so getUsername() here is the email from a token whose
     * signature has already been verified.
     */
    @GetMapping("/me")
    public UserProfileDTO me(@AuthenticationPrincipal UserDetails principal) {
        return userService.findByEmail(principal.getUsername())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Account no longer exists"));
    }

    /** Replaces my academic details. */
    @PutMapping("/me")
    public UserProfileDTO updateMe(@AuthenticationPrincipal UserDetails principal,
                                   @Valid @RequestBody UpdateProfileRequest request) {
        return userService.updateProfile(principal.getUsername(), request)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Account no longer exists"));
    }
}
