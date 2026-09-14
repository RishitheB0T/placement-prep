package com.rishikesh.placementprep.modules.auth.service;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rishikesh.placementprep.modules.auth.model.User;
import com.rishikesh.placementprep.modules.auth.repository.UserRepository;

/**
 * Adapts our User entity into the shape Spring Security expects.
 *
 * <p>Spring Security never sees our entity. It asks this class for a UserDetails by
 * username and gets back a security-specific object. Keeping the two apart means the
 * persistence model can change without dragging security along, and vice versa.
 *
 * <p>The exception message is deliberately vague. Saying "no account for that address"
 * would let anyone probe which emails are registered.
 */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public AppUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("Bad credentials"));

        // The ROLE_ prefix is not decoration. hasRole("TNP_ADMIN") in SecurityConfig looks
        // for an authority literally named ROLE_TNP_ADMIN, adding the prefix on our behalf.
        // Omitting it here is the single most common reason a correct-looking security
        // config returns 403 for a user who should be allowed through.
        var authority = new SimpleGrantedAuthority("ROLE_" + user.getRole().name());

        return org.springframework.security.core.userdetails.User
                .withUsername(user.getEmail())
                .password(user.getPasswordHash())
                .authorities(authority)
                .build();
    }
}
