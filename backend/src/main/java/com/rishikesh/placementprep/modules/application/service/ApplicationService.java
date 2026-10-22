package com.rishikesh.placementprep.modules.application.service;

import java.time.Instant;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rishikesh.placementprep.modules.application.dto.ApplicationDTO;
import com.rishikesh.placementprep.modules.application.dto.ApplicationStatsDTO;
import com.rishikesh.placementprep.modules.application.dto.UpdateStatusRequest;
import com.rishikesh.placementprep.modules.application.model.Application;
import com.rishikesh.placementprep.modules.application.model.ApplicationStatus;
import com.rishikesh.placementprep.modules.application.repository.ApplicationRepository;
import com.rishikesh.placementprep.modules.application.service.ApplicationRefusedException.Reason;
import com.rishikesh.placementprep.modules.auth.dto.UserProfileDTO;
import com.rishikesh.placementprep.modules.drive.dto.DriveDTO;
import com.rishikesh.placementprep.modules.drive.service.DriveService;
import com.rishikesh.placementprep.modules.notification.model.NotificationKind;
import com.rishikesh.placementprep.modules.notification.service.NotificationService;

@Service
public class ApplicationService {

    private final ApplicationRepository applicationRepository;
    private final DriveService driveService;
    private final NotificationService notificationService;

    /**
     * DriveService is needed for the apply rules - the drive's deadline and eligibility.
     * An application is always to a drive, so this module depending on the drive module is
     * the natural direction; the drive module never depends back on this one.
     * NotificationService tells the student when the hiring side changes their status; it
     * depends on nothing but its own repository, so there is no loop between the two.
     */
    public ApplicationService(ApplicationRepository applicationRepository, DriveService driveService,
                              NotificationService notificationService) {
        this.applicationRepository = applicationRepository;
        this.driveService = driveService;
        this.notificationService = notificationService;
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
     * <p>Every rule lives here, so the Apply button and the assistant's applyToDrive tool
     * cannot disagree. In order: the drive must exist; an active (not withdrawn)
     * application blocks a second one; the deadline must not have passed; the student's
     * profile must be complete; and the stored marks must meet the drive's criteria - the
     * same eligibility query the "Eligible for me" tab uses, so the API cannot be used to
     * apply to a drive the student does not qualify for.
     *
     * @throws ApplicationRefusedException naming which rule refused, with a message fit to
     *         show the student
     */
    @Transactional
    public ApplicationDTO apply(UserProfileDTO student, Long driveId) {
        DriveDTO drive = driveService.findDriveById(driveId)
                .orElseThrow(() -> new ApplicationRefusedException(Reason.NO_SUCH_DRIVE,
                        "No drive with id " + driveId));

        Optional<Application> existing = applicationRepository.findByStudentIdAndDriveId(student.id(), driveId);
        if (existing.isPresent() && existing.get().getStatus() != ApplicationStatus.WITHDRAWN) {
            throw new ApplicationRefusedException(Reason.ALREADY_APPLIED,
                    "You have already applied to " + drive.companyName());
        }

        if (drive.applicationDeadline() == null || !drive.applicationDeadline().isAfter(Instant.now())) {
            throw new ApplicationRefusedException(Reason.CLOSED,
                    "Applications for " + drive.companyName() + " have closed");
        }

        if (!student.isComplete()) {
            throw new ApplicationRefusedException(Reason.PROFILE_INCOMPLETE,
                    "Complete your profile (CGPA, branch, 10th and 12th percentages) before applying");
        }

        if (!driveService.isEligible(driveId, student.cgpa(), student.tenthPercentage(),
                student.twelfthPercentage(), student.backlogs(), student.branch())) {
            throw new ApplicationRefusedException(Reason.NOT_ELIGIBLE,
                    "You do not meet the eligibility criteria for " + drive.companyName());
        }

        if (existing.isPresent()) {
            Application application = existing.get();
            application.setStatus(ApplicationStatus.APPLIED);
            application.setNote(null);
            application.setAppliedAt(Instant.now());
            return toDto(applicationRepository.save(application));
        }

        Application application = new Application();
        application.setStudentId(student.id());
        application.setDriveId(driveId);
        application.setStatus(ApplicationStatus.APPLIED);
        return toDto(applicationRepository.save(application));
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

    /** One application by id, whoever it belongs to - for the hiring side's checks. */
    @Transactional(readOnly = true)
    public Optional<ApplicationDTO> findById(Long id) {
        return applicationRepository.findById(id).map(this::toDto);
    }

    /** Counts across every drive, for the coordinator's and admin's dashboards. */
    @Transactional(readOnly = true)
    public ApplicationStatsDTO stats() {
        return stats(Optional.empty());
    }

    /**
     * Counts for the dashboards.
     *
     * @param onlyDrives a recruiter's own drive ids, to count only those; empty to count
     *                   every drive. The caller works the ids out, so this module never
     *                   needs to know what a recruiter is.
     */
    @Transactional(readOnly = true)
    public ApplicationStatsDTO stats(Optional<Collection<Long>> onlyDrives) {
        // 1. Start every status at zero, then fill in the ones that have applications
        Map<ApplicationStatus, Long> byStatus = new EnumMap<>(ApplicationStatus.class);
        for (ApplicationStatus status : ApplicationStatus.values()) {
            byStatus.put(status, 0L);
        }
        Map<Long, Long> awaitingReview = new LinkedHashMap<>();

        // A recruiter with no drives yet has nothing to count - and "IN ()" with an empty
        // list is not valid SQL, so this answers without asking the database at all.
        if (onlyDrives.isPresent() && onlyDrives.get().isEmpty()) {
            return new ApplicationStatsDTO(byStatus, awaitingReview);
        }

        List<Object[]> statusRows = onlyDrives.isPresent()
                ? applicationRepository.countByStatusForDrives(onlyDrives.get())
                : applicationRepository.countByStatus();
        for (Object[] row : statusRows) {
            byStatus.put((ApplicationStatus) row[0], (Long) row[1]);
        }

        // 2. Which drives have applications nobody has looked at yet
        List<Object[]> waitingRows = onlyDrives.isPresent()
                ? applicationRepository.countPerDriveForDrives(ApplicationStatus.APPLIED, onlyDrives.get())
                : applicationRepository.countPerDrive(ApplicationStatus.APPLIED);
        for (Object[] row : waitingRows) {
            awaitingReview.put((Long) row[0], (Long) row[1]);
        }

        return new ApplicationStatsDTO(byStatus, awaitingReview);
    }

    // ----------------------------------------------------------------- Update

    /**
     * The hiring side moving an application along. Empty Optional means no such id.
     *
     * <p>A withdrawn application is the student's decision and stays withdrawn: deciding
     * it would put the student back in a process they left. (Setting WITHDRAWN itself is
     * refused earlier, by UpdateStatusRequest's validation.)
     *
     * @throws ApplicationRefusedException when the student has withdrawn it
     */
    @Transactional
    public Optional<ApplicationDTO> updateStatus(Long id, UpdateStatusRequest request) {
        return applicationRepository.findById(id)
                .map(application -> {
                    if (application.getStatus() == ApplicationStatus.WITHDRAWN) {
                        throw new ApplicationRefusedException(Reason.WITHDRAWN_BY_STUDENT,
                                "The student has withdrawn this application");
                    }
                    ApplicationStatus previous = application.getStatus();
                    application.setStatus(request.status());
                    application.setNote(request.note());
                    ApplicationDTO saved = toDto(applicationRepository.save(application));

                    // Tell the student - only when the status really changed, so saving a
                    // new note on an unchanged status does not ping them again. Same
                    // transaction: if the change rolls back, so does the message.
                    if (previous != request.status()) {
                        notificationService.notify(application.getStudentId(), application.getDriveId(),
                                NotificationKind.STATUS_CHANGED,
                                statusMessage(request.status(), driveName(application.getDriveId()), request.note()));
                    }
                    return saved;
                });
    }

    /** The student-facing sentence for a new status. */
    private static String statusMessage(ApplicationStatus status, String drive, String note) {
        String message = switch (status) {
            case SHORTLISTED -> "Good news - you have been shortlisted for " + drive + ".";
            case SELECTED -> "Congratulations - you have been selected for " + drive + "!";
            case REJECTED -> "Your application to " + drive + " was not taken forward this time.";
            case APPLIED -> "Your application to " + drive + " is back under review.";
            case WITHDRAWN -> "Your application to " + drive + " was withdrawn.";
        };
        return note == null || note.isBlank()
                ? message
                : message + " There is a note from the recruiter in My applications.";
    }

    private String driveName(Long driveId) {
        return driveService.findDriveById(driveId)
                .map(drive -> drive.companyName() + " - " + drive.role())
                .orElse("a drive");
    }

    /**
     * A student pulling out.
     *
     * <p>Keyed by student id as well as application id, so a student can only ever
     * withdraw their own application even if they guess another's id.
     *
     * <p>Only an application still under review - APPLIED or SHORTLISTED - can be
     * withdrawn. A decided one (REJECTED or SELECTED) cannot: withdrawing a rejection and
     * then applying again would reopen it as APPLIED, quietly undoing the decision.
     *
     * @return empty when no such application belongs to this student
     * @throws ApplicationRefusedException when the application has already been decided
     *         or withdrawn
     */
    @Transactional
    public Optional<ApplicationDTO> withdraw(Long id, Long studentId) {
        return applicationRepository.findByIdAndStudentId(id, studentId)
                .map(application -> {
                    ApplicationStatus status = application.getStatus();
                    if (status != ApplicationStatus.APPLIED && status != ApplicationStatus.SHORTLISTED) {
                        throw new ApplicationRefusedException(Reason.CANNOT_WITHDRAW,
                                "Only an application still under review can be withdrawn - this one is "
                                        + status.name().toLowerCase());
                    }
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
