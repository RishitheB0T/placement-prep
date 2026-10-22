package com.rishikesh.placementprep.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /api/users/{id}/password - the person in charge setting a new password for
 * someone who has forgotten theirs.
 *
 * <p>This is the project's "forgot password" path. The usual self-service version emails
 * a reset link, which needs a mail server and a mail library; until then, the person in
 * charge sets a temporary password and hands it over, and the owner changes it on their
 * Profile page.
 */
public record ResetPasswordRequest(
    @NotBlank @Size(min = 8, max = 72)
    String newPassword
) {}
