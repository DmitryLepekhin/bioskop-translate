package org.example.bioskop.translation.core.persistence;

import java.time.Instant;
import java.util.UUID;
import org.example.bioskop.translation.core.TranslationStatus;

public record TranslationJobRecord(
    UUID id,
    UUID sourceTextId,
    String sourcePath,
    String sourceLang,
    String targetLang,
    String targetPath,
    TranslationStatus status,
    int attempts,
    String errorCode,
    String errorMessage,
    Instant createdAt,
    Instant updatedAt,
    Instant completedAt,
    UUID leaseToken,
    Instant leaseExpiresAt
) {
}
