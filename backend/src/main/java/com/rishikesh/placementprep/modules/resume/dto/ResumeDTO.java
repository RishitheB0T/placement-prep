package com.rishikesh.placementprep.modules.resume.dto;

import java.time.Instant;
import java.util.List;

import com.rishikesh.placementprep.modules.resume.model.ResumeStatus;

/**
 * A resume and its review, as the student sees it.
 *
 * <p>storageKey is deliberately not here. Where the file sits on the server's disk is
 * nobody's business but the server's - the student downloads it through
 * GET /api/resumes/{id}/file, which looks the key up itself.
 *
 * @param strengths    never null - empty until the review completes
 * @param improvements never null - empty until the review completes
 */
public record ResumeDTO(
    Long id,
    Long driveId,
    String originalFilename,
    Long sizeBytes,
    ResumeStatus status,
    Integer score,
    String summary,
    List<String> strengths,
    List<String> improvements,
    String failureReason,
    Instant uploadedAt,
    Instant reviewedAt
) {}
