package com.rishikesh.placementprep.modules.application.service;

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
     * @return empty when they have already applied. The UNIQUE constraint on
     *         (student_id, drive_id) is the real guard; this check only turns the common
     *         case into a clean 409 instead of a constraint-violation 500.
     */
    @Transactional
    public Optional<ApplicationDTO> apply(Long studentId, Long driveId) {
        if (applicationRepository.findByStudentIdAndDriveId(studentId, driveId).isPresent()) {
            return Optional.empty();
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

    private ApplicationDTO toDto(Application application) {
        return new ApplicationDTO(
                application.getId(),
                application.getStudentId(),
                application.getDriveId(),
                application.getStatus(),
                application.getNote(),
                application.getAppliedAt(),
                application.getUpdatedAt());
    }
}
