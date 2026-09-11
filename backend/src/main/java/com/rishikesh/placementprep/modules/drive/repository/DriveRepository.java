package com.rishikesh.placementprep.modules.drive.repository;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.rishikesh.placementprep.modules.drive.model.Drive;

public interface DriveRepository extends JpaRepository<Drive, Long> {

    /**
     * Drives a given student is eligible to apply for, soonest deadline first.
     *
     * <p>This is a native query because the branch test reads a PostgreSQL array column,
     * and JPQL has no operator for "this array contains that value". Native means the SQL
     * below is handed straight to PostgreSQL, so it uses column names (cgpa_cutoff)
     * rather than entity field names (cgpaCutoff).
     *
     * <p>Every academic cutoff is written as "IS NULL OR ...". A NULL cutoff means the
     * drive sets no requirement, and in SQL a comparison against NULL yields NULL rather
     * than true, so without the explicit IS NULL branch the most permissive drives would
     * be the ones silently dropped from the results.
     *
     * <p>The backlog rule reads: the student has none, or the drive permits backlogs and
     * the student is within its ceiling. A NULL max_backlogs on a drive that permits them
     * means no ceiling.
     */
    @Query(value = """
            SELECT * FROM drives d
            WHERE (d.cgpa_cutoff    IS NULL OR d.cgpa_cutoff    <= :cgpa)
              AND (d.tenth_cutoff   IS NULL OR d.tenth_cutoff   <= :tenth)
              AND (d.twelfth_cutoff IS NULL OR d.twelfth_cutoff <= :twelfth)
              AND (:backlogs = 0
                   OR (d.backlogs_allowed = TRUE
                       AND (d.max_backlogs IS NULL OR :backlogs <= d.max_backlogs)))
              AND EXISTS (
                    SELECT 1 FROM unnest(d.eligible_branches) AS b
                    WHERE UPPER(b) = UPPER(:branch)
              )
              AND d.application_deadline > now()
            ORDER BY d.application_deadline ASC
            """, nativeQuery = true)
    List<Drive> findEligible(@Param("cgpa") BigDecimal cgpa,
                             @Param("tenth") BigDecimal tenth,
                             @Param("twelfth") BigDecimal twelfth,
                             @Param("backlogs") int backlogs,
                             @Param("branch") String branch);
}
