package com.rishikesh.placementprep.modules.auth.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rishikesh.placementprep.modules.auth.dto.CreateRecruiterRequest;
import com.rishikesh.placementprep.modules.auth.dto.UpdateProfileRequest;
import com.rishikesh.placementprep.modules.auth.dto.UpdateStaffProfileRequest;
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
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
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
     * Every account, or only those with one role, alphabetically by email. For the admin's
     * user list - the endpoint using it is restricted to the person in charge, since it
     * reaches every account rather than the caller's own.
     */
    @Transactional(readOnly = true)
    public List<UserProfileDTO> findAll(Optional<Role> role) {
        List<User> users = role.isPresent()
                ? userRepository.findByRoleOrderByEmailAsc(role.get())
                : userRepository.findAll(Sort.by("email"));
        return users.stream().map(this::toDto).toList();
    }

    /**
     * Email addresses for a set of account ids, in one query.
     *
     * <p>Exists so a caller showing a list of people can label every row without issuing a
     * lookup per row. Returns only the ids that resolved: an id with no account simply has
     * no entry, which the caller reads as "unknown" rather than as a failure.
     */
    @Transactional(readOnly = true)
    public Map<Long, String> emailsByIds(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(User::getId, User::getEmail));
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
     * Creates a company recruiter's account. The endpoint using it is restricted to the
     * person in charge, the same way promotion is.
     *
     * <p>The email is normalised exactly as AuthService does at registration and login, so
     * the recruiter can sign in however they capitalise it.
     *
     * @return empty when the email is already registered
     */
    @Transactional
    public Optional<UserProfileDTO> createRecruiter(CreateRecruiterRequest request) {
        String email = request.email().trim().toLowerCase();
        if (userRepository.existsByEmail(email)) {
            return Optional.empty();
        }

        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(Role.RECRUITER);
        return Optional.of(toDto(userRepository.save(user)));
    }

    /**
     * Changes the signed-in account's own password, after checking the current one.
     *
     * @return false when the current password is wrong (or the account no longer exists);
     *         nothing is changed in that case
     */
    @Transactional
    public boolean changePassword(String email, String currentPassword, String newPassword) {
        return userRepository.findByEmail(email)
                .filter(user -> passwordEncoder.matches(currentPassword, user.getPasswordHash()))
                .map(user -> {
                    user.setPasswordHash(passwordEncoder.encode(newPassword));
                    userRepository.save(user);
                    return true;
                })
                .orElse(false);
    }

    /**
     * Sets a new password on any account - the person in charge's answer to "I forgot my
     * password". The endpoint using it is restricted to the person in charge.
     *
     * @return false when no account has that id
     */
    @Transactional
    public boolean resetPassword(Long id, String newPassword) {
        return userRepository.findById(id)
                .map(user -> {
                    user.setPasswordHash(passwordEncoder.encode(newPassword));
                    userRepository.save(user);
                    return true;
                })
                .orElse(false);
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
     * Replaces the person in charge's own staff details.
     *
     * <p>Unrestricted here on purpose - SecurityConfig already refuses anyone who is not
     * TNP_PIC before this method is ever reached, the same division of labour every other
     * self-service method in this class relies on. The CHECK constraint added in V10 is
     * the backstop if that ever changes.
     *
     * @return empty when no user has that email, which in practice means the account was
     *         deleted after the token was issued
     */
    @Transactional
    public Optional<UserProfileDTO> updateStaffProfile(String email, UpdateStaffProfileRequest request) {
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

    /** Same shared-mapping reasoning as the academic overload above, for the PIC's own fields. */
    private void applyRequest(User user, UpdateStaffProfileRequest request) {
        user.setDesignation(request.designation());
        user.setDepartment(request.department());
        user.setStaffId(request.staffId());
        user.setOfficeLocation(request.officeLocation());
        user.setPhoneNumber(request.phoneNumber());
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
                user.getBacklogs(),
                user.getDesignation(),
                user.getDepartment(),
                user.getStaffId(),
                user.getOfficeLocation(),
                user.getPhoneNumber());
    }
}
