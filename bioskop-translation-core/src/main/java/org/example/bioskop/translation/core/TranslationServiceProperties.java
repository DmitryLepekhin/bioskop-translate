package org.example.bioskop.translation.core;

import java.time.Duration;

public record TranslationServiceProperties(
    int quickImmediateMaxChars,
    Duration quickImmediateTimeout,
    Duration inProgressTimeout,
    int maxAttempts
) {
    public static TranslationServiceProperties defaults() {
        return new TranslationServiceProperties(1000, Duration.ofSeconds(8), Duration.ofMinutes(5), 5);
    }
}
