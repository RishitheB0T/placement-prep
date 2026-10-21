package com.rishikesh.placementprep.modules.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.rishikesh.placementprep.modules.application.model.Application;
import com.rishikesh.placementprep.modules.application.model.ApplicationStatus;
import com.rishikesh.placementprep.modules.application.repository.ApplicationRepository;
import com.rishikesh.placementprep.modules.application.service.ApplicationService;

/** Unit tests for ApplicationService's rules, with the repository mocked out. */
@ExtendWith(MockitoExtension.class)
class ApplicationServiceTest {

    @Mock
    private ApplicationRepository applicationRepository;

    @InjectMocks
    private ApplicationService applicationService;

    @Test
    void applyingForTheFirstTimeCreatesAnAppliedApplication() {
        when(applicationRepository.findByStudentIdAndDriveId(1L, 10L)).thenReturn(Optional.empty());
        when(applicationRepository.save(any(Application.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Optional<ApplicationDTO> created = applicationService.apply(1L, 10L);

        assertThat(created).get().satisfies(application -> {
            assertThat(application.studentId()).isEqualTo(1L);
            assertThat(application.driveId()).isEqualTo(10L);
            assertThat(application.status()).isEqualTo(ApplicationStatus.APPLIED);
        });
    }

    @Test
    void applyingAgainWhileAnApplicationIsActiveIsRefusedWithoutSaving() {
        when(applicationRepository.findByStudentIdAndDriveId(1L, 10L))
                .thenReturn(Optional.of(application(ApplicationStatus.SHORTLISTED, "Strong profile")));

        assertThat(applicationService.apply(1L, 10L)).isEmpty();
        verify(applicationRepository, never()).save(any());
    }

    /** A withdrawn row is reopened rather than duplicated, with the stale note cleared. */
    @Test
    void applyingAfterWithdrawingReopensTheSameRowAndClearsTheOldNote() {
        Application withdrawn = application(ApplicationStatus.WITHDRAWN, "Withdrew before the test");
        withdrawn.setId(5L);
        when(applicationRepository.findByStudentIdAndDriveId(1L, 10L)).thenReturn(Optional.of(withdrawn));
        when(applicationRepository.save(withdrawn)).thenReturn(withdrawn);

        Optional<ApplicationDTO> reopened = applicationService.apply(1L, 10L);

        assertThat(reopened).get().satisfies(application -> {
            assertThat(application.id()).isEqualTo(5L);
            assertThat(application.status()).isEqualTo(ApplicationStatus.APPLIED);
            assertThat(application.note()).isNull();
        });
    }

    /** Keyed by student as well as id, so another student's application is out of reach. */
    @Test
    void withdrawingSomeoneElsesApplicationChangesNothing() {
        when(applicationRepository.findByIdAndStudentId(5L, 2L)).thenReturn(Optional.empty());

        assertThat(applicationService.withdraw(5L, 2L)).isEmpty();
        verify(applicationRepository, never()).save(any());
    }

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
