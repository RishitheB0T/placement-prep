package com.rishikesh.placementprep.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of PUT /api/users/me/password - any signed-in account changing its own password.
 *
 * <p>The current password is required even though the caller already holds a valid token:
 * a token left in an unattended browser should not be enough to lock the owner out of
 * their own account. The new one has the same bounds as registration (BCrypt ignores
 * anything past 72 bytes).
 */
public record ChangePasswordRequest(
    @NotBlank
    String currentPassword,

    @NotBlank @Size(min = 8, max = 72)
    String newPassword
) {}
