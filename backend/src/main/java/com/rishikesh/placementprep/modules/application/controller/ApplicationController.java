package com.rishikesh.placementprep.modules.application.controller;

import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import com.rishikesh.placementprep.modules.application.dto.ApplicationStatsDTO;
import com.rishikesh.placementprep.modules.application.dto.ApplyRequest;
import com.rishikesh.placementprep.modules.application.dto.UpdateStatusRequest;
import com.rishikesh.placementprep.modules.application.service.ApplicationRefusedException;
import com.rishikesh.placementprep.modules.application.service.ApplicationService;
import com.rishikesh.placementprep.modules.auth.dto.UserProfileDTO;
import com.rishikesh.placementprep.modules.auth.service.UserService;
import com.rishikesh.placementprep.modules.drive.dto.DriveDTO;
import com.rishikesh.placementprep.modules.drive.service.DriveService;

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
    private final DriveService driveService;

    public ApplicationController(ApplicationService applicationService, UserService userService,
                                 DriveService driveService) {
        this.applicationService = applicationService;
        this.userService = userService;
        this.driveService = driveService;
    }

    /**
     * A student applying. Every rule - drive exists, not already applied, still open,
     * profile complete, eligible - is checked in ApplicationService, the same place the
     * assistant's applyToDrive tool goes through.
     */
    @PostMapping
    public ResponseEntity<ApplicationDTO> apply(@AuthenticationPrincipal UserDetails principal,
                                                @Valid @RequestBody ApplyRequest request) {
        ApplicationDTO created;
        try {
            created = applicationService.apply(caller(principal), request.driveId());
        } catch (ApplicationRefusedException refused) {
            throw asHttp(refused);
        }

        return ResponseEntity.created(URI.create("/api/applications/" + created.id())).body(created);
    }

    @GetMapping("/me")
    public List<ApplicationDTO> mine(@AuthenticationPrincipal UserDetails principal) {
        return applicationService.findMine(callerId(principal));
    }

    /**
     * Counts for the dashboards: across every drive, or for a recruiter only across the
     * drives they posted. Restricted to the hiring side by SecurityConfig.
     */
    @GetMapping("/stats")
    public ApplicationStatsDTO stats(@AuthenticationPrincipal UserDetails principal) {
        Optional<Long> scope = caller(principal).driveScope();
        Optional<Collection<Long>> onlyDrives = scope.map(owner ->
                driveService.findAllDrives(scope).stream().map(DriveDTO::id).toList());
        return applicationService.stats(onlyDrives);
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
    public List<ApplicationDTO> forDrive(@AuthenticationPrincipal UserDetails principal,
                                         @PathVariable Long driveId) {
        // A recruiter may only read applicants to their own drives. Another company's
        // drive is a 404, the same answer as a drive that does not exist.
        requireVisibleDrive(driveId, caller(principal).driveScope());

        List<ApplicationDTO> applications = applicationService.findForDrive(driveId);

        Map<Long, String> emails = userService.emailsByIds(
                applications.stream().map(ApplicationDTO::studentId).collect(Collectors.toSet()));

        return applications.stream()
                .map(application -> application.withStudentEmail(emails.get(application.studentId())))
                .toList();
    }

    /**
     * The hiring side moving an application along. Restricted by SecurityConfig; a
     * recruiter may only decide applications to their own drives, and any other is a 404.
     */
    @PatchMapping("/{id}/status")
    public ApplicationDTO updateStatus(@AuthenticationPrincipal UserDetails principal,
                                       @PathVariable Long id,
                                       @Valid @RequestBody UpdateStatusRequest request) {
        Optional<Long> scope = caller(principal).driveScope();
        ResponseStatusException notFound =
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No application with id " + id);

        ApplicationDTO existing = applicationService.findById(id)
                .filter(application -> driveService.findDriveById(application.driveId(), scope).isPresent())
                .orElseThrow(() -> notFound);

        try {
            return applicationService.updateStatus(existing.id(), request).orElseThrow(() -> notFound);
        } catch (ApplicationRefusedException refused) {
            throw asHttp(refused);
        }
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
        try {
            return applicationService.withdraw(id, callerId(principal))
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "No application of yours with id " + id));
        } catch (ApplicationRefusedException refused) {
            throw asHttp(refused);
        }
    }

    /**
     * Turns a refused application rule into its HTTP answer: a drive that does not exist
     * is 404, and every other refusal is 409 - the request was well formed, but the
     * current state (closed, already applied, not eligible...) does not allow it. The
     * service's message goes back to the caller as the problem detail.
     */
    private ResponseStatusException asHttp(ApplicationRefusedException refused) {
        HttpStatus status = refused.getReason() == ApplicationRefusedException.Reason.NO_SUCH_DRIVE
                ? HttpStatus.NOT_FOUND
                : HttpStatus.CONFLICT;
        return new ResponseStatusException(status, refused.getMessage());
    }

    /** Resolves the signed-in account's numeric id from the email a verified token carries. */
    private Long callerId(UserDetails principal) {
        return caller(principal).id();
    }

    /**
     * The signed-in account. A missing account is a 404 rather than "no scope", so a
     * lookup failure can never widen a recruiter's view to every drive.
     */
    private UserProfileDTO caller(UserDetails principal) {
        return userService.findByEmail(principal.getUsername())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Account no longer exists"));
    }

    private void requireVisibleDrive(Long driveId, Optional<Long> scope) {
        if (driveService.findDriveById(driveId, scope).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No drive with id " + driveId);
        }
    }
}
