package com.murmur.common;

import com.github.f4b6a3.uuid.UuidCreator;
import java.util.UUID;

public final class Ids {

    private Ids() {}

    public static UUID newId() {
        return UuidCreator.getTimeOrderedEpoch();
    }

    // UUIDv7 stores unix epoch millis in the high 48 bits.
    public static long millis(UUID v7) {
        return v7.getMostSignificantBits() >>> 16;
    }
}
