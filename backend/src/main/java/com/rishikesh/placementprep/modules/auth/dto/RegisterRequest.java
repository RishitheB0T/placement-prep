package com.rishikesh.placementprep.modules.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /api/auth/register.
 *
 * <p>There is deliberately no role field. If the client could choose its own role, any
 * student could register as TNP_ADMIN and start publishing fake drives. Registration
 * always creates a STUDENT; administrators are provisioned separately.
 */
public record RegisterRequest(

    @NotBlank @Email
    String email,

    /**
     * The lower bound is a real security control, not decoration. The upper bound matters
     * too: BCrypt silently ignores anything past 72 bytes, so accepting longer passwords
     * would mean two different passwords could unlock the same account.
     */
    @NotBlank @Size(min = 8, max = 72)
    String password
) {}
