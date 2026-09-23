package com.rishikesh.placementprep.modules.application.controller;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.rishikesh.placementprep.modules.application.dto.ApplicationDTO;
import com.rishikesh.placementprep.modules.application.dto.ApplyRequest;
import com.rishikesh.placementprep.modules.application.dto.UpdateStatusRequest;
import com.rishikesh.placementprep.modules.application.service.ApplicationService;
import com.rishikesh.placementprep.modules.auth.dto.UserProfileDTO;
import com.rishikesh.placementprep.modules.auth.service.UserService;

import jakarta.validation.Valid;

/**
 * Applying to drives, and tracking what happened next.
 *
 * <p>Every student-facing path resolves the caller's own numeric id from their token via
 * UserService - the same lookup DriveController already does for eligibility - rather
 * than trusting an id the client could send. The two endpoints restricted to staff by
 * SecurityConfig are the only ones that ever act on somebody else's application.
 */
@RestController
@RequestMapping("/api/applications")
public class ApplicationController {

    private final ApplicationService applicationService;
    private final UserService userService;

    public ApplicationController(ApplicationService applicationService, UserService userService) {
        this.applicationService = applicationService;
        this.userService = userService;
    }

    @PostMapping
    public ResponseEntity<ApplicationDTO> apply(@AuthenticationPrincipal UserDetails principal,
                                                @Valid @RequestBody ApplyRequest request) {
        ApplicationDTO created = applicationService.apply(callerId(principal), request.driveId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "You have already applied to this drive"));

        return ResponseEntity.created(URI.create("/api/applications/" + created.id())).body(created);
    }

    @GetMapping("/me")
    public List<ApplicationDTO> mine(@AuthenticationPrincipal UserDetails principal) {
        return applicationService.findMine(callerId(principal));
    }

    /**
     * Everyone who applied to one drive. Restricted to staff by SecurityConfig.
     *
     * <p>Each row is labelled with the applicant's email, which the cell needs in order to
     * review anybody - a bare numeric id is not something a human can act on. The lookup
     * is one batched query for the whole list rather than one per row, and it happens here
     * rather than in ApplicationService so that the application module stays independent
     * of the auth module's storage.
     */
    @GetMapping("/drive/{driveId}")
    public List<ApplicationDTO> forDrive(@PathVariable Long driveId) {
        List<ApplicationDTO> applications = applicationService.findForDrive(driveId);

        Map<Long, String> emails = userService.emailsByIds(
                applications.stream().map(ApplicationDTO::studentId).collect(Collectors.toSet()));

        return applications.stream()
                .map(application -> application.withStudentEmail(emails.get(application.studentId())))
                .toList();
    }

    /** The placement cell moving an application along. Restricted by SecurityConfig. */
    @PatchMapping("/{id}/status")
    public ApplicationDTO updateStatus(@PathVariable Long id,
                                       @Valid @RequestBody UpdateStatusRequest request) {
        return applicationService.updateStatus(id, request)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No application with id " + id));
    }

    /**
     * A student pulling out of a drive.
     *
     * <p>A 404 rather than a 403 when the application belongs to someone else. Answering
     * 403 would confirm that an application with that id exists at all, which is
     * information the caller has no business having.
     */
    @PostMapping("/{id}/withdraw")
    public ApplicationDTO withdraw(@PathVariable Long id,
                                   @AuthenticationPrincipal UserDetails principal) {
        return applicationService.withdraw(id, callerId(principal))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No application of yours with id " + id));
    }

    /** Resolves the signed-in account's numeric id from the email a verified token carries. */
    private Long callerId(UserDetails principal) {
        UserProfileDTO profile = userService.findByEmail(principal.getUsername())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Account no longer exists"));
        return profile.id();
    }
}
