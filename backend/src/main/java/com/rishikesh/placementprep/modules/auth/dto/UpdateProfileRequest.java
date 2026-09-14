package com.rishikesh.placementprep.modules.auth.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Body of PUT /api/users/me.
 *
 * <p>Every field is required. PUT replaces a resource in full, so a missing field would
 * mean "set this to nothing" rather than "leave it alone" - and a half-filled profile is
 * exactly what makes the eligibility filter silently wrong. A partial update would be
 * PATCH with a different request type.
 *
 * <p>Email and role are not here on purpose. Letting a client change its own role through
 * a profile update would be the same privilege-escalation hole that registration closes.
 */
public record UpdateProfileRequest(

    @NotNull @DecimalMin("0.0") @DecimalMax("10.0")
    BigDecimal cgpa,

    @NotBlank
    String branch,

    @NotNull @DecimalMin("0.0") @DecimalMax("100.0")
    BigDecimal tenthPercentage,

    @NotNull @DecimalMin("0.0") @DecimalMax("100.0")
    BigDecimal twelfthPercentage,

    @NotNull @PositiveOrZero
    Integer backlogs
) {}
