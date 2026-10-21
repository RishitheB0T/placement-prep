package com.rishikesh.placementprep.modules.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.rishikesh.placementprep.modules.auth.dto.AuthResponse;
import com.rishikesh.placementprep.modules.auth.dto.LoginRequest;
import com.rishikesh.placementprep.modules.auth.dto.RegisterRequest;
import com.rishikesh.placementprep.modules.auth.model.Role;
import com.rishikesh.placementprep.modules.auth.model.User;
import com.rishikesh.placementprep.modules.auth.repository.UserRepository;
import com.rishikesh.placementprep.modules.auth.service.AuthService;
import com.rishikesh.placementprep.modules.auth.service.JwtService;

/** Unit tests for registration and login, with every collaborator mocked. */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private JwtService jwtService;

    @InjectMocks
    private AuthService authService;

    @Test
    void registeringStoresALowercaseEmailAHashAndAlwaysTheStudentRole() {
        when(userRepository.existsByEmail("asha@cse.nits.ac.in")).thenReturn(false);
        when(passwordEncoder.encode("correct-horse")).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(jwtService.generateToken("asha@cse.nits.ac.in", Role.STUDENT)).thenReturn("a.b.c");

        Optional<AuthResponse> response = authService.register(new RegisterRequest("  Asha@CSE.nits.ac.in ", "correct-horse"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("asha@cse.nits.ac.in");
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("hashed");
        assertThat(saved.getValue().getRole()).isEqualTo(Role.STUDENT);
        assertThat(response).get().extracting(AuthResponse::token).isEqualTo("a.b.c");
    }

    @Test
    void registeringAnEmailThatExistsSavesNothing() {
        when(userRepository.existsByEmail("asha@cse.nits.ac.in")).thenReturn(true);

        assertThat(authService.register(new RegisterRequest("asha@cse.nits.ac.in", "correct-horse"))).isEmpty();
        verify(userRepository, never()).save(any());
    }

    @Test
    void loginIssuesATokenCarryingTheRoleSpringSecurityFound() {
        when(authenticationManager.authenticate(any())).thenReturn(new UsernamePasswordAuthenticationToken(
                "coord@tnp.nits.ac.in", null, List.of(new SimpleGrantedAuthority("ROLE_TNP_COORDINATOR"))));
        when(jwtService.generateToken("coord@tnp.nits.ac.in", Role.TNP_COORDINATOR)).thenReturn("a.b.c");

        Optional<AuthResponse> response = authService.login(new LoginRequest("Coord@TNP.nits.ac.in", "password"));

        assertThat(response).get().satisfies(r -> {
            assertThat(r.role()).isEqualTo(Role.TNP_COORDINATOR);
            assertThat(r.token()).isEqualTo("a.b.c");
        });
    }

    /** One answer for a wrong password and an unknown email alike, so neither leaks. */
    @Test
    void aRejectedLoginIsEmpty() {
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("Bad credentials"));

        assertThat(authService.login(new LoginRequest("asha@cse.nits.ac.in", "wrong"))).isEmpty();
    }

    /** A broken lookup is a server fault, not a wrong password - it must not be swallowed. */
    @Test
    void aFailedLookupIsRethrownRatherThanReportedAsBadCredentials() {
        when(authenticationManager.authenticate(any())).thenThrow(new AuthenticationServiceException("database down"));

        assertThatThrownBy(() -> authService.login(new LoginRequest("asha@cse.nits.ac.in", "password")))
                .isInstanceOf(AuthenticationServiceException.class);
    }
}
