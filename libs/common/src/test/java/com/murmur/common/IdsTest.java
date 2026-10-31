package com.murmur.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class IdsTest {

    @Test
    void newId_isUuidV7() {
        var id = Ids.newId();
        long now = Instant.now().toEpochMilli();

        assertThat(id.version()).isEqualTo(7);
        assertThat(Ids.millis(id)).isBetween(now - 5_000, now + 5_000);
    }
}
