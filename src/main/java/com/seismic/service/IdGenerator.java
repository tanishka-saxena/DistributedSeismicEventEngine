package com.seismic.service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Produces readable, unique IDs such as {@code EVL-20261013T101500-0007}. The timestamp part is the
 * time the generator was created, so IDs from different application runs do not collide in the database.
 * {@link AtomicLong} makes it safe to call from several threads.
 */
public final class IdGenerator implements Supplier<String> {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS").withZone(ZoneOffset.UTC);

    private final String prefix;
    private final AtomicLong counter = new AtomicLong();

    public IdGenerator(String prefix, Instant createdAt) {
        this.prefix = prefix + "-" + STAMP.format(createdAt) + "-";
    }

    @Override
    public String get() {
        return prefix + String.format("%04d", counter.incrementAndGet());
    }
}
