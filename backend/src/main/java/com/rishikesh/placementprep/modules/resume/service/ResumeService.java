package com.rishikesh.placementprep.modules.resume.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rishikesh.placementprep.modules.resume.dto.ResumeDTO;
import com.rishikesh.placementprep.modules.resume.model.Resume;
import com.rishikesh.placementprep.modules.resume.model.ResumeStatus;
import com.rishikesh.placementprep.modules.resume.repository.ResumeRepository;

/**
 * Storing resumes, and the bookkeeping around reviewing them.
 *
 * <p>Two kinds of caller. The student-facing methods are keyed by student id as well as
 * resume id, the same ownership guard ApplicationService uses for withdraw. The
 * job-facing ones near the bottom are called only by ResumeReviewJob, which works on
 * whatever is next in the queue regardless of whose it is.
 *
 * <p>Takes the file as a byte array and a name rather than Spring's MultipartFile, so
 * this class has no idea an HTTP request was ever involved - the same rule DriveService
 * follows.
 */
@Service
public class ResumeService {

    /** A review still in flight. A second upload for the same drive waits for these. */
    private static final List<ResumeStatus> IN_PROGRESS =
            List.of(ResumeStatus.PENDING, ResumeStatus.PROCESSING);

    /** How upload() names stored files: a random UUID plus ".pdf". */
    private static final Pattern STORAGE_KEY =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.pdf");

    private final ResumeRepository resumeRepository;
    private final Path storageDirectory;

    public ResumeService(ResumeRepository resumeRepository,
                         @Value("${resume.storage.directory}") String storageDirectory) {
        this.resumeRepository = resumeRepository;
        // Resolved once, to an absolute path, so a log line or an error always names the
        // real location rather than one relative to wherever the server was started.
        this.storageDirectory = Path.of(storageDirectory).toAbsolutePath().normalize();
    }

    // ----------------------------------------------------------------- Create

    /**
     * Saves an uploaded resume and queues it for review.
     *
     * <p>The file is written to disk before the row is inserted, and deleted again if the
     * insert fails. The other order would risk a row pointing at a file that was never
     * written, which is worse than a stray file: the student would see a resume they
     * cannot open.
     *
     * @return empty when this student already has a review in flight for this drive -
     *         the earlier one has to finish first, otherwise a few impatient clicks would
     *         each queue their own model call
     */
    @Transactional
    public Optional<ResumeDTO> upload(Long studentId, Long driveId,
                                      String originalFilename, byte[] content) {
        // 1. One review at a time per drive
        if (resumeRepository.existsByStudentIdAndDriveIdAndStatusIn(studentId, driveId, IN_PROGRESS)) {
            return Optional.empty();
        }

        // 2. Write the PDF under a name the server chose. A random UUID cannot collide with
        //    another upload, and cannot contain "../" or anything else that would reach
        //    outside the storage directory - which a student-supplied name could.
        String storageKey = UUID.randomUUID() + ".pdf";
        writeFile(storageKey, content);

        // 3. Record it as waiting for the background job
        Resume resume = new Resume();
        resume.setStudentId(studentId);
        resume.setDriveId(driveId);
        resume.setOriginalFilename(displayName(originalFilename));
        resume.setStorageKey(storageKey);
        resume.setSizeBytes((long) content.length);
        resume.setStatus(ResumeStatus.PENDING);

        try {
            return Optional.of(toDto(resumeRepository.save(resume)));
        } catch (RuntimeException e) {
            // The row never made it in, so nothing points at the file any more.
            deleteFile(storageKey);
            throw e;
        }
    }

    // ------------------------------------------------------------------- Read

    @Transactional(readOnly = true)
    public List<ResumeDTO> findMine(Long studentId) {
        return resumeRepository.findByStudentIdOrderByUploadedAtDesc(studentId)
                .stream().map(this::toDto).toList();
    }

    /** Empty when there is no such resume, or it belongs to somebody else. */
    @Transactional(readOnly = true)
    public Optional<ResumeDTO> findMyResume(Long id, Long studentId) {
        return resumeRepository.findByIdAndStudentId(id, studentId).map(this::toDto);
    }

    /** The uploaded PDF itself, for the student to open again. Same ownership rule. */
    @Transactional(readOnly = true)
    public Optional<StoredFile> loadFile(Long id, Long studentId) {
        return resumeRepository.findByIdAndStudentId(id, studentId)
                .map(resume -> new StoredFile(resume.getOriginalFilename(), readFile(resume.getStorageKey())));
    }

    // ----------------------------------------------------------------- Update

    /**
     * Puts a failed review back in the queue. The file is still on disk, so there is
     * nothing to upload again.
     *
     * @return empty when there is no such resume of this student's, or when its review did
     *         not fail - the controller tells those two apart, the same way UserController
     *         does for promotion
     */
    @Transactional
    public Optional<ResumeDTO> retry(Long id, Long studentId) {
        return resumeRepository.findByIdAndStudentId(id, studentId)
                .filter(resume -> resume.getStatus() == ResumeStatus.FAILED)
                .map(resume -> {
                    resume.setStatus(ResumeStatus.PENDING);
                    resume.setFailureReason(null);
                    resume.setReviewedAt(null);
                    return toDto(resumeRepository.save(resume));
                });
    }

    // ------------------------------------------------- Background-job support

    /**
     * Takes the oldest waiting resume off the queue by marking it PROCESSING.
     *
     * <p>Its own short transaction, committed before the model is called. A model call can
     * take tens of seconds, and holding a database transaction open across it would tie up
     * a pooled connection for that whole time.
     */
    @Transactional
    public Optional<ReviewTask> claimNextPending() {
        return resumeRepository.findFirstByStatusOrderByUploadedAtAsc(ResumeStatus.PENDING)
                .map(resume -> {
                    resume.setStatus(ResumeStatus.PROCESSING);
                    resumeRepository.save(resume);
                    return new ReviewTask(resume.getId(), resume.getDriveId(), resume.getStorageKey());
                });
    }

    /**
     * Records a finished review. Looks the row up again rather than trusting the one
     * claimNextPending read: a drive deleted while its resume was being reviewed takes the
     * row with it, and there is then nothing left to save into.
     */
    @Transactional
    public void completeReview(Long id, ResumeReviewer.Review review) {
        resumeRepository.findById(id).ifPresent(resume -> {
            resume.setStatus(ResumeStatus.COMPLETED);
            resume.setScore(review.score());
            resume.setSummary(review.summary());
            resume.setStrengths(review.strengths());
            resume.setImprovements(review.improvements());
            resume.setFailureReason(null);
            resume.setReviewedAt(Instant.now());
            resumeRepository.save(resume);
        });
    }

    /** Records a review that could not be done, with a reason the student can act on. */
    @Transactional
    public void failReview(Long id, String reason) {
        resumeRepository.findById(id).ifPresent(resume -> {
            resume.setStatus(ResumeStatus.FAILED);
            resume.setFailureReason(reason);
            resume.setReviewedAt(Instant.now());
            resumeRepository.save(resume);
        });
    }

    /**
     * Puts back anything left PROCESSING when the server last stopped. A row can only be
     * PROCESSING while the job is actively working on it, so at startup every such row is
     * one whose review was cut off part way and will never finish on its own.
     *
     * @return how many were put back
     */
    @Transactional
    public int requeueInterrupted() {
        List<Resume> interrupted = resumeRepository.findByStatus(ResumeStatus.PROCESSING);
        for (Resume resume : interrupted) {
            resume.setStatus(ResumeStatus.PENDING);
        }
        resumeRepository.saveAll(interrupted);
        return interrupted.size();
    }

    /**
     * Deletes stored PDFs that no resume row points at any more.
     *
     * <p>Deleting a drive removes its resume rows through ON DELETE CASCADE, but the
     * database cannot delete files on disk. Rather than make the drive module reach into
     * this one, ResumeReviewJob runs this sweep every few hours.
     *
     * <p>Only files named the way upload() names them ({@code <uuid>.pdf}) are considered,
     * so nothing else that happens to sit in the folder is touched. Files modified after
     * {@code olderThan} are skipped: upload() writes the file a moment before its row is
     * committed, and a sweep landing in that gap must not delete a resume being uploaded.
     *
     * @return how many files were deleted
     */
    @Transactional(readOnly = true)
    public int deleteOrphanedFiles(Instant olderThan) {
        if (!Files.isDirectory(storageDirectory)) {
            return 0;
        }
        int deleted = 0;
        try (Stream<Path> files = Files.list(storageDirectory)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                String name = file.getFileName().toString();
                if (!STORAGE_KEY.matcher(name).matches()
                        || !Files.isRegularFile(file)
                        || Files.getLastModifiedTime(file).toInstant().isAfter(olderThan)
                        || resumeRepository.existsByStorageKey(name)) {
                    continue;
                }
                Files.deleteIfExists(file);
                deleted++;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not sweep " + storageDirectory, e);
        }
        return deleted;
    }

    /** The stored PDF's bytes. Public because ResumeReviewJob reads the file it claimed. */
    public byte[] readFile(String storageKey) {
        try {
            return Files.readAllBytes(storageDirectory.resolve(storageKey));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read stored resume " + storageKey, e);
        }
    }

    // -------------------------------------------------------- Helpers

    private void writeFile(String storageKey, byte[] content) {
        try {
            Files.createDirectories(storageDirectory);
            // CREATE_NEW refuses to overwrite: an existing file of this name would mean
            // something is badly wrong, and silently replacing it would hide that.
            Files.write(storageDirectory.resolve(storageKey), content, StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store resume " + storageKey, e);
        }
    }

    private void deleteFile(String storageKey) {
        try {
            Files.deleteIfExists(storageDirectory.resolve(storageKey));
        } catch (IOException e) {
            // Only reached while another failure is already on its way out of upload(). A
            // stray file is harmless, and throwing here would replace that real error with
            // this one.
        }
    }

    /**
     * The original filename, made safe to show. Some browsers send a full local path
     * rather than just the name, so everything up to the last slash is dropped; the
     * column holds 255 characters, so anything longer is cut there.
     */
    private String displayName(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "resume.pdf";
        }
        String name = originalFilename.substring(
                Math.max(originalFilename.lastIndexOf('/'), originalFilename.lastIndexOf('\\')) + 1).trim();
        if (name.isEmpty()) {
            return "resume.pdf";
        }
        return name.length() > 255 ? name.substring(0, 255) : name;
    }

    private ResumeDTO toDto(Resume resume) {
        return new ResumeDTO(
                resume.getId(),
                resume.getDriveId(),
                resume.getOriginalFilename(),
                resume.getSizeBytes(),
                resume.getStatus(),
                resume.getScore(),
                resume.getSummary(),
                resume.getStrengths() == null ? List.of() : resume.getStrengths(),
                resume.getImprovements() == null ? List.of() : resume.getImprovements(),
                resume.getFailureReason(),
                resume.getUploadedAt(),
                resume.getReviewedAt());
    }

    /** A stored PDF and the name to offer it under. */
    public record StoredFile(String filename, byte[] content) {}

    /** What ResumeReviewJob needs to review one claimed resume. */
    public record ReviewTask(Long resumeId, Long driveId, String storageKey) {}
}
