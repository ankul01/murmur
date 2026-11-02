package com.murmur.userservice.user;

import com.murmur.common.Ids;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
class UserService {
    private static final Pattern HANDLE = Pattern.compile("^[a-z0-9_]{3,30}$");

    private final UserRepository repository;

    UserService(UserRepository repository) {
        this.repository = repository;
    }

    User create(CreateUserRequest request) {
        String handle = request.handle() == null ? "" : request.handle().toLowerCase(Locale.ROOT);
        String name = request.displayName() == null ? "" : request.displayName().strip();
        int nameLength = name.codePointCount(0, name.length());
        if (!HANDLE.matcher(handle).matches() || nameLength < 1 || nameLength > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid handle or displayName");
        }
        var user = new User(Ids.newId(), handle, name, 0, 0, false, OffsetDateTime.now(ZoneOffset.UTC));
        try {
            repository.insert(user);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "handle taken");
        }
        return user;
    }

    User get(UUID id) {
        return repository
                .findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found"));
    }
}
