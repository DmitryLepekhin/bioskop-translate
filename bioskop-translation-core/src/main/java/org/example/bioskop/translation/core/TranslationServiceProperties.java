package org.example.bioskop.translation.core;

import java.time.Duration;

public record TranslationServiceProperties(
    int quickImmediateMaxChars,
    Duration quickImmediateTimeout,
    Duration leaseDuration,
    Duration heartbeatInterval,
    int maxAttempts
) {
    public TranslationServiceProperties {
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        if (heartbeatInterval == null || heartbeatInterval.isZero() || heartbeatInterval.isNegative()) {
            throw new IllegalArgumentException("heartbeatInterval must be positive");
        }
        if (heartbeatInterval.compareTo(leaseDuration) >= 0) {
            throw new IllegalArgumentException("heartbeatInterval must be shorter than leaseDuration");
        }
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
    }

    public static TranslationServiceProperties defaults() {
        return new TranslationServiceProperties(
            1000,
            Duration.ofSeconds(8),
            Duration.ofSeconds(90),
            Duration.ofSeconds(20),
            5
        );
    }
}
