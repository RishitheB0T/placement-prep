package com.rishikesh.placementprep.modules.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.rishikesh.placementprep.modules.agent.service.AssistantTools;
import com.rishikesh.placementprep.modules.application.dto.ApplicationDTO;
import com.rishikesh.placementprep.modules.application.model.ApplicationStatus;
import com.rishikesh.placementprep.modules.application.service.ApplicationService;
import com.rishikesh.placementprep.modules.auth.dto.UserProfileDTO;
import com.rishikesh.placementprep.modules.auth.model.Role;
import com.rishikesh.placementprep.modules.drive.dto.DriveDTO;
import com.rishikesh.placementprep.modules.drive.service.DriveService;
import com.rishikesh.placementprep.modules.knowledgebase.service.JobDescriptionService;

/**
 * Unit tests for the methods the model is allowed to call.
 *
 * <p>These are plain Java calls, so they pin down what each tool does no matter what the
 * model decides - AgentApiTest is where the model's side of the loop is exercised.
 */
@ExtendWith(MockitoExtension.class)
class AssistantToolsTest {

    @Mock
    private DriveService driveService;

    @Mock
    private ApplicationService applicationService;

    @Mock
    private JobDescriptionService jobDescriptionService;

    @Test
    void eligibleDrivesExplainsAnIncompleteProfileInsteadOfQueryingAnything() {
        AssistantTools tools = toolsFor(student(null, null));

        assertThatThrownBy(tools::eligibleDrives)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Profile page");
        verifyNoInteractions(driveService);
    }

    @Test
    void eligibleDrivesUsesTheStudentsOwnMarks() {
        when(driveService.findEligibleDrives(new BigDecimal("8.5"), new BigDecimal("90"), new BigDecimal("88"), 0, "CSE"))
                .thenReturn(List.of(drive(10L, Instant.parse("2099-01-01T00:00:00Z"))));

        List<AssistantTools.DriveSummary> drives = toolsFor(student(new BigDecimal("8.5"), "CSE")).eligibleDrives();

        assertThat(drives).extracting(AssistantTools.DriveSummary::companyName).containsExactly("Zoho");
    }

    /** Mirrors the Apply button, which is disabled once the deadline has passed. */
    @Test
    void applyToDriveRefusesAClosedDriveWithoutApplying() {
        when(driveService.findDriveById(10L)).thenReturn(Optional.of(drive(10L, Instant.parse("2020-01-01T00:00:00Z"))));

        String result = toolsFor(student(new BigDecimal("8.5"), "CSE")).applyToDrive(10L);

        assertThat(result).contains("closed");
        verify(applicationService, never()).apply(anyLong(), anyLong());
    }

    @Test
    void applyToDriveAppliesAsTheSignedInStudent() {
        when(driveService.findDriveById(10L)).thenReturn(Optional.of(drive(10L, Instant.parse("2099-01-01T00:00:00Z"))));
        when(applicationService.apply(1L, 10L)).thenReturn(Optional.of(new ApplicationDTO(
                100L, 1L, null, 10L, ApplicationStatus.APPLIED, null, Instant.now(), Instant.now())));

        String result = toolsFor(student(new BigDecimal("8.5"), "CSE")).applyToDrive(10L);

        assertThat(result).startsWith("Applied to Zoho");
        verify(applicationService).apply(1L, 10L);
    }

    @Test
    void applyToDriveReportsAnExistingApplication() {
        when(driveService.findDriveById(10L)).thenReturn(Optional.of(drive(10L, Instant.parse("2099-01-01T00:00:00Z"))));
        when(applicationService.apply(1L, 10L)).thenReturn(Optional.empty());

        assertThat(toolsFor(student(new BigDecimal("8.5"), "CSE")).applyToDrive(10L)).contains("already applied");
    }

    @Test
    void applyToDriveReportsAnUnknownDrive() {
        when(driveService.findDriveById(99L)).thenReturn(Optional.empty());

        assertThat(toolsFor(student(new BigDecimal("8.5"), "CSE")).applyToDrive(99L)).contains("no drive with id 99");
    }

    private AssistantTools toolsFor(UserProfileDTO student) {
        return new AssistantTools(student, driveService, applicationService, jobDescriptionService);
    }

    private static UserProfileDTO student(BigDecimal cgpa, String branch) {
        return new UserProfileDTO(1L, "student@cse.nits.ac.in", Role.STUDENT, cgpa, branch,
                cgpa == null ? null : new BigDecimal("90"), cgpa == null ? null : new BigDecimal("88"), 0,
                null, null, null, null, null);
    }

    private static DriveDTO drive(Long id, Instant deadline) {
        return new DriveDTO(id, "Zoho", "Member Technical Staff", new BigDecimal("900000"), 1, new BigDecimal("7.5"),
                null, null, false, null, List.of("CSE"), deadline, null);
    }
}
