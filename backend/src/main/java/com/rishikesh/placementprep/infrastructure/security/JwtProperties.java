package com.rishikesh.placementprep.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * The jwt.* block of application.yml, bound as a typed object rather than two loose
 * strings injected with @Value.
 *
 * <p>Three things this buys over @Value:
 * <ul>
 *   <li>Editors can complete and validate the property names, because
 *       spring-boot-configuration-processor turns this record into metadata at compile
 *       time. That is what silences the "cannot resolve property" warning.</li>
 *   <li>The constraints below are checked when the application context starts, so a
 *       missing or too-short secret fails immediately with a message naming the property,
 *       instead of surfacing later as an obscure exception from the signing library.</li>
 *   <li>The values arrive already converted to the right types.</li>
 * </ul>
 *
 * <p>A record works here because binding uses the canonical constructor; it needs no
 * setters and stays immutable.
 *
 * @param secret     signing key. HMAC-SHA256 requires at least 256 bits, hence 32
 *                   characters. Never commit a real one: set JWT_SECRET in the
 *                   environment.
 * @param expiration token lifetime in milliseconds
 */
@Validated
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(

    @NotBlank
    @Size(min = 32, message = "jwt.secret must be at least 32 characters for HMAC-SHA256")
    String secret,

    @Positive
    long expiration
) {}
