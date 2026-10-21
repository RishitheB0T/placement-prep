package com.rishikesh.placementprep.modules.drive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

import com.rishikesh.placementprep.modules.drive.dto.DriveDTO;
import com.rishikesh.placementprep.modules.drive.dto.DriveRequest;
import com.rishikesh.placementprep.modules.drive.model.Drive;
import com.rishikesh.placementprep.modules.drive.repository.DriveRepository;
import com.rishikesh.placementprep.modules.drive.service.DriveService;

/**
 * Unit tests for DriveService on its own.
 *
 * <p>No Spring context and no database: the repository is a Mockito mock, so each test
 * runs in milliseconds and fails only because of DriveService itself. DriveApiTest covers
 * the same class from the outside, through HTTP and a real PostgreSQL - the two kinds of
 * test catch different mistakes.
 */
@ExtendWith(MockitoExtension.class)
class DriveServiceTest {

    @Mock
    private DriveRepository driveRepository;

    @InjectMocks
    private DriveService driveService;

    @Test
    void createDriveCopiesEveryFieldOntoTheEntityItSaves() {
        when(driveRepository.save(any(Drive.class))).thenAnswer(invocation -> {
            Drive drive = invocation.getArgument(0);
            drive.setId(42L);
            return drive;
        });

        DriveDTO created = driveService.createDrive(request("Zoho"), 5L);

        ArgumentCaptor<Drive> saved = ArgumentCaptor.forClass(Drive.class);
        verify(driveRepository).save(saved.capture());
        assertThat(saved.getValue().getCompanyName()).isEqualTo("Zoho");
        assertThat(saved.getValue().getCgpaCutoff()).isEqualByComparingTo("7.5");
        assertThat(saved.getValue().getEligibleBranches()).containsExactly("CSE", "ECE");
        assertThat(saved.getValue().getCreatedBy()).isEqualTo(5L);
        assertThat(created.id()).isEqualTo(42L);
    }

    @Test
    void updateDriveReturnsEmptyAndSavesNothingForAnUnknownId() {
        when(driveRepository.findById(99L)).thenReturn(Optional.empty());

        assertThat(driveService.updateDrive(99L, request("Zoho"), Optional.empty())).isEmpty();
        verify(driveRepository, never()).save(any());
    }

    @Test
    void updateDriveReplacesTheFieldsOfAnExistingDrive() {
        Drive existing = new Drive();
        existing.setId(7L);
        existing.setCompanyName("Old name");
        when(driveRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(driveRepository.save(existing)).thenReturn(existing);

        Optional<DriveDTO> updated = driveService.updateDrive(7L, request("New name"), Optional.empty());

        assertThat(updated).get().extracting(DriveDTO::companyName).isEqualTo("New name");
    }

    /** Spring Data's delete is silent about a missing row, so the service looks first. */
    @Test
    void deleteDriveReportsFalseAndDeletesNothingForAnUnknownId() {
        when(driveRepository.findById(99L)).thenReturn(Optional.empty());

        assertThat(driveService.deleteDrive(99L, Optional.empty())).isFalse();
        verify(driveRepository, never()).delete(any());
    }

    @Test
    void deleteDriveDeletesAnExistingDrive() {
        Drive existing = drive(7L, null);
        when(driveRepository.findById(7L)).thenReturn(Optional.of(existing));

        assertThat(driveService.deleteDrive(7L, Optional.empty())).isTrue();
        verify(driveRepository).delete(existing);
    }

    // ------------------------------------------------- A recruiter's scope

    @Test
    void aRecruiterListsOnlyTheDrivesTheyPosted() {
        when(driveRepository.findByCreatedByOrderByCreatedAtDesc(5L)).thenReturn(List.of(drive(7L, 5L)));

        assertThat(driveService.findAllDrives(Optional.of(5L))).extracting(DriveDTO::id).containsExactly(7L);
        verify(driveRepository, never()).findAll(any(Sort.class));
    }

    /** Another recruiter's drive looks exactly like a drive that does not exist. */
    @Test
    void aRecruiterCannotSeeEditOrDeleteSomebodyElsesDrive() {
        when(driveRepository.findById(7L)).thenReturn(Optional.of(drive(7L, 6L)));

        assertThat(driveService.findDriveById(7L, Optional.of(5L))).isEmpty();
        assertThat(driveService.updateDrive(7L, request("Hijacked"), Optional.of(5L))).isEmpty();
        assertThat(driveService.deleteDrive(7L, Optional.of(5L))).isFalse();
        verify(driveRepository, never()).save(any());
        verify(driveRepository, never()).delete(any());
    }

    /** A drive posted before created_by existed belongs to the cell, not to any recruiter. */
    @Test
    void aRecruiterCannotSeeADriveWithNoRecordedPoster() {
        when(driveRepository.findById(7L)).thenReturn(Optional.of(drive(7L, null)));

        assertThat(driveService.findDriveById(7L, Optional.of(5L))).isEmpty();
        assertThat(driveService.findDriveById(7L, Optional.empty())).isPresent();
    }

    @Test
    void anUpdateNeverChangesWhoPostedTheDrive() {
        Drive existing = drive(7L, 5L);
        when(driveRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(driveRepository.save(existing)).thenReturn(existing);

        driveService.updateDrive(7L, request("New name"), Optional.of(5L));

        assertThat(existing.getCreatedBy()).isEqualTo(5L);
    }

    private static Drive drive(Long id, Long createdBy) {
        Drive drive = new Drive();
        drive.setId(id);
        drive.setCompanyName("Zoho");
        drive.setCreatedBy(createdBy);
        return drive;
    }

    private static DriveRequest request(String company) {
        return new DriveRequest(company, "SDE", new BigDecimal("900000"), 1, new BigDecimal("7.5"),
                null, null, false, null, List.of("CSE", "ECE"), Instant.parse("2099-01-01T00:00:00Z"), null);
    }
}
