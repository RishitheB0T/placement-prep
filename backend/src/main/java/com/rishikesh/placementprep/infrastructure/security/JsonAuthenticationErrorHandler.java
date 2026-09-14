package com.rishikesh.placementprep.infrastructure.security;

import java.io.IOException;
import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Renders authentication and authorisation failures as JSON.
 *
 * <p>These two failures are thrown inside the servlet filter chain, before any controller
 * runs, so a @RestControllerAdvice never sees them. Left alone, Spring Security answers
 * with its default HTML error page, which a JSON client cannot parse. This class supplies
 * both handlers so that every error from this API has the same shape.
 *
 * <p>The body is a ProblemDetail, the RFC 9457 format Spring uses for error responses.
 *
 * <p>One class implements both interfaces because the two responses differ only in status
 * code and wording, and keeping them together makes it obvious they stay consistent.
 */
@Component
public class JsonAuthenticationErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public JsonAuthenticationErrorHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** No token, an expired one, or one that fails signature verification. */
    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        write(request, response, HttpStatus.UNAUTHORIZED,
                "Authentication required",
                "A valid bearer token is required. Obtain one from POST /api/auth/login.");
    }

    /** A valid token, but the role it belongs to is not allowed here. */
    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        write(request, response, HttpStatus.FORBIDDEN,
                "Access denied",
                "Your account does not have permission to perform this action.");
    }

    private void write(HttpServletRequest request,
                       HttpServletResponse response,
                       HttpStatus status,
                       String title,
                       String detail) throws IOException {

        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setTitle(title);
        body.setInstance(URI.create(request.getRequestURI()));

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
