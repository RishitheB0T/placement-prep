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
     * A training-and-placement cell coordinator. Posts and manages drives, but cannot
     * change anybody's role - staffing the cell is the person-in-charge's decision.
     */
    TNP_COORDINATOR,

    /**
     * The training-and-placement cell's person in charge. Everything a coordinator can
     * do, plus promoting students into the cell.
     *
     * <p>There is no self-service route to this role: accounts are seeded directly into
     * the database, because whoever could grant it through the API would already need it.
     */
    TNP_PIC
}
