package com.flashstock.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public record CustomerPrincipal(UUID customerId, String username, String password) implements UserDetails {
    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")); }
    @Override public String getPassword() { return password; }
    @Override public String getUsername() { return username; }
}
