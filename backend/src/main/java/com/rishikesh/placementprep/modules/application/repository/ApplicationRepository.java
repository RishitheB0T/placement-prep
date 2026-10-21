package com.rishikesh.placementprep.modules.application.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.rishikesh.placementprep.modules.application.model.Application;
import com.rishikesh.placementprep.modules.application.model.ApplicationStatus;

public interface ApplicationRepository extends JpaRepository<Application, Long> {

    List<Application> findByStudentIdOrderByAppliedAtDesc(Long studentId);

    List<Application> findByDriveIdOrderByAppliedAtDesc(Long driveId);

    /** Used to reject a duplicate application before attempting the insert. */
    Optional<Application> findByStudentIdAndDriveId(Long studentId, Long driveId);

    /** Scoped by student as well as id, so withdrawing can only ever touch your own row. */
    Optional<Application> findByIdAndStudentId(Long id, Long studentId);

    /**
     * How many applications are in each status, across every drive. Each row is
     * {status, count}. GROUP BY lets PostgreSQL do the counting, rather than loading every
     * application into memory just to count them.
     */
    @Query("SELECT a.status, COUNT(a) FROM Application a GROUP BY a.status")
    List<Object[]> countByStatus();

    /** How many applications in one status each drive has. Each row is {driveId, count}. */
    @Query("SELECT a.driveId, COUNT(a) FROM Application a WHERE a.status = :status GROUP BY a.driveId")
    List<Object[]> countPerDrive(@Param("status") ApplicationStatus status);
}
