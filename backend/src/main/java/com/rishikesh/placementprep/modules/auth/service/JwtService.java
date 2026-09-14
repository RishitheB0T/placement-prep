package com.rishikesh.placementprep.modules.auth.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Service;

import com.rishikesh.placementprep.infrastructure.security.JwtProperties;
import com.rishikesh.placementprep.modules.auth.model.Role;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Creates and verifies JSON Web Tokens.
 *
 * <p>A JWT is three base64 segments separated by dots: a header, a payload of claims, and
 * a signature. Only the signature depends on the secret; the payload is merely encoded,
 * not encrypted, so anyone holding a token can read what is inside it. Never put anything
 * confidential in a claim. What the signature buys is tamper-evidence: a client can read
 * its own role, but cannot change STUDENT to TNP_ADMIN without breaking the signature.
 */
@Service
public class JwtService {

    private final SecretKey signingKey;
    private final long expirationMillis;

    /**
     * Takes the bound JwtProperties rather than two @Value strings, so the property names
     * are validated at startup and are visible to editors.
     */
    public JwtService(JwtProperties properties) {
        // HMAC-SHA256 needs at least 256 bits of key material. The @Size constraint on
        // JwtProperties already rejects a shorter secret when the context starts, so by
        // the time this runs the key is known to be long enough.
        this.signingKey = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.expirationMillis = properties.expiration();
    }

    /**
     * Mints a token for a user who has just proved who they are.
     *
     * <p>The email goes in the "subject" claim, the standard place for "who this token is
     * about". The role rides along as a custom claim purely as a convenience for clients.
     * The server never trusts it and re-loads the real role from the database on every
     * request, so revoking an admin takes effect on their next call rather than whenever
     * their token happens to expire.
     */
    public String generateToken(String email, Role role) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusMillis(expirationMillis);

        return Jwts.builder()
                .subject(email)
                .claim("role", role.name())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey)
                .compact();
    }

    /** When a token minted right now would stop being accepted. */
    public long expiresAtEpochMillis() {
        return Instant.now().plusMillis(expirationMillis).toEpochMilli();
    }

    /**
     * Verifies the signature and expiry, then returns the email the token was issued to.
     *
     * @throws JwtException if the token is malformed, expired, or was not signed by us
     */
    public String extractEmail(String token) throws JwtException {
        Claims claims = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claims.getSubject();
    }
}
