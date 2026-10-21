package com.rishikesh.placementprep.modules.resume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.rishikesh.placementprep.modules.resume.dto.ResumeDTO;
import com.rishikesh.placementprep.modules.resume.model.Resume;
import com.rishikesh.placementprep.modules.resume.model.ResumeStatus;
import com.rishikesh.placementprep.modules.resume.repository.ResumeRepository;
import com.rishikesh.placementprep.modules.resume.service.ResumeService;

/**
 * Unit tests for ResumeService. The repository is mocked; the storage directory is a real
 * temporary folder JUnit creates and deletes for each test, so file writes are real.
 */
@ExtendWith(MockitoExtension.class)
class ResumeServiceTest {

    private static final byte[] PDF = "%PDF-1.4 test".getBytes(StandardCharsets.US_ASCII);

    @Mock
    private ResumeRepository resumeRepository;

    @TempDir
    private Path storage;

    private ResumeService resumeService;

    @BeforeEach
    void createTheServiceOverTheTemporaryFolder() {
        resumeService = new ResumeService(resumeRepository, storage.toString());
    }

    @Test
    void uploadingWritesTheFileUnderAServerChosenNameAndQueuesIt() throws IOException {
        when(resumeRepository.save(any(Resume.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Optional<ResumeDTO> uploaded = resumeService.upload(1L, 10L, "C:\\Users\\asha\\My Resume.pdf", PDF);

        ArgumentCaptor<Resume> saved = ArgumentCaptor.forClass(Resume.class);
        verify(resumeRepository).save(saved.capture());
        // The path some browsers send is stripped; only the name is kept, for display.
        assertThat(saved.getValue().getOriginalFilename()).isEqualTo("My Resume.pdf");
        assertThat(saved.getValue().getStorageKey()).matches("[0-9a-f-]{36}\\.pdf");
        assertThat(Files.readAllBytes(storage.resolve(saved.getValue().getStorageKey()))).isEqualTo(PDF);
        assertThat(uploaded).get().extracting(ResumeDTO::status).isEqualTo(ResumeStatus.PENDING);
    }

    @Test
    void uploadingWhileAReviewIsInFlightWritesAndSavesNothing() throws IOException {
        when(resumeRepository.existsByStudentIdAndDriveIdAndStatusIn(anyLong(), anyLong(), anyCollection()))
                .thenReturn(true);

        assertThat(resumeService.upload(1L, 10L, "resume.pdf", PDF)).isEmpty();
        verify(resumeRepository, never()).save(any());
        try (var files = Files.list(storage)) {
            assertThat(files).isEmpty();
        }
    }

    /** No row would point at the file, so it must not be left behind. */
    @Test
    void ifTheRowCannotBeSavedTheFileIsRemovedAgain() throws IOException {
        when(resumeRepository.save(any(Resume.class))).thenThrow(new IllegalStateException("database down"));

        assertThatThrownBy(() -> resumeService.upload(1L, 10L, "resume.pdf", PDF))
                .isInstanceOf(IllegalStateException.class);
        try (var files = Files.list(storage)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void retryingAFailedReviewPutsItBackInTheQueue() {
        Resume failed = resume(ResumeStatus.FAILED);
        failed.setFailureReason("AI feedback is not switched on.");
        when(resumeRepository.findByIdAndStudentId(3L, 1L)).thenReturn(Optional.of(failed));
        when(resumeRepository.save(failed)).thenReturn(failed);

        Optional<ResumeDTO> retried = resumeService.retry(3L, 1L);

        assertThat(retried).get().satisfies(r -> {
            assertThat(r.status()).isEqualTo(ResumeStatus.PENDING);
            assertThat(r.failureReason()).isNull();
        });
    }

    @Test
    void onlyAFailedReviewCanBeRetried() {
        when(resumeRepository.findByIdAndStudentId(3L, 1L)).thenReturn(Optional.of(resume(ResumeStatus.COMPLETED)));

        assertThat(resumeService.retry(3L, 1L)).isEmpty();
        verify(resumeRepository, never()).save(any());
    }

    @Test
    void claimingTheNextResumeMarksItProcessing() {
        Resume pending = resume(ResumeStatus.PENDING);
        when(resumeRepository.findFirstByStatusOrderByUploadedAtAsc(ResumeStatus.PENDING)).thenReturn(Optional.of(pending));

        Optional<ResumeService.ReviewTask> task = resumeService.claimNextPending();

        assertThat(pending.getStatus()).isEqualTo(ResumeStatus.PROCESSING);
        assertThat(task).get().extracting(ResumeService.ReviewTask::resumeId).isEqualTo(3L);
    }

    @Test
    void reviewsCutOffByARestartGoBackToPending() {
        Resume interrupted = resume(ResumeStatus.PROCESSING);
        when(resumeRepository.findByStatus(ResumeStatus.PROCESSING)).thenReturn(List.of(interrupted));

        assertThat(resumeService.requeueInterrupted()).isEqualTo(1);
        assertThat(interrupted.getStatus()).isEqualTo(ResumeStatus.PENDING);
    }

    private static Resume resume(ResumeStatus status) {
        Resume resume = new Resume();
        resume.setId(3L);
        resume.setStudentId(1L);
        resume.setDriveId(10L);
        resume.setOriginalFilename("resume.pdf");
        resume.setStorageKey("key.pdf");
        resume.setSizeBytes(100L);
        resume.setStatus(status);
        return resume;
    }
}
