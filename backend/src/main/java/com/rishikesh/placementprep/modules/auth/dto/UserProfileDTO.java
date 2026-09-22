package com.rishikesh.placementprep.modules.auth.dto;

import java.math.BigDecimal;

import com.rishikesh.placementprep.modules.auth.model.Role;

/**
 * The logged-in user as the frontend sees them. Returned by GET /api/users/me.
 *
 * <p>There is deliberately no password field of any kind, hashed or otherwise.
 *
 * <p>One flat shape for every role, the same choice already made for the academic
 * fields: a student's {@code cgpa} and a PIC's {@code designation} sit side by side, each
 * null for the accounts they do not apply to, rather than three role-specific response
 * types. The frontend reads {@code role} once and decides which half of this to show.
 *
 * <p>{@code designation}, {@code department}, {@code staffId}, {@code officeLocation} and
 * {@code phoneNumber} are PIC-only, enforced by a CHECK constraint - always null for a
 * student or a coordinator.
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
    Integer backlogs,
    String designation,
    String department,
    String staffId,
    String officeLocation,
    String phoneNumber
) {

    /**
     * Whether enough of the profile is filled in to decide eligibility.
     *
     * <p>Jackson serialises this as a {@code complete} boolean alongside the real fields,
     * which lets the frontend prompt the student to finish their profile without having
     * to re-implement the same null checks.
     *
     * <p>Academic completeness only - a PIC has no CGPA to be missing, and nothing in the
     * system currently gates a feature behind their staff details being filled in, so
     * there is no equivalent "staff profile complete" concept to compute here.
     */
    public boolean isComplete() {
        return cgpa != null
                && branch != null && !branch.isBlank()
                && tenthPercentage != null
                && twelfthPercentage != null;
    }
}
