package org.example.bioskop.translation.core.persistence;

import java.time.Instant;
import java.util.UUID;
import org.example.bioskop.translation.core.TranslationStatus;

public record TranslationJobAttemptRecord(
    UUID id,
    UUID jobId,
    int attemptNo,
    TranslationStatus status,
    Instant startedAt,
    Instant finishedAt,
    String errorCode,
    String errorMessage
) {
}
