package com.rishikesh.placementprep.modules.application.dto;

import com.rishikesh.placementprep.modules.application.model.ApplicationStatus;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body of PATCH /api/applications/{id}/status, sent by the placement cell. */
public record UpdateStatusRequest(

    @NotNull
    ApplicationStatus status,

    @Size(max = 2000)
    String note
) {}
