package com.rishikesh.placementprep.modules.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.rishikesh.placementprep.modules.application.dto.ApplicationDTO;
import com.rishikesh.placementprep.modules.application.dto.ApplicationStatsDTO;
import com.rishikesh.placementprep.modules.application.dto.UpdateStatusRequest;
import com.rishikesh.placementprep.modules.application.model.Application;
import com.rishikesh.placementprep.modules.application.model.ApplicationStatus;
import com.rishikesh.placementprep.modules.application.repository.ApplicationRepository;
import com.rishikesh.placementprep.modules.application.service.ApplicationRefusedException;
import com.rishikesh.placementprep.modules.application.service.ApplicationRefusedException.Reason;
import com.rishikesh.placementprep.modules.application.service.ApplicationService;
import com.rishikesh.placementprep.modules.auth.dto.UserProfileDTO;
import com.rishikesh.placementprep.modules.auth.model.Role;
import com.rishikesh.placementprep.modules.drive.dto.DriveDTO;
import com.rishikesh.placementprep.modules.drive.service.DriveService;
import com.rishikesh.placementprep.modules.notification.model.NotificationKind;
import com.rishikesh.placementprep.modules.notification.service.NotificationService;

/** Unit tests for ApplicationService's rules, with its collaborators mocked out. */
@ExtendWith(MockitoExtension.class)
class ApplicationServiceTest {

    private static final Instant FUTURE = Instant.parse("2099-01-01T00:00:00Z");
    private static final Instant PAST = Instant.parse("2020-01-01T00:00:00Z");

    @Mock
    private ApplicationRepository applicationRepository;

    @Mock
    private DriveService driveService;

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private ApplicationService applicationService;

    // ------------------------------------------------------------------ Apply

    @Test
    void applyingForTheFirstTimeCreatesAnAppliedApplication() {
        openEligibleDrive();
        when(applicationRepository.findByStudentIdAndDriveId(1L, 10L)).thenReturn(Optional.empty());
        when(applicationRepository.save(any(Application.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ApplicationDTO created = applicationService.apply(student(), 10L);

        assertThat(created.studentId()).isEqualTo(1L);
        assertThat(created.driveId()).isEqualTo(10L);
        assertThat(created.status()).isEqualTo(ApplicationStatus.APPLIED);
    }

    @Test
    void applyingToADriveThatDoesNotExistIsRefused() {
        when(driveService.findDriveById(99L)).thenReturn(Optional.empty());

        assertRefused(() -> applicationService.apply(student(), 99L), Reason.NO_SUCH_DRIVE);
    }

    @Test
    void applyingAgainWhileAnApplicationIsActiveIsRefusedWithoutSaving() {
        when(driveService.findDriveById(10L)).thenReturn(Optional.of(drive(FUTURE)));
        when(applicationRepository.findByStudentIdAndDriveId(1L, 10L))
                .thenReturn(Optional.of(application(ApplicationStatus.SHORTLISTED, "Strong profile")));

        assertRefused(() -> applicationService.apply(student(), 10L), Reason.ALREADY_APPLIED);
        verify(applicationRepository, never()).save(any());
    }

    @Test
    void applyingAfterTheDeadlineIsRefused() {
        when(driveService.findDriveById(10L)).thenReturn(Optional.of(drive(PAST)));
        when(applicationRepository.findByStudentIdAndDriveId(1L, 10L)).thenReturn(Optional.empty());

        assertRefused(() -> applicationService.apply(student(), 10L), Reason.CLOSED);
        verify(applicationRepository, never()).save(any());
    }

    @Test
    void applyingWithAnIncompleteProfileIsRefused() {
        when(driveService.findDriveById(10L)).thenReturn(Optional.of(drive(FUTURE)));
        when(applicationRepository.findByStudentIdAndDriveId(1L, 10L)).thenReturn(Optional.empty());
        UserProfileDTO noMarks = new UserProfileDTO(1L, "student@cse.nits.ac.in", Role.STUDENT,
                null, null, null, null, 0, null, null, null, null, null);

        assertRefused(() -> applicationService.apply(noMarks, 10L), Reason.PROFILE_INCOMPLETE);
    }

    /** The API cannot be used to apply to a drive the student does not qualify for. */
    @Test
    void applyingToADriveTheStudentIsNotEligibleForIsRefused() {
        when(driveService.findDriveById(10L)).thenReturn(Optional.of(drive(FUTURE)));
        when(applicationRepository.findByStudentIdAndDriveId(1L, 10L)).thenReturn(Optional.empty());
        when(driveService.isEligible(eq(10L), any(), any(), any(), eq(0), eq("CSE"))).thenReturn(false);

        assertRefused(() -> applicationService.apply(student(), 10L), Reason.NOT_ELIGIBLE);
        verify(applicationRepository, never()).save(any());
    }

    /** A withdrawn row is reopened rather than duplicated, with the stale note cleared. */
    @Test
    void applyingAfterWithdrawingReopensTheSameRowAndClearsTheOldNote() {
        openEligibleDrive();
        Application withdrawn = application(ApplicationStatus.WITHDRAWN, "Withdrew before the test");
        withdrawn.setId(5L);
        when(applicationRepository.findByStudentIdAndDriveId(1L, 10L)).thenReturn(Optional.of(withdrawn));
        when(applicationRepository.save(withdrawn)).thenReturn(withdrawn);

        ApplicationDTO reopened = applicationService.apply(student(), 10L);

        assertThat(reopened.id()).isEqualTo(5L);
        assertThat(reopened.status()).isEqualTo(ApplicationStatus.APPLIED);
        assertThat(reopened.note()).isNull();
    }

    // --------------------------------------------------------------- Withdraw

    /** Keyed by student as well as id, so another student's application is out of reach. */
    @Test
    void withdrawingSomeoneElsesApplicationChangesNothing() {
        when(applicationRepository.findByIdAndStudentId(5L, 2L)).thenReturn(Optional.empty());

        assertThat(applicationService.withdraw(5L, 2L)).isEmpty();
        verify(applicationRepository, never()).save(any());
    }

    @Test
    void withdrawingAShortlistedApplicationIsAllowed() {
        Application shortlisted = application(ApplicationStatus.SHORTLISTED, null);
        when(applicationRepository.findByIdAndStudentId(5L, 1L)).thenReturn(Optional.of(shortlisted));
        when(applicationRepository.save(shortlisted)).thenReturn(shortlisted);

        assertThat(applicationService.withdraw(5L, 1L)).get()
                .extracting(ApplicationDTO::status).isEqualTo(ApplicationStatus.WITHDRAWN);
    }

    /**
     * Withdrawing a rejection and applying again would reopen it as APPLIED, quietly
     * undoing the decision - so a decided application cannot be withdrawn.
     */
    @Test
    void withdrawingARejectedApplicationIsRefused() {
        when(applicationRepository.findByIdAndStudentId(5L, 1L))
                .thenReturn(Optional.of(application(ApplicationStatus.REJECTED, null)));

        assertRefused(() -> applicationService.withdraw(5L, 1L), Reason.CANNOT_WITHDRAW);
        verify(applicationRepository, never()).save(any());
    }

    // ----------------------------------------------------------------- Status

    @Test
    void decidingAWithdrawnApplicationIsRefused() {
        when(applicationRepository.findById(5L))
                .thenReturn(Optional.of(application(ApplicationStatus.WITHDRAWN, null)));

        assertRefused(() -> applicationService.updateStatus(5L,
                new UpdateStatusRequest(ApplicationStatus.SHORTLISTED, null)), Reason.WITHDRAWN_BY_STUDENT);
        verify(applicationRepository, never()).save(any());
    }

    @Test
    void aStatusChangeNotifiesTheStudent() {
        Application applied = application(ApplicationStatus.APPLIED, null);
        when(applicationRepository.findById(5L)).thenReturn(Optional.of(applied));
        when(applicationRepository.save(applied)).thenReturn(applied);
        when(driveService.findDriveById(10L)).thenReturn(Optional.of(drive(FUTURE)));

        applicationService.updateStatus(5L, new UpdateStatusRequest(ApplicationStatus.SHORTLISTED, null));

        verify(notificationService).notify(eq(1L), eq(10L), eq(NotificationKind.STATUS_CHANGED),
                contains("shortlisted for Zoho - SDE"));
    }

    /** Saving a new note on an unchanged status does not ping the student again. */
    @Test
    void anUnchangedStatusSendsNoNotification() {
        Application shortlisted = application(ApplicationStatus.SHORTLISTED, null);
        when(applicationRepository.findById(5L)).thenReturn(Optional.of(shortlisted));
        when(applicationRepository.save(shortlisted)).thenReturn(shortlisted);

        applicationService.updateStatus(5L, new UpdateStatusRequest(ApplicationStatus.SHORTLISTED, "Round 2 on Friday"));

        verify(notificationService, never()).notify(any(), any(), any(), anyString());
    }

    // ------------------------------------------------------------------ Stats

    /** A status with no applications is reported as zero, not left missing. */
    @Test
    void statsFillInZeroForEveryStatusWithNoApplications() {
        when(applicationRepository.countByStatus())
                .thenReturn(List.<Object[]>of(new Object[] {ApplicationStatus.APPLIED, 3L}));
        when(applicationRepository.countPerDrive(ApplicationStatus.APPLIED))
                .thenReturn(List.<Object[]>of(new Object[] {10L, 3L}));

        ApplicationStatsDTO stats = applicationService.stats();

        assertThat(stats.byStatus()).containsEntry(ApplicationStatus.APPLIED, 3L)
                .containsEntry(ApplicationStatus.SELECTED, 0L)
                .hasSize(ApplicationStatus.values().length);
        assertThat(stats.awaitingReviewByDrive()).containsEntry(10L, 3L);
    }

    // ---------------------------------------------------------------- Helpers

    private void openEligibleDrive() {
        when(driveService.findDriveById(10L)).thenReturn(Optional.of(drive(FUTURE)));
        when(driveService.isEligible(eq(10L), any(), any(), any(), eq(0), eq("CSE"))).thenReturn(true);
    }

    private static void assertRefused(Runnable action, Reason reason) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ApplicationRefusedException.class)
                .extracting(thrown -> ((ApplicationRefusedException) thrown).getReason())
                .isEqualTo(reason);
    }

    private static UserProfileDTO student() {
        return new UserProfileDTO(1L, "student@cse.nits.ac.in", Role.STUDENT, new BigDecimal("8.5"), "CSE",
                new BigDecimal("90"), new BigDecimal("88"), 0, null, null, null, null, null);
    }

    private static DriveDTO drive(Instant deadline) {
        return new DriveDTO(10L, "Zoho", "SDE", new BigDecimal("900000"), 1, new BigDecimal("7.5"),
                null, null, false, null, List.of("CSE"), deadline, null);
    }

    private static Application application(ApplicationStatus status, String note) {
        Application application = new Application();
        application.setStudentId(1L);
        application.setDriveId(10L);
        application.setStatus(status);
        application.setNote(note);
        application.setAppliedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return application;
    }
}
