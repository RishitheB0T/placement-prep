package com.rishikesh.placementprep.modules.auth.service;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rishikesh.placementprep.modules.auth.dto.UpdateProfileRequest;
import com.rishikesh.placementprep.modules.auth.dto.UserProfileDTO;
import com.rishikesh.placementprep.modules.auth.model.Role;
import com.rishikesh.placementprep.modules.auth.model.User;
import com.rishikesh.placementprep.modules.auth.repository.UserRepository;

/**
 * Reading and updating the signed-in user's own profile.
 *
 * <p>Separate from AuthService because the two answer different questions: AuthService is
 * about proving who you are, this is about the details attached to you once you have.
 *
 * <p>The self-service methods are keyed by email rather than by id. The email comes from
 * the verified token, so a caller can only ever reach their own row; there is no parameter
 * they could tamper with to read somebody else's profile. The two id-keyed methods exist
 * only for the promotion endpoint, which is restricted to the person in charge.
 */
@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public Optional<UserProfileDTO> findByEmail(String email) {
        return userRepository.findByEmail(email).map(this::toDto);
    }

    /**
     * Looks a user up by id. Unlike the methods keyed by email, this one can reach an
     * account other than the caller's, so every endpoint using it must be restricted to
     * the person in charge.
     */
    @Transactional(readOnly = true)
    public Optional<UserProfileDTO> findById(Long id) {
        return userRepository.findById(id).map(this::toDto);
    }

    /**
     * Makes a student a coordinator.
     *
     * <p>The guard is here rather than in the controller so that the rule holds for every
     * caller, including a future scheduled job or bulk import. Refusing anything that is
     * not a STUDENT is what stops this being a way to quietly demote the person in charge
     * to a coordinator.
     *
     * @return empty when no user has that id <em>or</em> when they are not a student. The
     *         controller tells those two apart to pick the right status code, because
     *         only it knows what a 404 and a 409 mean.
     */
    @Transactional
    public Optional<UserProfileDTO> promoteToCoordinator(Long id) {
        return userRepository.findById(id)
                .filter(user -> user.getRole() == Role.STUDENT)
                .map(user -> {
                    user.setRole(Role.TNP_COORDINATOR);
                    return toDto(userRepository.save(user));
                });
    }

    /**
     * Replaces the academic details of an existing user.
     *
     * @return empty when no user has that email, which in practice means the account was
     *         deleted after the token was issued
     */
    @Transactional
    public Optional<UserProfileDTO> updateProfile(String email, UpdateProfileRequest request) {
        return userRepository.findByEmail(email)
                .map(user -> {
                    applyRequest(user, request);
                    return toDto(userRepository.save(user));
                });
    }

    /**
     * Copies the editable fields of a request onto a user. Kept separate for the same
     * reason DriveService does it: so create and update paths cannot drift apart when a
     * field is added later.
     *
     * <p>Branch is upper-cased because the eligibility query compares branches
     * case-insensitively, and storing one casing makes that comparison cheaper and the
     * stored data consistent.
     */
    private void applyRequest(User user, UpdateProfileRequest request) {
        user.setCgpa(request.cgpa());
        user.setBranch(request.branch().trim().toUpperCase());
        user.setTenthPercentage(request.tenthPercentage());
        user.setTwelfthPercentage(request.twelfthPercentage());
        user.setBacklogs(request.backlogs());
    }

    private UserProfileDTO toDto(User user) {
        return new UserProfileDTO(
                user.getId(),
                user.getEmail(),
                user.getRole(),
                user.getCgpa(),
                user.getBranch(),
                user.getTenthPercentage(),
                user.getTwelfthPercentage(),
                user.getBacklogs());
    }
}
