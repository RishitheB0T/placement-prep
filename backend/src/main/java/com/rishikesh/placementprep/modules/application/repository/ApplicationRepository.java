package com.rishikesh.placementprep.modules.application.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rishikesh.placementprep.modules.application.model.Application;

public interface ApplicationRepository extends JpaRepository<Application, Long> {

    List<Application> findByStudentIdOrderByAppliedAtDesc(Long studentId);

    List<Application> findByDriveIdOrderByAppliedAtDesc(Long driveId);

    /** Used to reject a duplicate application before attempting the insert. */
    Optional<Application> findByStudentIdAndDriveId(Long studentId, Long driveId);

    /** Scoped by student as well as id, so withdrawing can only ever touch your own row. */
    Optional<Application> findByIdAndStudentId(Long id, Long studentId);
}
