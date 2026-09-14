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

    /** A training-and-placement cell member who posts and manages drives. */
    TNP_ADMIN
}
