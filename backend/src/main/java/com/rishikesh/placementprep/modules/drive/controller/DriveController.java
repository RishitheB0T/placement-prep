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

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/drives")
public class DriveController {

    private final DriveService driveService;
    private final UserService userService;

    public DriveController(DriveService driveService, UserService userService) {
        this.driveService = driveService;
        this.userService = userService;
    }

    /** Admin posts a new placement drive. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DriveDTO createDrive(@Valid @RequestBody DriveRequest request) {
        return driveService.createDrive(request);
    }

    /** Every drive on the board. */
    @GetMapping
    public List<DriveDTO> findAllDrives() {
        return driveService.findAllDrives();
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
        UserProfileDTO profile = userService.findByEmail(principal.getUsername())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Account no longer exists"));

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

    /** One drive by id. */
    @GetMapping("/{id}")
    public DriveDTO findDriveById(@PathVariable Long id) {
        return driveService.findDriveById(id)
                .orElseThrow(() -> notFound(id));
    }

    /** Replaces every editable field of an existing drive. */
    @PutMapping("/{id}")
    public DriveDTO updateDrive(@PathVariable Long id,
                                @Valid @RequestBody DriveRequest request) {
        return driveService.updateDrive(id, request)
                .orElseThrow(() -> notFound(id));
    }

    /** Removes a drive. Returns 204 with an empty body when it worked. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDrive(@PathVariable Long id) {
        if (!driveService.deleteDrive(id)) {
            throw notFound(id);
        }
    }

    /**
     * Turns "no such drive" into a 404. ResponseStatusException is built into Spring, so
     * this needs no custom exception class or error handler.
     */
    private ResponseStatusException notFound(Long id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "No drive with id " + id);
    }
}
