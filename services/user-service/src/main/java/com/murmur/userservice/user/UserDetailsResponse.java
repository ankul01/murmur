package com.murmur.userservice.user;

import java.time.OffsetDateTime;
import java.util.UUID;

public record UserDetailsResponse(
        UUID id,
        String handle,
        String displayName,
        long followerCount,
        long followingCount,
        boolean isCelebrity,
        OffsetDateTime createdAt) {
    static UserDetailsResponse from(User u) {
        return new UserDetailsResponse(
                u.id(),
                u.handle(),
                u.displayName(),
                u.followerCount(),
                u.followingCount(),
                u.isCelebrity(),
                u.createdAt());
    }
}
