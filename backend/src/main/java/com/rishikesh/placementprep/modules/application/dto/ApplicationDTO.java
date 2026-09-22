package com.rishikesh.placementprep.modules.application.dto;

import java.time.Instant;

import com.rishikesh.placementprep.modules.application.model.ApplicationStatus;

public record ApplicationDTO(
    Long id,
    Long studentId,
    Long driveId,
    ApplicationStatus status,
    String note,
    Instant appliedAt,
    Instant updatedAt
) {}
