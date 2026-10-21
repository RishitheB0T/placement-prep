package com.rishikesh.placementprep.modules.auth.model;

/**
 * Who a user is allowed to be.
 *
 * <p>Stored in the database as its name rather than its ordinal position, so that adding
 * or reordering constants later cannot silently change what existing rows mean.
 *
 * <p>Spring Security expects authorities to be named with a {@code ROLE_} prefix when
 * they are checked with {@code hasRole(...)}. The prefix is added when the authority is
 * built, not here, so this enum stays a plain domain concept.
 */
public enum Role {

    /** A student browsing drives they are eligible for. */
    STUDENT,

    /**
     * A training-and-placement cell coordinator: a student who also helps run the cell.
     * Everything a student can do, plus posting drives and reviewing applicants. Cannot
     * change anybody's role - staffing the cell is the person-in-charge's decision.
     */
    TNP_COORDINATOR,

    /**
     * A company's hiring contact. Posts and manages drives and reviews applicants, but is
     * not a student: no academic profile, no applying, no resume feedback, no assistant.
     *
     * <p>Created only by the person in charge, never through registration - registration
     * accepts college addresses only, and a recruiter signs in with a company address.
     */
    RECRUITER,

    /**
     * The training-and-placement cell's person in charge. Everything a coordinator can
     * do, plus promoting students into the cell.
     *
     * <p>There is no self-service route to this role: accounts are seeded directly into
     * the database, because whoever could grant it through the API would already need it.
     */
    TNP_PIC
}
