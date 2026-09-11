package com.rishikesh.placementprep.modules.drive.controller;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.rishikesh.placementprep.modules.drive.dto.DriveRequest;
import com.rishikesh.placementprep.modules.drive.dto.DriveDTO;
import com.rishikesh.placementprep.modules.drive.service.DriveService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

@RestController
@RequestMapping("/api/drives")
public class DriveController {

    private final DriveService driveService;

    public DriveController(DriveService driveService) {
        this.driveService = driveService;
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
     * Drives the given student is eligible for.
     *
     * <p>Declared before the "/{id}" mapping only for readability - Spring always prefers
     * a literal path segment over a variable one, so "/eligible" wins regardless of order.
     */
    @GetMapping("/eligible")
    public List<DriveDTO> findEligibleDrives(
            @RequestParam @DecimalMin("0.0") @DecimalMax("10.0") BigDecimal cgpa,
            @RequestParam @DecimalMin("0.0") @DecimalMax("100.0") BigDecimal tenth,
            @RequestParam @DecimalMin("0.0") @DecimalMax("100.0") BigDecimal twelfth,
            @RequestParam @PositiveOrZero int backlogs,
            @RequestParam @NotBlank String branch) {
        return driveService.findEligibleDrives(cgpa, tenth, twelfth, backlogs, branch);
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
