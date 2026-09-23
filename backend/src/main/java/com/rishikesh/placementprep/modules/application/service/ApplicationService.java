package com.rishikesh.placementprep.modules.application.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rishikesh.placementprep.modules.application.dto.ApplicationDTO;
import com.rishikesh.placementprep.modules.application.dto.UpdateStatusRequest;
import com.rishikesh.placementprep.modules.application.model.Application;
import com.rishikesh.placementprep.modules.application.model.ApplicationStatus;
import com.rishikesh.placementprep.modules.application.repository.ApplicationRepository;

@Service
public class ApplicationService {

    private final ApplicationRepository applicationRepository;

    public ApplicationService(ApplicationRepository applicationRepository) {
        this.applicationRepository = applicationRepository;
    }

    // ----------------------------------------------------------------- Create

    /**
     * Records a student applying to a drive.
     *
     * <p>A withdrawn application re-opens the same row rather than failing. The UNIQUE
     * constraint on (student_id, drive_id) would refuse a second INSERT for this pair
     * regardless of status, so "apply again after withdrawing" has to mean "flip this row
     * back to APPLIED" - there is no other row it could become. The note is cleared and
     * appliedAt is reset to now, because both described the withdrawn cycle: a stale note
     * about why the cell was reviewing them last time, or a date that would otherwise make
     * a fresh application look months old, would mislead more than help.
     *
     * @return empty when there is already an active application for this drive - APPLIED,
     *         SHORTLISTED, REJECTED or SELECTED. Only WITHDRAWN is re-openable.
     */
    @Transactional
    public Optional<ApplicationDTO> apply(Long studentId, Long driveId) {
        Optional<Application> existing = applicationRepository.findByStudentIdAndDriveId(studentId, driveId);

        if (existing.isPresent()) {
            Application application = existing.get();
            if (application.getStatus() != ApplicationStatus.WITHDRAWN) {
                return Optional.empty();
            }
            application.setStatus(ApplicationStatus.APPLIED);
            application.setNote(null);
            application.setAppliedAt(Instant.now());
            return Optional.of(toDto(applicationRepository.save(application)));
        }

        Application application = new Application();
        application.setStudentId(studentId);
        application.setDriveId(driveId);
        application.setStatus(ApplicationStatus.APPLIED);

        return Optional.of(toDto(applicationRepository.save(application)));
    }

    // ------------------------------------------------------------------- Read

    @Transactional(readOnly = true)
    public List<ApplicationDTO> findMine(Long studentId) {
        return applicationRepository.findByStudentIdOrderByAppliedAtDesc(studentId)
                .stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<ApplicationDTO> findForDrive(Long driveId) {
        return applicationRepository.findByDriveIdOrderByAppliedAtDesc(driveId)
                .stream().map(this::toDto).toList();
    }

    // ----------------------------------------------------------------- Update

    /** The placement cell moving an application along. Empty Optional means no such id. */
    @Transactional
    public Optional<ApplicationDTO> updateStatus(Long id, UpdateStatusRequest request) {
        return applicationRepository.findById(id)
                .map(application -> {
                    application.setStatus(request.status());
                    application.setNote(request.note());
                    return toDto(applicationRepository.save(application));
                });
    }

    /**
     * A student pulling out.
     *
     * <p>Keyed by student id as well as application id, so a student can only ever
     * withdraw their own application even if they guess another's id.
     *
     * @return empty when no such application belongs to this student
     */
    @Transactional
    public Optional<ApplicationDTO> withdraw(Long id, Long studentId) {
        return applicationRepository.findByIdAndStudentId(id, studentId)
                .map(application -> {
                    application.setStatus(ApplicationStatus.WITHDRAWN);
                    return toDto(applicationRepository.save(application));
                });
    }

    // -------------------------------------------------------- Mapping helpers

    /**
     * studentEmail is left null here on purpose. Who a student id belongs to lives in the
     * auth module, and reaching into it from this service would couple the two for the
     * benefit of exactly one endpoint. The controller attaches it where it is wanted,
     * which is the same seam DriveController already uses to read a caller's profile.
     */
    private ApplicationDTO toDto(Application application) {
        return new ApplicationDTO(
                application.getId(),
                application.getStudentId(),
                null,
                application.getDriveId(),
                application.getStatus(),
                application.getNote(),
                application.getAppliedAt(),
                application.getUpdatedAt());
    }
}
