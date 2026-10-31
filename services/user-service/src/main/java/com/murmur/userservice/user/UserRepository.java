package com.murmur.userservice.user;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class UserRepository {
    private final JdbcClient jdbc;

    UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(User u) {
        jdbc.sql("""
                INSERT INTO users (id, handle, display_name, created_at)
                VALUES (:id, :handle, :displayName, :createdAt)
                """)
                .param("id", u.id())
                .param("handle", u.handle())
                .param("displayName", u.displayName())
                .param("createdAt", u.createdAt())
                .update();
    }

    Optional<User> findById(UUID id) {
        return jdbc.sql("SELECT * FROM users WHERE id = :id")
                .param("id", id)
                .query(User.class)
                .optional();
    }
}
