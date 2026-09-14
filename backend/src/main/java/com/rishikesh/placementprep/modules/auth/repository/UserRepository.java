package com.rishikesh.placementprep.modules.auth.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rishikesh.placementprep.modules.auth.model.User;

public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Looks a user up by the address they sign in with. This is a derived query: Spring
     * Data builds the SQL from the method name alone, so no @Query is needed.
     */
    Optional<User> findByEmail(String email);

    /** Used to reject a duplicate registration before attempting the insert. */
    boolean existsByEmail(String email);
}
