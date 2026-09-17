package com.rishikesh.placementprep.modules.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /api/auth/register.
 *
 * <p>There is deliberately no role field. If the client could choose its own role, any
 * student could register as staff and start publishing fake drives. Registration always
 * creates a STUDENT; a coordinator is promoted from one by the person in charge, and the
 * person in charge is seeded directly into the database.
 */
public record RegisterRequest(

    /**
     * Only college addresses may register. @Email checks the shape of the address; this
     * pattern checks who issued it.
     *
     * <p>Every address the college issues sits under a subdomain - name@cse.nits.ac.in
     * rather than name@nits.ac.in - so the subdomain group repeats one-or-more times
     * rather than zero-or-more. The bare domain is therefore rejected: nobody has an
     * address of that shape, so accepting it would only widen what the check lets in.
     *
     * <p>Each subdomain label must be followed by a literal dot, which is what stops a
     * lookalike domain like name@notnits.ac.in from matching: the characters before
     * "nits.ac.in" have to end at a dot boundary.
     *
     * <p>(?i) makes the match case-insensitive. It has to be, because validation runs
     * before AuthService lowercases the address, so a student typing Name@CSE.NITS.ac.in
     * would otherwise be turned away.
     */
    @NotBlank
    @Email
    @Pattern(
        regexp = "(?i)^[^@\\s]+@([a-zA-Z0-9-]+\\.)+nits\\.ac\\.in$",
        message = "Registration is open only to college email addresses ending in .nits.ac.in")
    String email,

    /**
     * The lower bound is a real security control, not decoration. The upper bound matters
     * too: BCrypt silently ignores anything past 72 bytes, so accepting longer passwords
     * would mean two different passwords could unlock the same account.
     */
    @NotBlank @Size(min = 8, max = 72)
    String password
) {}
