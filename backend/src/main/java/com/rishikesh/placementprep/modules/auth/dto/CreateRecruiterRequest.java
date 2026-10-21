package com.rishikesh.placementprep.modules.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /api/users/recruiters - the person in charge adding a company's recruiter.
 *
 * <p>Unlike RegisterRequest there is no college-domain pattern: a recruiter signs in with
 * their company address. That is safe only because this endpoint is restricted to the
 * person in charge; self-registration stays college-only. The password is a first password
 * the person in charge hands over, with the same bounds as registration (BCrypt ignores
 * anything past 72 bytes).
 */
public record CreateRecruiterRequest(
    @NotBlank @Email
    String email,

    @NotBlank @Size(min = 8, max = 72)
    String password
) {}
