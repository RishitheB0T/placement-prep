package com.rishikesh.placementprep.modules.application.service;

/**
 * An application rule said no: applying, withdrawing or deciding was not allowed.
 *
 * <p>A domain exception rather than a web one, so ApplicationService stays free of HTTP
 * types and every caller - the REST controller, the assistant's applyToDrive tool - gets
 * the same rules and the same human-readable message. The controller turns the reason into
 * a status code; the assistant hands the message back to the model.
 */
public class ApplicationRefusedException extends RuntimeException {

    /** Why it was refused. Only NO_SUCH_DRIVE is "not found"; the rest are conflicts. */
    public enum Reason {
        NO_SUCH_DRIVE,
        ALREADY_APPLIED,
        CLOSED,
        PROFILE_INCOMPLETE,
        NOT_ELIGIBLE,
        CANNOT_WITHDRAW,
        WITHDRAWN_BY_STUDENT
    }

    private final Reason reason;

    public ApplicationRefusedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
