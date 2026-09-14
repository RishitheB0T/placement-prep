package com.rishikesh.placementprep.modules.auth.dto;

import com.rishikesh.placementprep.modules.auth.model.Role;

/**
 * Returned by both register and login, so a successful registration signs the user in
 * immediately rather than making them log in again.
 *
 * <p>The password hash is not a field here, and must never become one.
 *
 * @param token      the signed JWT, to be sent back as "Authorization: Bearer &lt;token&gt;"
 * @param expiresAt  epoch milliseconds at which the token stops being accepted
 */
public record AuthResponse(
    String token,
    String email,
    Role role,
    long expiresAt
) {}
