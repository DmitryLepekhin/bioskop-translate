package org.example.bioskop.translation.core;

import java.time.Duration;

public interface TranslationTelemetry {
    enum ProviderOutcome {
        SUCCESS,
        TIMEOUT,
        THROTTLED,
        TRANSIENT_FAILURE,
        PERMANENT_FAILURE
    }

    default void jobClaimed(boolean retry, boolean reclaimed) {
    }

    default void jobCompleted() {
    }

    default void jobFailed() {
    }

    default void providerCall(Duration duration, ProviderOutcome outcome) {
    }

    static TranslationTelemetry noop() {
        return new TranslationTelemetry() {
        };
    }
}
