package com.murmur.userservice.user;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users")
class UserController {
    private final UserService users;

    UserController(UserService users) {
        this.users = users;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    UserResponse create(@RequestBody CreateUserRequest request) {
        return UserResponse.from(users.create(request));
    }

    @GetMapping("/{id}")
    UserDetailsResponse get(@PathVariable UUID id) {
        return UserDetailsResponse.from(users.get(id));
    }
}
