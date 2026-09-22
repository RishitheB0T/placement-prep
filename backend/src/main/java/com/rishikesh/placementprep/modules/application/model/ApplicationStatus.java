package com.rishikesh.placementprep.modules.application.model;

/**
 * Where an application has got to.
 *
 * <p>The student controls only the first and the last: applying creates an APPLIED row,
 * and withdrawing sets WITHDRAWN. Everything between is the placement cell's decision,
 * which is why changing status is restricted to staff in SecurityConfig.
 */
public enum ApplicationStatus {

    /** Submitted, not yet looked at. */
    APPLIED,

    SHORTLISTED,

    REJECTED,

    SELECTED,

    /** The student pulled out. Kept rather than deleted, so the history stays honest. */
    WITHDRAWN
}
