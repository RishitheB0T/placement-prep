package com.rishikesh.placementprep.infrastructure.security;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.rishikesh.placementprep.modules.auth.service.JwtService;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Turns a bearer token on the request into an authenticated user for the rest of the
 * request.
 *
 * <p>Extends OncePerRequestFilter rather than implementing Filter directly, which
 * guarantees it runs exactly once even when a request is forwarded internally.
 *
 * <p>This filter never rejects anything. If there is no token, or the token is bad, it
 * simply leaves the request unauthenticated and passes it along. Deciding whether an
 * unauthenticated request is acceptable belongs to SecurityConfig, which knows that
 * /api/auth/login is fine without a token while /api/drives is not. Separating "who are
 * you" from "are you allowed" keeps both readable.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    public JwtAuthenticationFilter(JwtService jwtService, UserDetailsService userDetailsService) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            String token = header.substring(BEARER_PREFIX.length());
            String email = jwtService.extractEmail(token);

            // Skip if something earlier in the chain already authenticated this request.
            if (email != null && SecurityContextHolder.getContext().getAuthentication() == null) {

                // Re-load from the database rather than trusting the role claim inside the
                // token. A token issued before an admin was demoted would otherwise keep
                // full privileges until it expired.
                UserDetails userDetails = userDetailsService.loadUserByUsername(email);

                var authentication = new UsernamePasswordAuthenticationToken(
                        userDetails, null, userDetails.getAuthorities());
                authentication.setDetails(
                        new WebAuthenticationDetailsSource().buildDetails(request));

                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        } catch (JwtException | IllegalArgumentException e) {
            // Expired, tampered with, or simply not a JWT. Clear anything half-set and
            // continue unauthenticated; SecurityConfig will answer with 401 if the target
            // endpoint required a user.
            SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }
}
