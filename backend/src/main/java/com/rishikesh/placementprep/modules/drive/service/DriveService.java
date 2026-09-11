package com.rishikesh.placementprep.modules.drive.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rishikesh.placementprep.modules.drive.dto.DriveRequest;
import com.rishikesh.placementprep.modules.drive.dto.DriveDTO;
import com.rishikesh.placementprep.modules.drive.model.Drive;
import com.rishikesh.placementprep.modules.drive.repository.DriveRepository;

@Service
public class DriveService {

    private final DriveRepository driveRepository;

    public DriveService(DriveRepository driveRepository) {
        this.driveRepository = driveRepository;
    }

    // ----------------------------------------------------------------- Create

    @Transactional
    public DriveDTO createDrive(DriveRequest request) {
        // 1. Map the incoming DTO onto a new Database Entity
        Drive drive = new Drive();
        applyRequest(drive, request);

        // 2. Save the entity to PostgreSQL
        Drive savedDrive = driveRepository.save(drive);

        // 3. Map the saved entity (which now has an ID) back to an outgoing DTO
        return toDto(savedDrive);
    }

    // ------------------------------------------------------------------- Read

    @Transactional(readOnly = true)
    public List<DriveDTO> findAllDrives() {
        return driveRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"))
                .stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * Returns an empty Optional when no drive has that id. The service deliberately does
     * not decide what that means over HTTP - translating "absent" into a 404 is the
     * controller's job, which keeps this class free of any web concepts.
     */
    @Transactional(readOnly = true)
    public Optional<DriveDTO> findDriveById(Long id) {
        return driveRepository.findById(id).map(this::toDto);
    }

    /**
     * Drives the given student can apply for. Read-only: nothing here writes, and saying
     * so lets Hibernate skip tracking the returned entities for changes.
     */
    @Transactional(readOnly = true)
    public List<DriveDTO> findEligibleDrives(BigDecimal cgpa, BigDecimal tenth,
                                             BigDecimal twelfth, int backlogs,
                                             String branch) {
        return driveRepository.findEligible(cgpa, tenth, twelfth, backlogs, branch)
                .stream()
                .map(this::toDto)
                .toList();
    }

    // ----------------------------------------------------------------- Update

    /**
     * Replaces every editable field of an existing drive. Empty Optional means there was
     * no drive with that id, so nothing was changed.
     */
    @Transactional
    public Optional<DriveDTO> updateDrive(Long id, DriveRequest request) {
        return driveRepository.findById(id)
                .map(drive -> {
                    applyRequest(drive, request);
                    return toDto(driveRepository.save(drive));
                });
    }

    // ----------------------------------------------------------------- Delete

    /**
     * Returns false when there was no drive with that id. Spring Data's deleteById is
     * silent about a missing row, so we check first rather than report success for a
     * delete that did nothing.
     */
    @Transactional
    public boolean deleteDrive(Long id) {
        if (!driveRepository.existsById(id)) {
            return false;
        }
        driveRepository.deleteById(id);
        return true;
    }

    // -------------------------------------------------------- Mapping helpers

    /**
     * Copies the editable fields of a request onto an entity. Shared by create and
     * update so the two can never drift apart when a field is added.
     */
    private void applyRequest(Drive drive, DriveRequest request) {
        drive.setCompanyName(request.companyName());
        drive.setRole(request.role());
        drive.setCtc(request.ctc());
        drive.setTier(request.tier());
        drive.setCgpaCutoff(request.cgpaCutoff());
        drive.setTenthCutoff(request.tenthCutoff());
        drive.setTwelfthCutoff(request.twelfthCutoff());
        drive.setBacklogsAllowed(request.backlogsAllowed());
        drive.setMaxBacklogs(request.maxBacklogs());
        drive.setEligibleBranches(request.eligibleBranches());
        drive.setApplicationDeadline(request.applicationDeadline());
        drive.setDescription(request.description());
    }

    /**
     * The single place an entity becomes a DTO. Every endpoint goes through here, so a
     * new field only has to be added once.
     */
    private DriveDTO toDto(Drive drive) {
        return new DriveDTO(
            drive.getId(),
            drive.getCompanyName(),
            drive.getRole(),
            drive.getCtc(),
            drive.getTier(),
            drive.getCgpaCutoff(),
            drive.getTenthCutoff(),
            drive.getTwelfthCutoff(),
            drive.getBacklogsAllowed(),
            drive.getMaxBacklogs(),
            drive.getEligibleBranches(),
            drive.getApplicationDeadline(),
            drive.getDescription()
        );
    }
}
