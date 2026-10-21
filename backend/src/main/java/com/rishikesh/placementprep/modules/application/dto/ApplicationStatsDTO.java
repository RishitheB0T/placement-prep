package com.rishikesh.placementprep.modules.application.dto;

import java.util.Map;

import com.rishikesh.placementprep.modules.application.model.ApplicationStatus;

/**
 * Application counts for the recruiter's and admin's dashboards. Returned by
 * GET /api/applications/stats.
 *
 * @param byStatus              every status is present, zero included, so a dashboard never
 *                              has to treat a missing key as zero itself
 * @param awaitingReviewByDrive driveId to the number of applications still APPLIED - not yet
 *                              looked at by the cell. Drives with none are left out.
 */
public record ApplicationStatsDTO(
    Map<ApplicationStatus, Long> byStatus,
    Map<Long, Long> awaitingReviewByDrive
) {}
