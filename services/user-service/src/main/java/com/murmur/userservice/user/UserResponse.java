package com.murmur.userservice.user;

import java.util.UUID;

public record UserResponse(UUID id, String handle, String displayName) {
    static UserResponse from(User u) {
        return new UserResponse(u.id(), u.handle(), u.displayName());
    }
}
