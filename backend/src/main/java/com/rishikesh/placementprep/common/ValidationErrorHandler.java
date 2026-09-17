package com.rishikesh.placementprep.common;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Adds the field-level reasons to the 400 produced when a request body fails validation.
 *
 * <p>By default a rejected body answers with nothing more useful than
 * {@code "detail": "Invalid request content."}. Every message written on the request
 * records - why an address must be a college one, why a password has a length floor - is
 * computed and then thrown away, so a form has no way to show the user what to fix.
 *
 * <p>{@code @RestControllerAdvice} registers this across every controller, and extending
 * ResponseEntityExceptionHandler means only the one case below changes: all the other
 * exception types Spring already knows how to render keep their existing behaviour.
 */
@RestControllerAdvice
public class ValidationErrorHandler extends ResponseEntityExceptionHandler {

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        // Start from the ProblemDetail Spring already built, so status, title and instance
        // stay exactly as they were and only the extra member is new.
        ProblemDetail body = exception.getBody();

        // LinkedHashMap keeps the fields in declaration order, so a form showing several
        // errors lists them the same way every time rather than in hash order.
        Map<String, String> errors = new LinkedHashMap<>();

        for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
            // Only the field name and the message. The rejected value is deliberately left
            // out: it would echo back whatever was submitted, and on a registration form
            // the submitted body includes a password.
            errors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }

        body.setProperty("errors", errors);

        return handleExceptionInternal(exception, body, headers, status, request);
    }
}
