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

    /**
     * @param createdBy the posting account's id. Set here and never by applyRequest, so
     *                  an update can never hand a drive to somebody else.
     */
    @Transactional
    public DriveDTO createDrive(DriveRequest request, Long createdBy) {
        // 1. Map the incoming DTO onto a new Database Entity
        Drive drive = new Drive();
        applyRequest(drive, request);
        drive.setCreatedBy(createdBy);

        // 2. Save the entity to PostgreSQL
        Drive savedDrive = driveRepository.save(drive);

        // 3. Map the saved entity (which now has an ID) back to an outgoing DTO
        return toDto(savedDrive);
    }

    // ------------------------------------------------------------------- Read

    /** The whole board. For callers acting as a student, who see every drive. */
    @Transactional(readOnly = true)
    public List<DriveDTO> findAllDrives() {
        return findAllDrives(Optional.empty());
    }

    /**
     * The board as one account may see it.
     *
     * @param scope a recruiter's own id, to see only the drives they posted; empty for
     *              every other role, to see them all (see UserProfileDTO.driveScope)
     */
    @Transactional(readOnly = true)
    public List<DriveDTO> findAllDrives(Optional<Long> scope) {
        List<Drive> drives = scope.isPresent()
                ? driveRepository.findByCreatedByOrderByCreatedAtDesc(scope.get())
                : driveRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
        return drives.stream().map(this::toDto).toList();
    }

    /**
     * Returns an empty Optional when no drive has that id. The service deliberately does
     * not decide what that means over HTTP - translating "absent" into a 404 is the
     * controller's job, which keeps this class free of any web concepts.
     */
    @Transactional(readOnly = true)
    public Optional<DriveDTO> findDriveById(Long id) {
        return findDriveById(id, Optional.empty());
    }

    /**
     * One drive, if this scope may see it. Another recruiter's drive comes back empty,
     * exactly like a drive that does not exist, so the caller cannot tell the two apart.
     */
    @Transactional(readOnly = true)
    public Optional<DriveDTO> findDriveById(Long id, Optional<Long> scope) {
        return findVisible(id, scope).map(this::toDto);
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

    /**
     * Whether one drive is open and these marks meet its criteria. Answered by the same
     * query as the eligible list, so "eligible" means exactly one thing everywhere - the
     * apply rule, the "Eligible for me" tab and the deadline reminders all agree.
     */
    @Transactional(readOnly = true)
    public boolean isEligible(Long driveId, BigDecimal cgpa, BigDecimal tenth,
                              BigDecimal twelfth, int backlogs, String branch) {
        return driveRepository.findEligible(cgpa, tenth, twelfth, backlogs, branch)
                .stream()
                .anyMatch(drive -> drive.getId().equals(driveId));
    }

    // ----------------------------------------------------------------- Update

    /**
     * Replaces every editable field of an existing drive. Empty Optional means there was
     * no drive with that id that this scope may touch, so nothing was changed.
     */
    @Transactional
    public Optional<DriveDTO> updateDrive(Long id, DriveRequest request, Optional<Long> scope) {
        return findVisible(id, scope)
                .map(drive -> {
                    applyRequest(drive, request);
                    return toDto(driveRepository.save(drive));
                });
    }

    // ----------------------------------------------------------------- Delete

    /**
     * Returns false when there was no drive with that id that this scope may touch.
     * Spring Data's delete is silent about a missing row, so we look first rather than
     * report success for a delete that did nothing.
     */
    @Transactional
    public boolean deleteDrive(Long id, Optional<Long> scope) {
        Optional<Drive> drive = findVisible(id, scope);
        drive.ifPresent(driveRepository::delete);
        return drive.isPresent();
    }

    // -------------------------------------------------------- Mapping helpers

    /**
     * The one place the recruiter rule lives: with a scope, a drive is visible only when
     * that account posted it. Every scoped read and write goes through here, so the rule
     * cannot be applied to one endpoint and forgotten on another.
     */
    private Optional<Drive> findVisible(Long id, Optional<Long> scope) {
        return driveRepository.findById(id)
                .filter(drive -> scope.isEmpty() || scope.get().equals(drive.getCreatedBy()));
    }

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
