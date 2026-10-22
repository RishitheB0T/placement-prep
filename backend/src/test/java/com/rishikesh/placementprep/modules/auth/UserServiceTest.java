package com.rishikesh.placementprep.modules.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.rishikesh.placementprep.modules.auth.dto.CreateRecruiterRequest;
import com.rishikesh.placementprep.modules.auth.dto.UserProfileDTO;
import com.rishikesh.placementprep.modules.auth.model.Role;
import com.rishikesh.placementprep.modules.auth.model.User;
import com.rishikesh.placementprep.modules.auth.repository.UserRepository;
import com.rishikesh.placementprep.modules.auth.service.UserService;

/** Unit tests for UserService's rules, with the repository mocked out. */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserService userService;

    @Test
    void promotingAStudentMakesThemACoordinator() {
        User student = user(5L, Role.STUDENT);
        when(userRepository.findById(5L)).thenReturn(Optional.of(student));
        when(userRepository.save(student)).thenReturn(student);

        Optional<UserProfileDTO> promoted = userService.promoteToCoordinator(5L);

        assertThat(promoted).get().extracting(UserProfileDTO::role).isEqualTo(Role.TNP_COORDINATOR);
    }

    /** Only a STUDENT can be promoted, so this can never quietly demote the admin. */
    @Test
    void promotingAnyoneWhoIsNotAStudentChangesNothing() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L, Role.TNP_PIC)));

        assertThat(userService.promoteToCoordinator(1L)).isEmpty();
        verify(userRepository, never()).save(any());
    }

    @Test
    void creatingARecruiterStoresAHashedPasswordAndTheRecruiterRole() {
        when(userRepository.existsByEmail("hr@company.com")).thenReturn(false);
        when(passwordEncoder.encode("first-password")).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

        Optional<UserProfileDTO> created =
                userService.createRecruiter(new CreateRecruiterRequest("  HR@Company.com ", "first-password"));

        assertThat(created).get().extracting(UserProfileDTO::email, UserProfileDTO::role)
                .containsExactly("hr@company.com", Role.RECRUITER);
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("hashed");
    }

    @Test
    void creatingARecruiterWithATakenEmailChangesNothing() {
        when(userRepository.existsByEmail("hr@company.com")).thenReturn(true);

        assertThat(userService.createRecruiter(new CreateRecruiterRequest("hr@company.com", "first-password")))
                .isEmpty();
        verify(userRepository, never()).save(any());
    }

    @Test
    void changingThePasswordWithTheRightCurrentOneStoresTheNewHash() {
        User user = user(5L, Role.STUDENT);
        user.setPasswordHash("old-hash");
        when(userRepository.findByEmail("user5@cse.nits.ac.in")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("old-password", "old-hash")).thenReturn(true);
        when(passwordEncoder.encode("new-password")).thenReturn("new-hash");

        assertThat(userService.changePassword("user5@cse.nits.ac.in", "old-password", "new-password")).isTrue();
        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
    }

    @Test
    void aWrongCurrentPasswordChangesNothing() {
        User user = user(5L, Role.STUDENT);
        user.setPasswordHash("old-hash");
        when(userRepository.findByEmail("user5@cse.nits.ac.in")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("guess", "old-hash")).thenReturn(false);

        assertThat(userService.changePassword("user5@cse.nits.ac.in", "guess", "new-password")).isFalse();
        assertThat(user.getPasswordHash()).isEqualTo("old-hash");
        verify(userRepository, never()).save(any());
    }

    @Test
    void resettingAnUnknownAccountsPasswordReportsFalse() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThat(userService.resetPassword(99L, "temporary-password")).isFalse();
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    void listingOneRoleUsesTheRoleQuery() {
        when(userRepository.findByRoleOrderByEmailAsc(Role.STUDENT)).thenReturn(List.of(user(5L, Role.STUDENT)));

        assertThat(userService.findAll(Optional.of(Role.STUDENT)))
                .extracting(UserProfileDTO::role).containsExactly(Role.STUDENT);
    }

    @Test
    void listingEveryoneSortsByEmail() {
        when(userRepository.findAll(Sort.by("email"))).thenReturn(List.of(user(1L, Role.TNP_PIC), user(5L, Role.STUDENT)));

        assertThat(userService.findAll(Optional.empty())).hasSize(2);
    }

    /** An empty set of ids costs no query at all. */
    @Test
    void emailsForNoIdsSkipsTheDatabase() {
        assertThat(userService.emailsByIds(Set.of())).isEmpty();
        verifyNoInteractions(userRepository);
    }

    private static User user(Long id, Role role) {
        User user = new User();
        user.setId(id);
        user.setEmail("user" + id + "@cse.nits.ac.in");
        user.setRole(role);
        user.setBacklogs(0);
        return user;
    }
}
