package com.rishikesh.placementprep.modules.drive.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.rishikesh.placementprep.modules.auth.dto.UserProfileDTO;
import com.rishikesh.placementprep.modules.auth.service.UserService;
import com.rishikesh.placementprep.modules.drive.dto.DriveDTO;
import com.rishikesh.placementprep.modules.drive.dto.DriveRequest;
import com.rishikesh.placementprep.modules.drive.service.DriveService;
import com.rishikesh.placementprep.modules.knowledgebase.service.JobDescriptionService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/drives")
public class DriveController {

    private final DriveService driveService;
    private final UserService userService;
    private final JobDescriptionService jobDescriptionService;

    public DriveController(DriveService driveService, UserService userService,
                           JobDescriptionService jobDescriptionService) {
        this.driveService = driveService;
        this.userService = userService;
        this.jobDescriptionService = jobDescriptionService;
    }

    /** A coordinator, recruiter or the admin posts a new drive, recorded as theirs. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DriveDTO createDrive(@AuthenticationPrincipal UserDetails principal,
                                @Valid @RequestBody DriveRequest request) {
        return driveService.createDrive(request, caller(principal).id());
    }

    /** Every drive on the board - or, for a recruiter, every drive they posted. */
    @GetMapping
    public List<DriveDTO> findAllDrives(@AuthenticationPrincipal UserDetails principal) {
        return driveService.findAllDrives(caller(principal).driveScope());
    }

    /**
     * Drives the signed-in student is eligible for.
     *
     * <p>This used to take the five academic values as query parameters. They now come
     * from the student's stored profile, which is both less to get wrong at the call site
     * and impossible to lie about: a student cannot claim a higher CGPA than the one on
     * their account in order to see drives they do not qualify for.
     *
     * <p>DriveService is unchanged and still takes the values as arguments, so it stays
     * independent of the auth module. Reading them off the caller is a controller concern.
     */
    @GetMapping("/eligible")
    public List<DriveDTO> findEligibleDrives(@AuthenticationPrincipal UserDetails principal) {
        UserProfileDTO profile = caller(principal);

        if (!profile.isComplete()) {
            // 409 rather than 400: the request itself is perfectly well formed, it just
            // cannot be answered while the account is in this state. The message names
            // exactly what is missing so the frontend can send the student to the form.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Complete your profile (CGPA, branch, 10th and 12th percentages) "
                    + "before checking eligibility");
        }

        return driveService.findEligibleDrives(
                profile.cgpa(),
                profile.tenthPercentage(),
                profile.twelfthPercentage(),
                profile.backlogs(),
                profile.branch());
    }

    /**
     * One drive by id. Another recruiter's drive is a 404 rather than a 403, so a
     * recruiter cannot even learn which ids other companies' drives have.
     */
    @GetMapping("/{id}")
    public DriveDTO findDriveById(@AuthenticationPrincipal UserDetails principal, @PathVariable Long id) {
        return driveService.findDriveById(id, caller(principal).driveScope())
                .orElseThrow(() -> notFound(id));
    }

    /** Replaces every editable field of an existing drive. */
    @PutMapping("/{id}")
    public DriveDTO updateDrive(@AuthenticationPrincipal UserDetails principal,
                                @PathVariable Long id,
                                @Valid @RequestBody DriveRequest request) {
        return driveService.updateDrive(id, request, caller(principal).driveScope())
                .orElseThrow(() -> notFound(id));
    }

    /** Removes a drive. Returns 204 with an empty body when it worked. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDrive(@AuthenticationPrincipal UserDetails principal, @PathVariable Long id) {
        if (!driveService.deleteDrive(id, caller(principal).driveScope())) {
            throw notFound(id);
        }
        // Its job-description passages live in the vector store, which has no foreign key
        // back to drives, so they are removed here - otherwise searches would keep
        // returning a drive that no longer exists.
        jobDescriptionService.forgetDrive(id);
    }

    /**
     * The signed-in account. A missing account is a 404 rather than "no scope", so a
     * lookup failure can never widen a recruiter's view to the whole board.
     */
    private UserProfileDTO caller(UserDetails principal) {
        return userService.findByEmail(principal.getUsername())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Account no longer exists"));
    }

    /**
     * Turns "no such drive" into a 404. ResponseStatusException is built into Spring, so
     * this needs no custom exception class or error handler.
     */
    private ResponseStatusException notFound(Long id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "No drive with id " + id);
    }
}
