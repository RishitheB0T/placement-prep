package com.rishikesh.placementprep.modules.auth.dto;

import jakarta.validation.constraints.Size;

/**
 * Body of PUT /api/users/me/staff-profile. PIC only - SecurityConfig refuses anyone else
 * before this ever runs.
 *
 * <p>Every field is optional, unlike {@link UpdateProfileRequest}. There is no eligibility
 * filter or any other feature gated on a PIC's staff details being complete, so there is
 * nothing here for an all-or-nothing PUT to protect the way the student profile does. PUT
 * is still the right verb: this replaces the whole staff-details sub-resource each call,
 * and a field left out is a field that becomes null, the same as leaving out an optional
 * cutoff on {@code DriveRequest} clears it rather than leaving the old value in place.
 */
public record UpdateStaffProfileRequest(

    @Size(max = 100)
    String designation,

    @Size(max = 100)
    String department,

    @Size(max = 50)
    String staffId,

    @Size(max = 100)
    String officeLocation,

    @Size(max = 20)
    String phoneNumber
) {}
