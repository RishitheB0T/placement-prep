package com.rishikesh.placementprep.modules.resume.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rishikesh.placementprep.modules.resume.model.Resume;
import com.rishikesh.placementprep.modules.resume.model.ResumeStatus;

public interface ResumeRepository extends JpaRepository<Resume, Long> {

    List<Resume> findByStudentIdOrderByUploadedAtDesc(Long studentId);

    /** Scoped by student as well as id, so a student can only ever reach their own resume. */
    Optional<Resume> findByIdAndStudentId(Long id, Long studentId);

    /** Used to refuse a second upload for a drive while the first is still being reviewed. */
    boolean existsByStudentIdAndDriveIdAndStatusIn(Long studentId, Long driveId,
                                                   Collection<ResumeStatus> statuses);

    /** The next job for the background review: the oldest resume still waiting. */
    Optional<Resume> findFirstByStatusOrderByUploadedAtAsc(ResumeStatus status);

    List<Resume> findByStatus(ResumeStatus status);

    /** Whether any resume row still points at this stored file - the orphan sweep's check. */
    boolean existsByStorageKey(String storageKey);
}
