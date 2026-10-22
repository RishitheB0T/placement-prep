package com.rishikesh.placementprep.modules.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.rishikesh.placementprep.modules.application.dto.ApplicationDTO;
import com.rishikesh.placementprep.modules.application.model.ApplicationStatus;
import com.rishikesh.placementprep.modules.application.service.ApplicationService;
import com.rishikesh.placementprep.modules.auth.dto.UserProfileDTO;
import com.rishikesh.placementprep.modules.auth.model.Role;
import com.rishikesh.placementprep.modules.auth.service.UserService;
import com.rishikesh.placementprep.modules.drive.dto.DriveDTO;
import com.rishikesh.placementprep.modules.drive.service.DriveService;
import com.rishikesh.placementprep.modules.notification.model.NotificationKind;
import com.rishikesh.placementprep.modules.notification.service.DeadlineReminderJob;
import com.rishikesh.placementprep.modules.notification.service.NotificationService;

/**
 * Unit tests for which notification the reminder job picks. Every collaborator is a mock
 * and "now" is fixed, so each test is about the job's own rules and nothing else.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeadlineReminderJobTest {

    private static final Instant NOW = Instant.parse("2026-10-01T06:00:00Z");

    @Mock
    private UserService userService;

    @Mock
    private DriveService driveService;

    @Mock
    private ApplicationService applicationService;

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private DeadlineReminderJob job;

    @BeforeEach
    void oneStudentWithACompleteProfile() {
        when(userService.findAll(Optional.of(Role.STUDENT))).thenReturn(List.of(student(1L, true)));
        when(userService.findAll(Optional.of(Role.TNP_COORDINATOR))).thenReturn(List.of());
        when(applicationService.findMine(1L)).thenReturn(List.of());
        when(notificationService.notifyOnce(anyLong(), anyLong(), any(), anyString())).thenReturn(true);
    }

    @Test
    void aDriveWithWeeksLeftIsAnnouncedAsNew() {
        eligibleDrives(drive(10L, NOW.plus(Duration.ofDays(20))));

        assertThat(job.sendDueReminders(NOW)).isEqualTo(1);
        verify(notificationService).notifyOnce(eq(1L), eq(10L), eq(NotificationKind.NEW_DRIVE), anyString());
    }

    @Test
    void aDriveClosingWithinThreeDaysGetsTheThreeDayReminder() {
        eligibleDrives(drive(10L, NOW.plus(Duration.ofHours(60))));

        job.sendDueReminders(NOW);

        verify(notificationService).notifyOnce(eq(1L), eq(10L), eq(NotificationKind.DEADLINE_3_DAYS), anyString());
        verify(notificationService, never()).notifyOnce(anyLong(), anyLong(), eq(NotificationKind.NEW_DRIVE), anyString());
    }

    @Test
    void aDriveClosingWithinADayGetsTheLastDayReminder() {
        eligibleDrives(drive(10L, NOW.plus(Duration.ofHours(5))));

        job.sendDueReminders(NOW);

        verify(notificationService).notifyOnce(eq(1L), eq(10L), eq(NotificationKind.DEADLINE_1_DAY), anyString());
    }

    /** Somebody who has applied needs no reminder - unless they withdrew. */
    @Test
    void aDriveTheStudentAppliedToIsSkippedButAWithdrawnOneIsNot() {
        eligibleDrives(drive(10L, NOW.plus(Duration.ofHours(5))), drive(11L, NOW.plus(Duration.ofHours(5))));
        when(applicationService.findMine(1L)).thenReturn(List.of(
                application(10L, ApplicationStatus.APPLIED),
                application(11L, ApplicationStatus.WITHDRAWN)));

        job.sendDueReminders(NOW);

        verify(notificationService, never()).notifyOnce(eq(1L), eq(10L), any(), anyString());
        verify(notificationService).notifyOnce(eq(1L), eq(11L), eq(NotificationKind.DEADLINE_1_DAY), anyString());
    }

    /** Without marks there is no eligibility to judge, so nothing is sent. */
    @Test
    void aStudentWithAnIncompleteProfileIsSkipped() {
        when(userService.findAll(Optional.of(Role.STUDENT))).thenReturn(List.of(student(1L, false)));

        assertThat(job.sendDueReminders(NOW)).isZero();
        verify(driveService, never()).findEligibleDrives(any(), any(), any(), anyInt(), any());
    }

    /** A reminder already sent is not counted again - NotificationService said no. */
    @Test
    void aReminderAlreadySentIsNotCounted() {
        eligibleDrives(drive(10L, NOW.plus(Duration.ofDays(20))));
        when(notificationService.notifyOnce(anyLong(), anyLong(), any(), anyString())).thenReturn(false);

        assertThat(job.sendDueReminders(NOW)).isZero();
    }

    private void eligibleDrives(DriveDTO... drives) {
        when(driveService.findEligibleDrives(any(), any(), any(), anyInt(), any())).thenReturn(List.of(drives));
    }

    private static UserProfileDTO student(Long id, boolean complete) {
        return new UserProfileDTO(id, "student@cse.nits.ac.in", Role.STUDENT,
                complete ? new BigDecimal("8.5") : null, complete ? "CSE" : null,
                complete ? new BigDecimal("90") : null, complete ? new BigDecimal("88") : null, 0,
                null, null, null, null, null);
    }

    private static DriveDTO drive(Long id, Instant deadline) {
        return new DriveDTO(id, "Zoho", "SDE", new BigDecimal("900000"), 1, new BigDecimal("7.5"),
                null, null, false, null, List.of("CSE"), deadline, null);
    }

    private static ApplicationDTO application(Long driveId, ApplicationStatus status) {
        return new ApplicationDTO(driveId * 10, 1L, null, driveId, status, null, NOW, NOW);
    }
}
