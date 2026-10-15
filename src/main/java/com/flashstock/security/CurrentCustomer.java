package com.flashstock.security;

import org.springframework.security.core.Authentication;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class CurrentCustomer {
    private CurrentCustomer() { }
    public static UUID id(Authentication authentication) {
        Object principal = authentication.getPrincipal();
        if (principal instanceof CustomerPrincipal customer) return customer.customerId();
        return UUID.nameUUIDFromBytes(authentication.getName().getBytes(StandardCharsets.UTF_8));
    }
}
