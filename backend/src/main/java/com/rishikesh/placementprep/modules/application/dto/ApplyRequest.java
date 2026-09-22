package com.rishikesh.placementprep.modules.application.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Body of POST /api/applications.
 *
 * <p>Only the drive is named. The student comes from the signed-in account, never from
 * the body - otherwise anyone could apply on somebody else's behalf.
 */
public record ApplyRequest(

    @NotNull @Positive
    Long driveId
) {}
