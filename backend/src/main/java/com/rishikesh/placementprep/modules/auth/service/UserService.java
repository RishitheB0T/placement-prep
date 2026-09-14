package com.rishikesh.placementprep.modules.auth.service;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rishikesh.placementprep.modules.auth.dto.UpdateProfileRequest;
import com.rishikesh.placementprep.modules.auth.dto.UserProfileDTO;
import com.rishikesh.placementprep.modules.auth.model.User;
import com.rishikesh.placementprep.modules.auth.repository.UserRepository;

/**
 * Reading and updating the signed-in user's own profile.
 *
 * <p>Separate from AuthService because the two answer different questions: AuthService is
 * about proving who you are, this is about the details attached to you once you have.
 *
 * <p>Every method is keyed by email rather than by id. The email comes from the verified
 * token, so a caller can only ever reach their own row; there is no parameter they could
 * tamper with to read somebody else's profile.
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
