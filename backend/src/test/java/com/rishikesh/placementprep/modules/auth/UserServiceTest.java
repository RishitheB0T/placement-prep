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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

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

    @InjectMocks
    private UserService userService;

    @Test
    void promotingAStudentMakesThemARecruiter() {
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
