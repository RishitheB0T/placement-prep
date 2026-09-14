package com.rishikesh.placementprep.modules.auth.dto;

import java.math.BigDecimal;

import com.rishikesh.placementprep.modules.auth.model.Role;

/**
 * The logged-in user as the frontend sees them. Returned by GET /api/users/me.
 *
 * <p>There is deliberately no password field of any kind, hashed or otherwise.
 *
 * @param backlogs never null; the column defaults to zero
 */
public record UserProfileDTO(
    Long id,
    String email,
    Role role,
    BigDecimal cgpa,
    String branch,
    BigDecimal tenthPercentage,
    BigDecimal twelfthPercentage,
    Integer backlogs
) {

    /**
     * Whether enough of the profile is filled in to decide eligibility.
     *
     * <p>Jackson serialises this as a {@code complete} boolean alongside the real fields,
     * which lets the frontend prompt the student to finish their profile without having
     * to re-implement the same null checks.
     */
    public boolean isComplete() {
        return cgpa != null
                && branch != null && !branch.isBlank()
                && tenthPercentage != null
                && twelfthPercentage != null;
    }
}
