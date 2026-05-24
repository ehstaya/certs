package com.sfquiz.security;

import com.sfquiz.entity.User;
import com.sfquiz.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    public CustomUserDetailsService(UserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User u = users.findByEmailIgnoreCase(username)
                .orElseThrow(() -> new UsernameNotFoundException("No account found for " + username));
        return new AppUserDetails(u);
    }
}
