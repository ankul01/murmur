package com.murmur.userservice.user;

import java.time.OffsetDateTime;
import java.util.UUID;

record User(
        UUID id,
        String handle,
        String displayName,
        long followerCount,
        long followingCount,
        boolean isCelebrity,
        OffsetDateTime createdAt) {}
