package com.rishikesh.placementprep.modules.application.dto;

import java.time.Instant;

import com.rishikesh.placementprep.modules.application.model.ApplicationStatus;

/**
 * @param studentEmail filled in only where the caller is looking at somebody else's
 *        application - the placement cell reviewing a drive's applicants. It stays null
 *        on a student's own list, where a bare id is no worse than an email they already
 *        know, and populating it would mean a lookup on every row for no gain.
 */
public record ApplicationDTO(
    Long id,
    Long studentId,
    String studentEmail,
    Long driveId,
    ApplicationStatus status,
    String note,
    Instant appliedAt,
    Instant updatedAt
) {

    /** The same application with a name attached, for the applicant-review view. */
    public ApplicationDTO withStudentEmail(String email) {
        return new ApplicationDTO(id, studentId, email, driveId, status, note, appliedAt, updatedAt);
    }
}
