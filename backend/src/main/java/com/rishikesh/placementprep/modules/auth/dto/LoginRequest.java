package com.rishikesh.placementprep.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Body of POST /api/auth/login.
 *
 * <p>Only @NotBlank here, with no @Email or @Size. Applying the registration rules to a
 * login attempt would let a caller tell a malformed address apart from a wrong password
 * by the status code alone, which leaks whether an account exists.
 */
public record LoginRequest(

    @NotBlank
    String email,

    @NotBlank
    String password
) {}
