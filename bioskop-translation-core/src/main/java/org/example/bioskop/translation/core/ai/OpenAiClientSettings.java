package org.example.bioskop.translation.core.ai;

import java.net.URI;
import java.time.Duration;

public record OpenAiClientSettings(
    URI endpoint,
    Duration connectTimeout,
    Duration requestTimeout,
    int maxAttempts,
    Duration initialBackoff,
    Duration maxBackoff,
    double jitterFactor
) {
    public OpenAiClientSettings {
        if (endpoint == null) {
            throw new IllegalArgumentException("endpoint must not be null");
        }
        requirePositive(connectTimeout, "connectTimeout");
        requirePositive(requestTimeout, "requestTimeout");
        requireNonNegative(initialBackoff, "initialBackoff");
        requireNonNegative(maxBackoff, "maxBackoff");
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        if (maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("maxBackoff must not be shorter than initialBackoff");
        }
        if (jitterFactor < 0 || jitterFactor > 1) {
            throw new IllegalArgumentException("jitterFactor must be between 0 and 1");
        }
    }

    public static OpenAiClientSettings defaults() {
        return new OpenAiClientSettings(
            URI.create("https://api.openai.com/v1/responses"),
            Duration.ofSeconds(10),
            Duration.ofSeconds(60),
            3,
            Duration.ofMillis(250),
            Duration.ofSeconds(5),
            0.2
        );
    }

    private static void requirePositive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireNonNegative(Duration duration, String name) {
        if (duration == null || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
