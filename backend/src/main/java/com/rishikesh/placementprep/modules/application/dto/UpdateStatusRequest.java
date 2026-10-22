package com.rishikesh.placementprep.modules.application.dto;

import com.rishikesh.placementprep.modules.application.model.ApplicationStatus;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body of PATCH /api/applications/{id}/status, sent by the hiring side. */
public record UpdateStatusRequest(

    @NotNull
    ApplicationStatus status,

    @Size(max = 2000)
    String note
) {

    /**
     * WITHDRAWN is the student's decision, made through POST /{id}/withdraw. The hiring
     * side can only move an application to APPLIED, SHORTLISTED, REJECTED or SELECTED.
     * A null status passes here and is reported by @NotNull instead.
     */
    @AssertTrue(message = "Only the student can withdraw an application")
    public boolean isNotWithdrawal() {
        return status != ApplicationStatus.WITHDRAWN;
    }
}
