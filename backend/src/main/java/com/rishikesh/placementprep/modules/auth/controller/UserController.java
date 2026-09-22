package com.rishikesh.placementprep.modules.auth.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.rishikesh.placementprep.modules.auth.dto.UpdateProfileRequest;
import com.rishikesh.placementprep.modules.auth.dto.UpdateStaffProfileRequest;
import com.rishikesh.placementprep.modules.auth.dto.UserProfileDTO;
import com.rishikesh.placementprep.modules.auth.service.UserService;

import jakarta.validation.Valid;

/**
 * The signed-in user's own account.
 *
 * <p>The self-service paths are /api/users/me rather than /api/users/{id}. There is no id
 * in the URL to tamper with, so one user can never request another user's profile - the
 * identity comes from the verified token instead. "me" is a widely used convention for
 * exactly this. Promotion is the deliberate exception, and is locked to the person in
 * charge because of it.
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

    /**
     * Replaces the person in charge's own staff details - designation, department, staff
     * id, office location, phone number. Restricted to TNP_PIC by SecurityConfig; a
     * coordinator calling this gets a 403 same as a student would.
     */
    @PutMapping("/me/staff-profile")
    public UserProfileDTO updateStaffProfile(@AuthenticationPrincipal UserDetails principal,
                                             @Valid @RequestBody UpdateStaffProfileRequest request) {
        return userService.updateStaffProfile(principal.getUsername(), request)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Account no longer exists"));
    }

    /**
     * Makes a student a coordinator. Restricted to the person in charge by SecurityConfig,
     * so no role check is repeated here.
     *
     * <p>This is the one endpoint that names another user by id, which is unavoidable:
     * the whole point is to act on somebody else's account.
     */
    @PostMapping("/{id}/promote")
    public UserProfileDTO promote(@PathVariable Long id) {
        return userService.promoteToCoordinator(id).orElseThrow(() -> explainFailure(id));
    }

    /**
     * Works out why a promotion returned nothing, so the caller gets an accurate status.
     *
     * <p>The extra lookup happens only on the failure path; a successful promotion still
     * costs one round trip. Telling the two cases apart is the controller's job because
     * the distinction is purely about HTTP: the service only knows "that did not happen".
     */
    private ResponseStatusException explainFailure(Long id) {
        return userService.findById(id)
                .map(existing -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "Only a student can be promoted, and this account is already "
                                + existing.role()))
                .orElseGet(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No account with id " + id));
    }
}
